"""Check native SurfaceView presentation during idle/background on the selected lab APK.

Starts the app, waits for assets, samples SurfaceFlinger, backgrounds and restores it.
No library data is changed. Zero/unsupported timestamps are reported as unverified.
"""
import argparse
import json
from pathlib import Path
import shlex
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', required=True)
parser.add_argument('--output-directory', default='artifacts/measurements')
args = parser.parse_args()
package = 'net.monindev.shelfie.lab.filament'


def adb(*parts):
    return subprocess.check_output([args.adb, '-s', args.serial, *parts], timeout=40).decode('utf-8').strip()


def launch():
    adb('shell', 'am', 'start', '-W', '-n', package + '/net.monindev.shelfie.app.MainActivity')


def snapshot():
    layers = [line for line in adb('shell', 'dumpsys', 'SurfaceFlinger', '--list').splitlines()
              if package in line and 'SurfaceView' in line and 'Background' not in line]
    values = {}
    for layer in layers:
        raw = adb('shell', 'dumpsys SurfaceFlinger --latency ' + shlex.quote(layer))
        present = []
        for line in raw.splitlines()[1:]:
            fields = line.split()
            if len(fields) == 3 and all(field.isdigit() for field in fields):
                timestamp = int(fields[1])
                if 0 < timestamp < 9223372036854775807:
                    present.append(timestamp)
        values[layer] = max(present) if present else None
    return values


launch()
time.sleep(12)
before = snapshot()
time.sleep(5)
after = snapshot()
presented_layers = {layer: timestamp for layer, timestamp in before.items() if timestamp is not None}
app_pid = adb('shell', 'pidof', package)
log_before = adb('logcat', '-d', '--pid=' + app_pid, '-v', 'brief')
idle_events = [line for line in log_before.splitlines() if 'idle frames=' in line]
adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
time.sleep(5)
background = snapshot()
log_background = adb('logcat', '-d', '--pid=' + app_pid, '-v', 'brief')
background_events = [line for line in log_background.splitlines() if 'idle frames=' in line]
launch()
result = {
    'renderer': 'filament',
    'idle_seconds': 5,
    'background_seconds': 5,
    'before': before,
    'after': after,
    'background': background,
    'observed_layers_idle_presentation_stopped': all(after.get(layer) == timestamp for layer, timestamp in presented_layers.items()) if presented_layers else None,
    'observed_buffer_layers': list(presented_layers),
    'last_idle_event': idle_events[-1] if idle_events else None,
    'last_background_event': background_events[-1] if background_events else None,
    'limitation': 'Missing SurfaceView timestamps mean unverified, not zero GPU work. Not an energy measurement.',
}
out = Path(__file__).resolve().parents[1] / args.output_directory / 'idle.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False, indent=2))
