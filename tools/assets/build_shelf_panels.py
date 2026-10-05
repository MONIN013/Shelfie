"""Generate unit-sized shelf panels; SceneComposer controls inner dimensions and board thickness.

Run with Blender: blender --background --python tools/assets/build_shelf_panels.py -- --output-root <repo>
Writes only assets/models/panel-{oak,lavender}.glb. No existing shelf/book assets are replaced.
"""
import argparse
import math
from pathlib import Path
import sys


def build(root):
    import bpy
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    size = 256
    grain = bpy.data.images.new("Natural oak grain", width=size, height=size)
    pixels = []
    for y in range(size):
        for x in range(size):
            u, v = x / size, y / size
            bands = math.sin((u + .018*math.sin(v*19) + .012*math.sin(v*7)) * 210)
            pores = max(0, math.sin(u*390 + math.sin(v*32))) ** 18
            shade = .018*bands - .025*pores + .009*math.sin(u*37 + v*3)
            pixels.extend((.73+shade, .57+shade*.8, .37+shade*.5, 1))
    grain.pixels = pixels
    grain.pack()
    for finish in ("oak", "lavender"):
        bpy.ops.object.select_all(action="SELECT")
        bpy.ops.object.delete(use_global=False)
        bpy.ops.mesh.primitive_cube_add(size=1)
        panel = bpy.context.object
        panel.name = "Unit panel - dimensions supplied at runtime"
        material = bpy.data.materials.new(f"Shelf {finish}")
        material.use_nodes = True
        shader = next((n for n in material.node_tree.nodes if n.type == "BSDF_PRINCIPLED"), None)
        if shader is None:
            shader = material.node_tree.nodes.new("ShaderNodeBsdfPrincipled")
            output = material.node_tree.nodes.new("ShaderNodeOutputMaterial")
            material.node_tree.links.new(shader.outputs["BSDF"], output.inputs["Surface"])
        shader.inputs["Roughness"].default_value = .77
        if finish == "oak":
            texture = material.node_tree.nodes.new("ShaderNodeTexImage")
            texture.image = grain
            material.node_tree.links.new(texture.outputs["Color"], shader.inputs["Base Color"])
        else:
            shader.inputs["Base Color"].default_value = (.527, .462, .624, 1)
        panel.data.materials.append(material)
        bevel = panel.modifiers.new("Small finished edge", "BEVEL")
        bevel.width, bevel.segments = .0015, 3
        bpy.ops.object.modifier_apply(modifier=bevel.name)
        destination = root / "assets" / "models" / f"panel-{finish}.glb"
        destination.parent.mkdir(parents=True, exist_ok=True)
        bpy.ops.export_scene.gltf(filepath=str(destination), export_format="GLB", use_selection=True,
                                 export_yup=True, export_apply=True, export_normals=True, export_materials="EXPORT")
        print(f"Wrote {destination}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", type=Path, required=True)
    args = parser.parse_args(sys.argv[sys.argv.index("--")+1:] if "--" in sys.argv else [])
    build(args.output_root.resolve())
