package net.monindev.shelfie.filament

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.app.Activity
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import net.monindev.shelfie.render.CameraState
import net.monindev.shelfie.render.RenderInstance
import net.monindev.shelfie.render.RenderScene
import net.monindev.shelfie.render.ShelfRenderer
import net.monindev.shelfie.render.ShelfSnapshotter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Direct Filament integration; all native objects are owned by the main thread. An instance
 * either drives one on-screen view or renders snapshots off screen, never both.
 */
class FilamentShelfRenderer : ShelfRenderer, ShelfSnapshotter, Choreographer.FrameCallback {
    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var renderView: View
    private lateinit var camera: Camera
    private lateinit var materialProvider: UbershaderProvider
    private lateinit var assetLoader: AssetLoader
    private lateinit var resourceLoader: ResourceLoader
    private lateinit var displayHelper: DisplayHelper
    private lateinit var assets: AssetManager
    private lateinit var context: android.content.Context
    private lateinit var indirectLight: IndirectLight
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var surfaceView: SurfaceView? = null
    private var swapChain: SwapChain? = null
    private var cameraEntity = 0
    private var lightEntity = 0
    private var fillEntity = 0
    private var active = false
    private var released = false
    private var posted = false
    private var framesRemaining = 0
    private var renderedFrames = 0L
    private var lastScene: RenderScene? = null
    private val models = linkedMapOf<String, Model>()
    private val pending = ArrayDeque<Model>()
    private var loading: Model? = null
    private val readyEntities = IntArray(128)
    private var headless = false
    private val captures = ArrayDeque<Capture>()
    private var reading = false

    private class Model(val path: String, val asset: FilamentAsset)
    private class Capture(val scene: RenderScene, val width: Int, val height: Int, val onResult: (Bitmap?) -> Unit)

    private fun initEngine(context: Context) {
        Filament.init()
        Gltfio.init()
        assets = context.assets
        this.context = context.applicationContext
        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        renderView = engine.createView()
        cameraEntity = EntityManager.get().create()
        camera = engine.createCamera(cameraEntity)
        camera.setExposure(16f, 1f / 125f, 100f)
        renderView.scene = scene
        renderView.camera = camera
        renderView.antiAliasing = View.AntiAliasing.FXAA
        renderView.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply {
            enabled = true
            sampleCount = 4
        }
        renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            clearColor = doubleArrayOf(0.87, 0.84, 0.77, 1.0)
        }
        materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine, true)
        createLighting()
    }

    override fun createView(activity: Activity): android.view.View {
        checkMainThread()
        check(!released && !headless)
        surfaceView?.let { return it }
        initEngine(activity)
        displayHelper = DisplayHelper(activity)
        val view = object : SurfaceView(activity) {
            override fun onTouchEvent(event: MotionEvent): Boolean = false
        }
        surfaceView = view
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface)
                view.display?.let { displayHelper.attach(renderer, it) }
                requestDraw()
            }

            override fun onDetachedFromSurface() {
                cancelFrame()
                displayHelper.detach()
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                }
                swapChain = null
            }

            override fun onResized(width: Int, height: Int) {
                renderView.viewport = Viewport(0, 0, width, height)
                lastScene?.camera?.let(::applyCamera)
                // Android may resize while the driver still holds the old buffers.
                engine.flushAndWait()
                requestDraw()
            }
        }
        uiHelper.attachTo(view)
        lastScene?.let(::update)
        return view
    }

    override fun capture(context: Context, scene: RenderScene, width: Int, height: Int, onResult: (Bitmap?) -> Unit) {
        checkMainThread()
        if (released || surfaceView != null || width <= 0 || height <= 0) { onResult(null); return }
        if (!this::engine.isInitialized) { initEngine(context); headless = true }
        captures.addLast(Capture(scene, width, height, onResult))
        if (captures.size == 1) startCapture()
    }

    private fun startCapture() {
        val next = captures.firstOrNull() ?: run { active = false; return }
        val viewport = renderView.viewport
        if (swapChain == null || viewport.width != next.width || viewport.height != next.height) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(next.width, next.height, SwapChainFlags.CONFIG_READABLE)
            renderView.viewport = Viewport(0, 0, next.width, next.height)
        }
        active = true
        update(next.scene)
    }

    /** Reads back the finished frame; Filament delivers rows top-down. */
    private fun readBack(capture: Capture) {
        reading = true
        val pixels = java.nio.ByteBuffer.allocateDirect(capture.width * capture.height * 4)
        renderer.readPixels(0, 0, capture.width, capture.height, Texture.PixelBufferDescriptor(pixels, Texture.Format.RGBA,
            Texture.Type.UBYTE, 1, 0, 0, 0, Handler(Looper.getMainLooper())) {
            reading = false
            // release() has already answered every queued request.
            if (!released) {
                captures.removeFirstOrNull()
                val bitmap = try {
                    Bitmap.createBitmap(capture.width, capture.height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(pixels.rewind()) }
                } catch (_: RuntimeException) { null }
                capture.onResult(bitmap)
                startCapture()
            }
        })
    }

    private fun createLighting() {
        // Photographic exposure uses lux-scale lights and a neutral diffuse fill.
        indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(0.72f, 0.70f, 0.66f))
            .intensity(30_000f).build(engine)
        scene.indirectLight = indirectLight
        lightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.SUN)
            .color(1f, 0.94f, 0.83f)
            .intensity(75_000f)
            .direction(-0.4f, -0.8f, -0.6f)
            .sunAngularRadius(2.5f)
            .castShadows(true)
            .build(engine, lightEntity)
        scene.addEntity(lightEntity)
        fillEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.83f, 0.90f, 1f)
            .intensity(16_000f)
            .direction(0.6f, -0.3f, 0.5f)
            .castShadows(false)
            .build(engine, fillEntity)
        scene.addEntity(fillEntity)
    }

    override fun update(scene: RenderScene) {
        checkMainThread()
        if (released) return
        lastScene = scene
        if (!this::engine.isInitialized) return
        val desired = scene.instances.toMutableList()
        scene.preview?.let { marker ->
            val transform = floatArrayOf(
                marker.width, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                marker.x, marker.y, 0.24f, 1f,
            )
            desired += RenderInstance("__filament_preview", if (marker.valid) {
                "models/marker-valid.glb"
            } else "models/marker-invalid.glb", transform)
        }
        val ids = desired.mapTo(hashSetOf()) { it.id }
        models.keys.filter { it !in ids }.forEach(::removeModel)
        desired.forEach { item ->
            val key = item.book?.let { "${item.assetPath}:$it:${net.monindev.shelfie.render.BookCovers.file(context,it)?.length()}" } ?: item.assetPath
            if (models[item.id]?.path != key) removeModel(item.id)
            val model = models[item.id] ?: loadModel(item, key)?.also {
                models[item.id] = it
                pending.addLast(it)
            }
            if (model != null) {
                require(item.transform.size == 16)
                val transforms = engine.transformManager
                transforms.setTransform(transforms.getInstance(model.asset.root), item.transform)
            }
        }
        // The shared scene supplies a separate selection marker. Cover materials
        // remain untouched, including when instances share glTF material resources.
        applyCamera(scene.camera)
        startNextLoad()
        requestDraw()
    }

    private fun loadModel(item: RenderInstance, path: String): Model? {
        return try {
            val bytes = item.book?.let {
                val cover = net.monindev.shelfie.render.BookCovers.bitmap(context, it)
                try { GeneratedBookAsset.create(assets, it, cover) } finally { cover?.recycle() }
            }
                ?: assets.open(item.assetPath.removePrefix("assets/")).use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buffer.put(bytes).flip()
            val asset = assetLoader.createAsset(buffer)
                ?: error("glTF asset creation failed: $path")
            Model(path, asset)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to load $path", error)
            null
        }
    }

    private fun startNextLoad() {
        if (loading != null || pending.isEmpty()) return
        val next = pending.removeFirst()
        loading = next
        if (!resourceLoader.asyncBeginLoad(next.asset)) {
            Log.e(TAG, "Resource loading failed: ${next.path}")
            next.asset.releaseSourceData()
            loading = null
            startNextLoad()
        }
    }

    private fun updateUploads() {
        val current = loading ?: return
        resourceLoader.asyncUpdateLoad()
        while (true) {
            val count = current.asset.popRenderables(readyEntities)
            if (count == 0) break
            scene.addEntities(readyEntities.copyOf(count))
        }
        if (resourceLoader.asyncGetLoadProgress() >= 1f) {
            current.asset.releaseSourceData()
            resourceLoader.evictResourceData()
            loading = null
            framesRemaining = maxOf(framesRemaining, 3)
            startNextLoad()
        }
        assetLoader.gc()
    }

    private fun removeModel(id: String) {
        val old = models.remove(id) ?: return
        pending.remove(old)
        if (loading === old) {
            resourceLoader.asyncCancelLoad()
            resourceLoader.evictResourceData()
            loading = null
        }
        scene.removeEntities(old.asset.entities)
        assetLoader.destroyAsset(old.asset)
    }

    private fun applyCamera(state: CameraState) {
        val viewport = renderView.viewport
        val aspect = if (viewport.height > 0 && viewport.width > 0) {
            viewport.width.toDouble() / viewport.height
        } else state.aspect.toDouble()
        val halfHeight = state.verticalHalfExtent.toDouble().coerceAtLeast(0.05)
        camera.setProjection(Camera.Projection.ORTHO, -halfHeight * aspect, halfHeight * aspect,
            -halfHeight, halfHeight, 0.05, 100.0)
        camera.lookAt(state.eye[0].toDouble(), state.eye[1].toDouble(), state.eye[2].toDouble(),
            state.target[0].toDouble(), state.target[1].toDouble(), state.target[2].toDouble(),
            0.0, 1.0, 0.0)
    }

    override fun setActive(active: Boolean) {
        checkMainThread()
        if (released || headless) return
        if (this.active == active) return
        this.active = active
        if (active) requestDraw() else cancelFrame()
    }

    private fun requestDraw() {
        framesRemaining = maxOf(framesRemaining, 3)
        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (!released && active && swapChain != null && !posted && !reading) {
            posted = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun cancelFrame() {
        if (posted) Choreographer.getInstance().removeFrameCallback(this)
        posted = false
    }

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        if (!active || released || reading || (!headless && !uiHelper.isReadyToRender)) return
        val chain = swapChain ?: return
        updateUploads()
        // A snapshot is taken from the last frame after every model and texture has loaded.
        val capture = captures.firstOrNull()?.takeIf { headless && loading == null && pending.isEmpty() && framesRemaining <= 1 }
        if (renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(renderView)
            if (capture != null) readBack(capture)
            renderer.endFrame()
            renderedFrames++
            framesRemaining--
            // Read-back callbacks are dispatched once the driver has executed and purged the frame.
            if (capture != null) { engine.flushAndWait(); return }
        }
        if (loading != null || pending.isNotEmpty() || framesRemaining > 0 || (headless && captures.isNotEmpty())) scheduleFrame()
        else Log.d(TAG, "idle frames=$renderedFrames uptimeNs=${System.nanoTime()} models=${models.size}")
    }

    override fun release() {
        checkMainThread()
        if (released) return
        active = false
        released = true
        cancelFrame()
        captures.toList().forEach { it.onResult(null) }
        captures.clear()
        if (!this::engine.isInitialized) return
        if (headless) swapChain?.let { engine.destroySwapChain(it) } else uiHelper.detach()
        swapChain = null
        resourceLoader.asyncCancelLoad()
        resourceLoader.evictResourceData()
        loading = null
        pending.clear()
        models.values.forEach {
            scene.removeEntities(it.asset.entities)
            assetLoader.destroyAsset(it.asset)
        }
        models.clear()
        resourceLoader.destroy()
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
        engine.destroyEntity(lightEntity)
        engine.destroyEntity(fillEntity)
        EntityManager.get().destroy(lightEntity)
        EntityManager.get().destroy(fillEntity)
        engine.destroyIndirectLight(indirectLight)
        engine.destroyView(renderView)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroyRenderer(renderer)
        engine.destroy()
        surfaceView = null
        lastScene = null
        Log.d(TAG, "released frames=$renderedFrames")
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "Filament renderer must be accessed on Android's main thread"
    }

    private companion object { const val TAG = "ShelfieFilament" }
}
