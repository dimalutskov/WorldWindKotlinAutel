package earth.worldwind.ogc

import earth.worldwind.geom.Sector
import earth.worldwind.globe.elevation.coverage.CacheableElevationCoverage
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.globe.elevation.coverage.WebElevationCoverage
import earth.worldwind.layer.CacheableImageLayer
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.WebImageLayer
import earth.worldwind.layer.mercator.WebMercatorLayerFactory
import earth.worldwind.layer.mercator.MercatorTiledSurfaceImage
import earth.worldwind.ogc.gpkg.AbstractGeoPackage.Companion.COVERAGE
import earth.worldwind.ogc.gpkg.AbstractGeoPackage.Companion.EPSG_3857
import earth.worldwind.ogc.gpkg.AbstractGeoPackage.Companion.TILES
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.CacheTileFactory
import earth.worldwind.util.ContentManager
import earth.worldwind.util.LevelSet
import earth.worldwind.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import java.io.File

class GpkgContentManager(val pathName: String, val isReadOnly: Boolean = false): ContentManager {
    private val geoPackage by lazy { GeoPackage(pathName, isReadOnly) }

    /**
     * Returns database connection state. If true, then the Content Manager cannot be used anymore.
     */
    val isShutdown get() = geoPackage.isShutdown

    /**
     * Shutdown GPKG database connection forever for this Content Manager instance.
     */
    suspend fun shutdown() = geoPackage.shutdown()

    override suspend fun contentSize() = File(pathName).length()

    override suspend fun lastModifiedDate() = Instant.fromEpochMilliseconds(File(pathName).lastModified())

    override suspend fun getImageLayersCount() = geoPackage.content.values.count { it.dataType.equals(TILES, true) }

    override suspend fun getImageLayers(contentKeys: List<String>?) = withContext(Dispatchers.IO) {
        geoPackage.content.values
            .filter { it.dataType.equals(TILES, true) && contentKeys?.contains(it.tableName) != false }
            .mapNotNull { content ->
                // Try to build the level set. It may fail due to unsupported projection or other requirements.
                runCatching { geoPackage.buildLevelSetConfig(content) }.onFailure {
                    Logger.logMessage(Logger.WARN, "GpkgContentManager", "getImageLayers", it.message!!)
                }.getOrNull()?.let { config ->
                    // Check if WEB service config available and try to create a Web Layer
                    geoPackage.webServices[content.tableName]?.let { service ->
                        runCatching {
                            when (service.type) {
                                WmsLayerFactory.SERVICE_TYPE -> WmsLayerFactory.createLayer(
                                    service.address,
                                    service.layerName?.split(",") ?: error("Layer not specified"),
                                    service.metadata
                                )

                                WmtsLayerFactory.SERVICE_TYPE -> WmtsLayerFactory.createLayer(
                                    service.address,
                                    service.layerName ?: error("Layer not specified"),
                                    service.metadata
                                )

                                WebMercatorLayerFactory.SERVICE_TYPE -> WebMercatorLayerFactory.createLayer(
                                    content.identifier, service.address, service.outputFormat, service.isTransparent,
                                    config.numLevels, config.tileHeight
                                )

                                else -> null // It is not a known Web Layer type
                            }?.apply {
                                // Configure cache for Web Layer
                                tiledSurfaceImage?.cacheTileFactory = GpkgTileFactory(content, service.outputFormat)
                            }
                        }.onFailure {
                            Logger.logMessage(Logger.WARN, "GpkgContentManager", "getImageLayers", it.message!!)
                        }.getOrNull()
                    } ?: TiledImageLayer(content.identifier, if (content.srsId == EPSG_3857) {
                        MercatorTiledSurfaceImage(GpkgTileFactory(content), LevelSet(config))
                    } else {
                        TiledSurfaceImage(GpkgTileFactory(content), LevelSet(config))
                    }).apply {
                        // Set cache factory to be able to use cacheale layer interface
                        tiledSurfaceImage?.cacheTileFactory = tiledSurfaceImage?.tileFactory as? CacheTileFactory
                    }
                }
            }
    }

    override suspend fun setupImageLayerCache(
        layer: CacheableImageLayer, contentKey: String, boundingSector: Sector?, setupWebLayer: Boolean
    ) = withContext(Dispatchers.IO) {
        val tiledSurfaceImage = layer.tiledSurfaceImage ?: error("Surface image not defined")
        val levelSet = tiledSurfaceImage.levelSet
        val imageFormat = (layer as? WebImageLayer)?.imageFormat ?: "image/png"
        val content = geoPackage.content[contentKey]?.also {
            // Check if the current layer fits cache content
            val config = geoPackage.buildLevelSetConfig(it)
            require(config.sector.equals(levelSet.sector, TOLERANCE)) { "Invalid sector" }
            require(config.tileOrigin.equals(levelSet.tileOrigin, TOLERANCE)) { "Invalid tile origin" }
            require(config.firstLevelDelta.equals(levelSet.firstLevelDelta, TOLERANCE)) { "Invalid first level delta" }
            require(config.tileWidth == levelSet.tileWidth && config.tileHeight == levelSet.tileHeight) { "Invalid tile size" }
            require(geoPackage.tileMatrix[contentKey]?.keys?.sorted()?.get(0) == 0) { "Invalid level offset" }
            if (imageFormat.equals("image/webp", true)) requireNotNull(geoPackage.extensions.firstOrNull { e ->
                e.tableName == contentKey && e.columnName == "tile_data" && e.extensionName == "gpkg_webp"
            }) { "WEBP extension missed" }
            // Check and update web service config
            if (layer is WebImageLayer) {
                val serviceType = geoPackage.webServices[contentKey]?.type
                require(serviceType == null || serviceType == layer.serviceType) { "Invalid service type" }
                val outputFormat = geoPackage.webServices[contentKey]?.outputFormat
                require(outputFormat == null || outputFormat == layer.imageFormat) { "Invalid image format" }
                if (setupWebLayer && !geoPackage.isReadOnly) geoPackage.setupWebLayer(layer, contentKey)
            }
            // Verify if all required tile matrices created
            if (!geoPackage.isReadOnly && config.numLevels < levelSet.numLevels) geoPackage.setupTileMatrices(contentKey, levelSet)
            // Update content metadata
            geoPackage.updateTilesContent(layer, contentKey, levelSet, boundingSector)
        } ?: geoPackage.setupTilesContent(layer, contentKey, levelSet, boundingSector, setupWebLayer)

        layer.tiledSurfaceImage?.cacheTileFactory = GpkgTileFactory(content, imageFormat)
    }

    override suspend fun getElevationCoveragesCount() = geoPackage.content.values.count { it.dataType.equals(COVERAGE, true) }

    override suspend fun getElevationCoverages(contentKeys: List<String>?) = withContext(Dispatchers.IO) {
        geoPackage.content.values
            .filter { it.dataType.equals(COVERAGE, true) && contentKeys?.contains(it.tableName) != false }
            .mapNotNull { content ->
                runCatching {
                    val metadata = geoPackage.griddedCoverages[content.tableName]
                    requireNotNull(metadata) { "Missing gridded coverage metadata for '${content.tableName}'" }
                    val matrixSet = geoPackage.buildTileMatrixSet(content)
                    val factory = GpkgElevationSourceFactory(content, metadata.datatype == "float")
                    val service = geoPackage.webServices[content.tableName]
                    when (service?.type) {
                        Wcs100ElevationCoverage.SERVICE_TYPE -> Wcs100ElevationCoverage(
                            service.address,
                            service.layerName ?: error("Coverage not specified"),
                            service.outputFormat,
                            matrixSet.sector, matrixSet.maxResolution
                        ).apply { cacheSourceFactory = factory }

                        Wcs201ElevationCoverage.SERVICE_TYPE -> Wcs201ElevationCoverage(
                            service.address,
                            service.layerName ?: error("Coverage not specified"),
                            service.outputFormat,
                            service.metadata
                        ).apply { cacheSourceFactory = factory }

                        WmsElevationCoverage.SERVICE_TYPE -> WmsElevationCoverage(
                            service.address,
                            service.layerName ?: error("Coverage not specified"),
                            service.outputFormat,
                            matrixSet.sector, matrixSet.maxResolution
                        ).apply { cacheSourceFactory = factory }

                        else -> TiledElevationCoverage(matrixSet, factory).apply {
                            // Configure cache to be able to use cacheable converage interface
                            cacheSourceFactory = factory
                        }
                    }
                }.onFailure {
                    Logger.logMessage(Logger.WARN, "GpkgContentManager", "getElevationCoverages", it.message!!)
                }.getOrNull()
            }
    }

    override suspend fun setupElevationCoverageCache(
        coverage: CacheableElevationCoverage, contentKey: String, boundingSector: Sector?,
        setupWebCoverage: Boolean, isFloat: Boolean
    ) = withContext(Dispatchers.IO) {
        val content = geoPackage.content[contentKey]?.also {
            // Check if the current layer fits cache content
            val matrixSet = geoPackage.buildTileMatrixSet(it)
            require(matrixSet.sector.equals(coverage.tileMatrixSet.sector, TOLERANCE)) { "Invalid sector" }
            requireNotNull(geoPackage.griddedCoverages[contentKey]?.datatype == if (isFloat) "float" else "integer") { "Invalid data type" }
            // Check and update web service config
            if (coverage is WebElevationCoverage) {
                val serviceType = geoPackage.webServices[contentKey]?.type
                require(serviceType == null || serviceType == coverage.serviceType) { "Invalid service type" }
                if (setupWebCoverage && !geoPackage.isReadOnly) geoPackage.setupWebCoverage(coverage, contentKey)
            }
            // Verify if all required tile matrices created
            if (!geoPackage.isReadOnly && matrixSet.entries.size < coverage.tileMatrixSet.entries.size) {
                geoPackage.setupTileMatrices(contentKey, coverage.tileMatrixSet)
            }
            // Update content metadata
            geoPackage.updateGriddedCoverageContent(coverage, contentKey, boundingSector)
        } ?: geoPackage.setupGriddedCoverageContent(coverage, contentKey, boundingSector, setupWebCoverage, isFloat)

        coverage.cacheSourceFactory = GpkgElevationSourceFactory(content, isFloat)
    }

    override suspend fun deleteContent(contentKey: String) = geoPackage.deleteContent(contentKey)

    companion object {
        private const val TOLERANCE = 1e-6
    }
}