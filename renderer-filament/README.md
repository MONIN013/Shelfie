# Filament renderer

Direct Filament 1.75.1 `Engine` / `UiHelper` / `SurfaceView` integration; no SceneView
or runtime shader compiler. `UbershaderProvider` supplies packaged glTF materials.
`ResourceLoader` uploads queued GLBs asynchronously, and unchanged instances retain
their native assets across camera, selection and drag updates.

The common scene owns model transforms, selection indicator geometry and camera
state. This adapter draws its preview marker from the shared valid/invalid marker
GLBs. All native access is confined to Android's main thread. Surface callbacks
recreate swap chains after backgrounding and update the orthographic aspect on resize.

Every book uses `models/book.glb`. `GeneratedBookAsset` replaces its cover and spine
images with the downloaded cover or a generated title cover before loading.

An instance can instead render off screen for list thumbnails (`ShelfSnapshotter`).
It uses a readable headless swap chain, waits for all uploads and two settle frames,
reads the frame back with `readPixels`, and processes queued requests one at a time.
It never attaches a view.

Choreographer callbacks stop after uploads finish and three successful settle frames.
State changes, activation and surface events restart callbacks. Logcat tag
`ShelfieFilament` emits cumulative submitted-frame counts at idle and destruction;
these are diagnostics, not GPU timing measurements.

Only `filament-android` and `gltfio-android` are used. API reference checked against
the official 1.75.1 AARs.
