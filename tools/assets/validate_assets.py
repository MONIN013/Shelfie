"""Read-only GLB contract validation; uses only Python's standard library.

Checks binary chunk lengths, embedded resources, finite position data, node-transformed
bounds, and material/texture references. Not a substitute for the
Khronos validator or each renderer's visual QA.
"""
from pathlib import Path
import json
import math
import struct

ROOT = Path(__file__).resolve().parents[2]
IDENTITY = [[1 if r == c else 0 for c in range(4)] for r in range(4)]


def multiply(a, b):
    return [[sum(a[r][k] * b[k][c] for k in range(4)) for c in range(4)] for r in range(4)]


def matrix(node):
    if "matrix" in node:
        return [[node["matrix"][c * 4 + r] for c in range(4)] for r in range(4)]
    x, y, z, w = node.get("rotation", [0, 0, 0, 1])
    rotation = [
        [1 - 2*(y*y + z*z), 2*(x*y-z*w), 2*(x*z+y*w), 0],
        [2*(x*y+z*w), 1 - 2*(x*x+z*z), 2*(y*z-x*w), 0],
        [2*(x*z-y*w), 2*(y*z+x*w), 1 - 2*(x*x+y*y), 0],
        [0, 0, 0, 1],
    ]
    for c, scale in enumerate(node.get("scale", [1, 1, 1])):
        for r in range(3):
            rotation[r][c] *= scale
    for r, translation in enumerate(node.get("translation", [0, 0, 0])):
        rotation[r][3] = translation
    return rotation


def validate(path):
    raw = path.read_bytes()
    magic, version, length = struct.unpack_from("<III", raw)
    assert magic == 0x46546C67 and version == 2 and length == len(raw), path
    json_length, chunk_type = struct.unpack_from("<II", raw, 12)
    assert chunk_type == 0x4E4F534A
    doc = json.loads(raw[20:20 + json_length])
    assert doc["asset"]["version"] == "2.0"
    offset = 20 + json_length
    binary_length, chunk_type = struct.unpack_from("<II", raw, offset)
    assert chunk_type == 0x004E4942 and offset + 8 + binary_length == len(raw)
    binary = raw[offset + 8:]
    assert len(doc["buffers"]) == 1 and "uri" not in doc["buffers"][0]
    assert doc["buffers"][0]["byteLength"] <= len(binary)
    for view in doc["bufferViews"]:
        assert view.get("byteOffset", 0) + view["byteLength"] <= len(binary)
    for image in doc.get("images", []):
        assert "uri" not in image and "bufferView" in image
        view = doc["bufferViews"][image["bufferView"]]
        start = view.get("byteOffset", 0)
        assert binary[start:start + 8] == b"\x89PNG\r\n\x1a\n", path
    positions = []
    mesh_positions = {}

    def visit(index, parent):
        node = doc["nodes"][index]
        world = multiply(parent, matrix(node))
        if "mesh" in node:
            node_positions = mesh_positions.setdefault(node.get("name", str(index)), [])
            for primitive in doc["meshes"][node["mesh"]]["primitives"]:
                assert primitive.get("mode", 4) == 4
                assert "NORMAL" in primitive["attributes"]
                accessor = doc["accessors"][primitive["attributes"]["POSITION"]]
                assert accessor["type"] == "VEC3" and accessor["componentType"] == 5126
                view = doc["bufferViews"][accessor["bufferView"]]
                start = view.get("byteOffset", 0) + accessor.get("byteOffset", 0)
                stride = view.get("byteStride", 12)
                for i in range(accessor["count"]):
                    local = struct.unpack_from("<fff", binary, start + i * stride) + (1,)
                    point = [sum(world[r][c] * local[c] for c in range(4)) for r in range(3)]
                    assert all(math.isfinite(value) for value in point)
                    positions.append(point)
                    node_positions.append(point)
                mat = doc["materials"][primitive["material"]]
                assert "pbrMetallicRoughness" in mat
        for child in node.get("children", []):
            visit(child, world)

    for root in doc["scenes"][doc.get("scene", 0)]["nodes"]:
        visit(root, IDENTITY)
    low = [min(p[axis] for p in positions) for axis in range(3)]
    high = [max(p[axis] for p in positions) for axis in range(3)]
    size = [high[i] - low[i] for i in range(3)]
    assert len(positions) > 24, (path, "Expected authored, beveled geometry")
    if path.stem == "book":
        assert all(abs(actual - expected) < .002 for actual, expected in zip(size, [1.2, 1.8, .24])), (path, size)
        assert abs(low[1]) < .001 and abs(low[0] + .6) < .001, (path, low)
        assert len(doc.get("images", [])) == 2
    elif path.stem.startswith("prop-"):
        assert size[0] <= .701 and abs(low[1]) < .001 and size[1] <= 1.1, (path, low, size)
    elif path.stem.startswith("marker-"):
        assert all(abs(actual - expected) < .001 for actual, expected in zip(size, [1, .035, 1])), (path, size)
    elif path.stem.startswith("panel-"):
        assert all(abs(actual - 1) < .002 for actual in size), (path, size)
        assert all(abs(actual + .5) < .002 for actual in low), (path, low)
        assert len(doc.get("images", [])) == (1 if path.stem == "panel-oak" else 0)
    return {"file": path.name, "bytes": len(raw), "vertices": len(positions), "min": low, "max": high}


if __name__ == "__main__":
    models = ROOT / "assets" / "models"
    files = sorted(models.glob("*.glb"))
    expected = {"book.glb", "panel-oak.glb", "panel-lavender.glb", "prop-pebble.glb", "prop-vase.glb", "prop-arch.glb", "marker-valid.glb", "marker-invalid.glb"}
    assert {file.name for file in files} == expected
    assert {path.name for path in (ROOT / "assets").iterdir()} == {"models", "README.md"}
    reports = [validate(path) for path in files]
    print(f"PASS: {len(reports)} GLBs; finite geometry, contract bounds, embedded PNGs, PBR materials")
    print(f"Runtime GLB total: {sum(report['bytes'] for report in reports):,} bytes")
