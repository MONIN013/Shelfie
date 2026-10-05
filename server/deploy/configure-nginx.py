"""Run with sudo on nogiku after reviewing the snippet. Back up, validate, then reload.

Only the existing monindev.net HTTPS block receives an include. A failed nginx
validation restores the original file; existing routes and certificate files stay intact.
"""
from pathlib import Path
import datetime
import os
import subprocess
import sys

if os.geteuid() != 0:
    sys.exit("Run: sudo python3 deploy/configure-nginx.py")
target = Path("/etc/nginx/sites-available/default")
snippet = Path("/etc/nginx/snippets/shelfie.conf")
source = Path(__file__).with_name("shelfie.nginx.conf")
original = target.read_text()
include = "    include /etc/nginx/snippets/shelfie.conf;"
anchor = "    server_name monindev.net;"
if include not in original:
    if original.count(anchor) != 1 or "listen 443 ssl;" not in original:
        sys.exit("Unexpected nginx layout; review the HTTPS server block manually.")
    if "location ^~ /shelfie/" in original:
        sys.exit("A Shelfie route already exists; review before modifying it.")
    updated = original.replace(anchor, anchor + "\n" + include, 1)
else:
    updated = original
previous_snippet = snippet.read_bytes() if snippet.exists() else None
backup = target.with_name("default.shelfie-backup-" + datetime.datetime.now().strftime("%Y%m%d%H%M%S"))
backup.write_text(original)
try:
    snippet.write_bytes(source.read_bytes())
    target.write_text(updated)
    subprocess.run(["nginx", "-t"], check=True)
    subprocess.run(["systemctl", "reload", "nginx"], check=True)
except BaseException:
    target.write_text(original)
    if previous_snippet is None:
        snippet.unlink(missing_ok=True)
    else:
        snippet.write_bytes(previous_snippet)
    raise
print("Shelfie HTTPS route enabled. Backup:", backup)
