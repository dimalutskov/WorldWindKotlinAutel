package earth.worldwind.examples.autel

import android.graphics.PixelFormat
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.autel.drone.sdk.SDKConstants
import com.autel.player.player.AutelPlayerManager
import earth.worldwind.WorldWindow
import earth.worldwind.examples.R
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.Layer
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.mercator.WebMercatorLayerFactory
import earth.worldwind.layer.starfield.StarFieldLayer
import earth.worldwind.ogc.GpkgContentManager
import earth.worldwind.render.Color
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.util.kgl.TranslucentEGLConfigChooser
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

class AutelGlobeActivity : AppCompatActivity() {

    /**
     * The WorldWindow (GLSurfaceView) maintained by this activity
     */
    private val worldWindows = mutableListOf<WorldWindow>()

    private val roadsLayers = mutableListOf<Layer>()
    private val polygonesLayers = mutableListOf<Layer>()

    private val initLat = 41.889761
    private val initLon = 12.486086
    private val initAlt = 100.0

    private var lookAt = LookAt(
        position = Position(
            initLat.degrees,
            initLon.degrees,
            initAlt,
        ),
        altitudeMode = AltitudeMode.ABSOLUTE,
        range = 10.0,
        heading = 0.0.degrees,
        tilt = 45.0.degrees,
        roll = 0.0.degrees
    )

    private val polygons = generateRandomPolygons()

    private lateinit var autelSurface: AutelPlayerSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.globe_autel)

        autelSurface = findViewById(R.id.autel_surface)

        findViewById<CheckBox>(R.id.checkbox_map).setOnCheckedChangeListener { _, isChecked ->
            findViewById<View>(R.id.globe_two).visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        findViewById<CheckBox>(R.id.checkbox_roads).setOnCheckedChangeListener { _, isChecked ->
            roadsLayers.forEach { it.isEnabled = isChecked }
            worldWindows.forEach { it.requestRedraw() }
        }
        findViewById<CheckBox>(R.id.checkbox_polygones).setOnCheckedChangeListener { _, isChecked ->
            polygonesLayers.forEach { it.isEnabled = isChecked }
            worldWindows.forEach { it.requestRedraw() }
        }

        // Add a WorldWindow to each of the FrameLayouts in the multi-globe layout.
        val globe1 = findViewById<FrameLayout>(R.id.globe_one)
        val globe2 = findViewById<FrameLayout>(R.id.globe_two)
        globe1.addView(
            if (getWorldWindow(0) == null) createWorldWindow(false) else getWorldWindow(0),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        globe2.addView(
            if (getWorldWindow(1) == null) createWorldWindow(true) else getWorldWindow(1),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        updateLookAt()

        AutelHelper.gimbalAttitude.onEach {
            lookAt = LookAt(
                position = lookAt.position,
                altitudeMode = lookAt.altitudeMode,
                range = lookAt.range,
                heading = it.yaw.degrees,
                tilt = (it.pitch + 90).degrees,
                roll = (-1 * it.roll).degrees
            )
            updateLookAt()
        }.launchIn(lifecycleScope)

        findViewById<CheckBox>(R.id.checkbox_roads).isChecked = false
        findViewById<CheckBox>(R.id.checkbox_polygones).isChecked = false
    }

    private fun updateLookAt() {
        worldWindows.forEach {
            it.engine.cameraFromLookAt(lookAt)
            it.requestRedraw()
        }
    }

    private fun getWorldWindow(index: Int): WorldWindow? {
        return if (index !in worldWindows.indices) null else worldWindows[index]
    }

    private fun createWorldWindow(isFullMap: Boolean): WorldWindow {
        val contentManager = GpkgContentManager(File(cacheDir, "cache_content.gpkg").absolutePath)
        // Create the WorldWindow (a GLSurfaceView) which displays the globe.
        val wwd = WorldWindow(this, TranslucentEGLConfigChooser()).apply {
            holder.setFormat(PixelFormat.TRANSLUCENT)
        }
        // Setting up the WorldWindow's layers.
        wwd.engine.layers.apply {
            if (isFullMap) {
                addLayer(BackgroundLayer())
                addLayer(
                    WebMercatorLayerFactory.createLayer(
                        urlTemplate = "https://mt.google.com/vt/lyrs=s&x={x}&y={y}&z={z}&hl={lang}",
                        imageFormat = "image/jpeg",
                        name = "Google Satellite"
                    ).apply {
                        wwd.mainScope.launch { configureCache(contentManager, "GSat") }
                    })
            }

            // Road Maps
            val roadsLayer = WebMercatorLayerFactory.createLayer(
                urlTemplate = "https://mt.google.com/vt/lyrs=h&x={x}&y={y}&z={z}&hl={lang}",
                imageFormat = "PNG",
                name = "Google Roads"
            ).apply {
                wwd.mainScope.launch { configureCache(contentManager, "GRoads") }
            }
            addLayer(roadsLayer)
            roadsLayers.add(roadsLayer)

            val customLayer = RenderableLayer("Polygons").apply {
                addAllRenderables(polygons)
            }
            addLayer(customLayer)
            polygonesLayers.add(customLayer)

            if (isFullMap) {
                addLayer(StarFieldLayer())
                addLayer(AtmosphereLayer())
            }
        }
        worldWindows.add(wwd)
        return wwd
    }

    private fun generateRandomPolygons(): List<Polygon> {
        val polygons = mutableListOf<Polygon>()

        repeat(10) {
            val corners = Random.nextInt(3, 7) // Random number of corners (3 to 6)
            val positions = mutableListOf<Position>()

            // Generate random positions within the radius
            repeat(corners) {
                val latOffset = Random.nextDouble(-0.001, 0.001)
                val lonOffset = Random.nextDouble(-0.001, 0.001)
                positions.add(Position((initLat + latOffset).degrees, (initLon + lonOffset).degrees, 10.0))
            }

            // Generate random color with alpha between 0.2 and 0.7
            val alpha = Random.nextFloat() * (0.7f - 0.2f) + 0.2f
            val randomColor = Color(
                Random.nextFloat(), // Red
                Random.nextFloat(), // Green
                Random.nextFloat(), // Blue
                alpha
            )

            val attributes = ShapeAttributes().apply {
                interiorColor = randomColor
            }

            polygons.add(Polygon(positions, attributes))
        }

        return polygons
    }

    override fun onStart() {
        super.onStart()

        AutelPlayerManager.getInstance().init(this, false)
        AutelPlayerManager.getInstance().addStreamChannel(SDKConstants.getZoomChancelId(), true)

        autelSurface.onStart()

        AutelHelper.test(lifecycleScope)

        for (wwd in worldWindows) wwd.onResume() // resumes a paused rendering thread
    }

    override fun onStop() {
        super.onStop()

        AutelPlayerManager.getInstance().getAutelPlayer(SDKConstants.getZoomChancelId())?.also {
            AutelPlayerManager.getInstance().removeAutelPlayer(it)
        }
        AutelPlayerManager.getInstance().destory()
        AutelPlayerManager.getInstance().release()

        autelSurface.onStop()

        for (wwd in worldWindows) wwd.onPause() // pauses the rendering thread
    }

}