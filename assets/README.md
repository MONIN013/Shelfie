# Shelfie runtime assets

The app uses eight GLBs: one book model, two unit shelf panels, three props and two placement markers. There are no sample books, sample shelves or bundled photographs.

## Geometry

- glTF uses Y up. The book bottom is at Y=0, its center at X=0, the cover faces +Z and the spine faces -X.
- `book.glb` has normalized size 1.2 × 1.8 × 0.24. Runtime transforms apply each book's actual dimensions; one world unit is 100 mm. It embeds two neutral images named for the cover and the spine; the app replaces both with the book's cover or a generated title cover, and tints the binding to match.
- Unit panels have size 1 × 1 × 1. SceneComposer creates the floor, roof, sides and back with fixed board thickness around the configured inner volume.
- Props are pebble, vase and arch, with bottoms at Y=0 and width up to 0.7. Markers have width/depth 1 and height 0.035.

## Reproduction

Use Blender 5.2. Run from the repository root:

```sh
blender --background --factory-startup --python tools/assets/build_assets.py -- --output /absolute/path/to/Shelfie/assets
blender --background --factory-startup --python tools/assets/build_shelf_panels.py -- --output-root /absolute/path/to/Shelfie
python tools/assets/validate_assets.py
```

The first command exports the book, prop and marker GLBs; the second creates the shelf panels. `build_assets.py` writes its placeholder PNGs to `.asset-work/`, which can be removed afterwards and is not packaged. The scripts are the editable source; generated Blender scenes and previews are not retained.

## Provenance

Original geometry and the placeholder images are dedicated to CC0 1.0. No fonts or third-party images are bundled.
