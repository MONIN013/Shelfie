"""Export Shelfie's runtime book, prop and marker GLBs with Blender.

    blender --background --python tools/assets/build_assets.py -- --output /absolute/path/to/Shelfie/assets

The single book model carries neutral cover and spine images. The app replaces both
images at runtime with the registered book's cover or a generated title cover.
"""
from pathlib import Path
import argparse
import math
import sys

ROOT = Path(__file__).resolve().parents[2]


def arguments():
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else sys.argv[1:]
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "assets")
    return parser.parse_args(args)


def blender_assets(output):
    import bpy
    bpy.context.preferences.filepaths.save_version = 0
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    bpy.context.scene.world.color = (0.25, 0.25, 0.25)
    models = output / "models"
    models.mkdir(parents=True, exist_ok=True)
    current = None

    def srgb(value):
        value = value / 255
        return value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4

    def placeholder(name, width, height, color):
        # Saved as PNG so the exporter embeds an ordinary image the runtime can replace.
        work = output.parent / ".asset-work"
        work.mkdir(parents=True, exist_ok=True)
        image = bpy.data.images.new(name, width, height)
        rgb = [int(color.lstrip("#")[i:i+2], 16) / 255 for i in (0, 2, 4)]
        image.pixels = (rgb + [1.0]) * (width * height)
        image.filepath_raw = str(work / f"{name}.png")
        image.file_format = "PNG"
        image.save()
        return work / f"{name}.png"

    def material(name, color, roughness=.72, texture=None):
        mat = bpy.data.materials.new(name)
        mat.use_nodes = True
        rgba = tuple(srgb(int(color.lstrip("#")[i:i+2], 16)) for i in (0, 2, 4)) + (1,)
        mat.diffuse_color = rgba
        bsdf = mat.node_tree.nodes.get("Principled BSDF")
        bsdf.inputs["Base Color"].default_value = rgba
        bsdf.inputs["Roughness"].default_value = roughness
        if texture:
            node = mat.node_tree.nodes.new("ShaderNodeTexImage")
            node.image = bpy.data.images.load(str(texture))
            node.image.pack()
            mat.node_tree.links.new(node.outputs["Color"], bsdf.inputs["Base Color"])
        return mat

    def collection(name):
        nonlocal current
        current = bpy.data.collections.new(name)
        bpy.context.scene.collection.children.link(current)
        return current

    def assign(obj, mat):
        for coll in list(obj.users_collection):
            coll.objects.unlink(obj)
        current.objects.link(obj)
        obj.data.materials.append(mat)
        return obj

    def xyz(x, y, z):
        return (x, -z, y)

    def box(name, center, size, mat, bevel=.02):
        bpy.ops.mesh.primitive_cube_add(size=1, location=xyz(*center))
        obj = bpy.context.object
        obj.name = name
        obj.dimensions = (size[0], size[2], size[1])
        bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
        if bevel:
            modifier = obj.modifiers.new("Soft manufactured edges", "BEVEL")
            modifier.width = bevel
            modifier.segments = 3
            bpy.ops.object.modifier_apply(modifier=modifier.name)
            modifier = obj.modifiers.new("Weighted face normals", "WEIGHTED_NORMAL")
            bpy.ops.object.modifier_apply(modifier=modifier.name)
        assign(obj, mat)
        return obj

    def image_face(name, points, mat):
        mesh = bpy.data.meshes.new(name)
        mesh.from_pydata([xyz(*p) for p in points], [], [(0, 1, 2, 3)])
        mesh.uv_layers.new(name="Editorial artwork")
        for loop, uv in zip(mesh.uv_layers.active.data, [(0, 0), (1, 0), (1, 1), (0, 1)]):
            loop.uv = uv
        obj = bpy.data.objects.new(name, mesh)
        current.objects.link(obj)
        mesh.materials.append(mat)
        return obj

    def export(coll, name):
        bpy.ops.object.select_all(action="DESELECT")
        for obj in coll.objects:
            obj.select_set(True)
        bpy.ops.export_scene.gltf(filepath=str(models / f"{name}.glb"), export_format="GLB", use_selection=True,
            export_yup=True, export_apply=True, export_texcoords=True, export_normals=True, export_materials="EXPORT")

    paper = material("Warm uncoated paper", "#eee6d3", .92)
    paper_edge = material("Subtle page edge shadows", "#d8d0bb", .95)
    coll = collection("book")
    cloth = material("matte binding", "#7d6f8f", .83)
    front = material("cover image", "#e9e1d3", .73, placeholder("book-cover", 8, 12, "#e9e1d3"))
    spine = material("spine image", "#e9e1d3", .78, placeholder("book-spine", 2, 12, "#e9e1d3"))
    box("Inset paper block", (.015, .895, 0), (1.13, 1.70, .18), paper, .011)
    box("Front cover with softly beveled edges", (0, .9, .108), (1.2, 1.8, .024), cloth, .009)
    box("Back cover with softly beveled edges", (0, .9, -.108), (1.2, 1.8, .024), cloth, .009)
    box("Rounded cloth binding", (-.577, .9, 0), (.046, 1.8, .24), cloth, .018)
    image_face("Cover image", [(-.582, .018, .1205), (.582, .018, .1205), (.582, 1.782, .1205), (-.582, 1.782, .1205)], front)
    image_face("Spine image", [(-.6005, .023, -.098), (-.6005, .023, .098), (-.6005, 1.777, .098), (-.6005, 1.777, -.098)], spine)
    # Fine separate page-edge strips on the fore edge, exposed by quarter turns.
    for i in range(8):
        box(f"Paper leaf edge {i:02}", (.581, .895, -.074 + i * .021), (.002, 1.66, .0013), paper_edge, 0)
    export(coll, "book")

    ceramic = material("Warm ivory satin ceramic", "#edddc6", .48)
    clay = material("Dusty rose sculptural ceramic", "#c89591", .74)
    green = material("Sage stone", "#a5b5a6", .91)
    coll = collection("prop-pebble")
    bpy.ops.mesh.primitive_uv_sphere_add(segments=32, ring_count=16, location=xyz(0, .22, 0))
    obj = assign(bpy.context.object, green)
    obj.name = "Smooth sculptural pebble"
    obj.scale = (.34, .28, .22)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    for poly in obj.data.polygons:
        poly.use_smooth = True
    export(coll, "prop-pebble")

    coll = collection("prop-vase")
    profile = [(0, .24), (.035, .27), (.13, .32), (.37, .35), (.62, .23), (.76, .13), (1.04, .13), (1.06, .11), (.99, .09), (.77, .09), (.61, .18)]
    vertices, faces = [], []
    for y, radius in profile:
        for i in range(40):
            angle = i * math.tau / 40
            vertices.append(xyz(math.cos(angle) * radius, y, math.sin(angle) * radius))
    for ring in range(len(profile) - 1):
        for i in range(40):
            a, b = ring * 40 + i, ring * 40 + (i + 1) % 40
            faces.append((a, a + 40, b + 40, b))
    faces.append(tuple(reversed(range(40))))
    mesh = bpy.data.meshes.new("Wheel thrown hollow vase")
    mesh.from_pydata(vertices, [], faces)
    obj = bpy.data.objects.new("Wheel thrown hollow vase", mesh)
    current.objects.link(obj)
    mesh.materials.append(ceramic)
    for poly in mesh.polygons:
        poly.use_smooth = True
    export(coll, "prop-vase")

    coll = collection("prop-arch")
    # Extruded arch band with a true open center, rather than intersecting solids.
    vertices, faces = [], []
    profile = [(-.35, 0), (-.35, .35)]
    profile += [(math.cos(math.pi - i * math.pi / 24) * .35, .35 + math.sin(math.pi - i * math.pi / 24) * .35) for i in range(25)]
    profile += [(.35, 0), (.19, 0), (.19, .35)]
    profile += [(math.cos(i * math.pi / 24) * .19, .35 + math.sin(i * math.pi / 24) * .19) for i in range(25)]
    profile += [(-.19, 0)]
    # Avoid duplicate arc endpoints that would introduce degenerate faces.
    cleaned = []
    for point in profile:
        if not cleaned or abs(point[0] - cleaned[-1][0]) + abs(point[1] - cleaned[-1][1]) > .00001:
            cleaned.append(point)
    count = len(cleaned)
    vertices = [xyz(x, y, z) for z in [-.13, .13] for x, y in cleaned]
    faces = [tuple(reversed(range(count))), tuple(range(count, count * 2))]
    faces += [(i, (i + 1) % count, (i + 1) % count + count, i + count) for i in range(count)]
    mesh = bpy.data.meshes.new("Open ceramic arch")
    mesh.from_pydata(vertices, [], faces)
    obj = bpy.data.objects.new("Open ceramic arch", mesh)
    current.objects.link(obj)
    mesh.materials.append(clay)
    bpy.context.view_layer.objects.active = obj
    obj.select_set(True)
    mod = obj.modifiers.new("Soft ceramic arris", "BEVEL")
    mod.width = .013
    mod.segments = 3
    bpy.ops.object.modifier_apply(modifier=mod.name)
    export(coll, "prop-arch")

    for status, color in [("valid", "#a7cbbb"), ("invalid", "#d88e91")]:
        coll = collection(f"marker-{status}")
        marker = material(f"Placement {status}", color, .84)
        box("Placement footprint", (0, .0175, 0), (1, .035, 1), marker, .012)
        export(coll, f"marker-{status}")

    print(f"Exported book, prop and marker GLBs to {output}")


if __name__ == "__main__":
    args = arguments()
    blender_assets(args.output.resolve())
