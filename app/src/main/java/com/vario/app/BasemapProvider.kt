package com.vario.app

import android.content.Context
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * Modular interface for map tile providers.
 * Allows seamless addition of new online and offline basemaps.
 */
interface BasemapProvider {
    val id: String
    val displayName: String
    val description: String
    val isTopographic: Boolean
    fun createTileSource(): ITileSource
}

/**
 * OpenTopoMap provider: High-contrast topographic relief, contours (20m/50m),
 * mountain shading, summits, and cols. 100% free and open-source (CC-BY-SA).
 */
class OpenTopoMapProvider : BasemapProvider {
    override val id: String = "opentopomap"
    override val displayName: String = "OpenTopoMap (Relief & Courbes)"
    override val description: String = "Ombrage de relief SRTM, courbes de niveau et sommets"
    override val isTopographic: Boolean = true

    override fun createTileSource(): ITileSource {
        return XYTileSource(
            "OpenTopoMap",
            1,
            17,
            256,
            ".png",
            arrayOf(
                "https://a.tile.opentopomap.org/",
                "https://b.tile.opentopomap.org/",
                "https://c.tile.opentopomap.org/"
            ),
            "© OpenTopoMap (CC-BY-SA), OpenStreetMap contributors"
        )
    }
}

/**
 * CyclOSM / Outdoor provider: Mountain passes, elevation curves, outdoor trails and relief.
 */
class CyclOsmProvider : BasemapProvider {
    override val id: String = "cyclosm"
    override val displayName: String = "CyclOSM Outdoor (Plein Air)"
    override val description: String = "Relief accentué, cols et chemins de montagne"
    override val isTopographic: Boolean = true

    override fun createTileSource(): ITileSource {
        return XYTileSource(
            "CyclOSM",
            0,
            18,
            256,
            ".png",
            arrayOf(
                "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/",
                "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/",
                "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/"
            ),
            "© CyclOSM, OpenStreetMap contributors"
        )
    }
}

/**
 * Standard OpenStreetMap (Mapnik) provider.
 */
class OsmStandardProvider : BasemapProvider {
    override val id: String = "osm_standard"
    override val displayName: String = "OpenStreetMap Standard"
    override val description: String = "Cartographie générale OpenStreetMap"
    override val isTopographic: Boolean = false

    override fun createTileSource(): ITileSource {
        return TileSourceFactory.MAPNIK
    }
}

/**
 * USGS Topographic relief basemap.
 */
class UsgsTopoProvider : BasemapProvider {
    override val id: String = "usgs_topo"
    override val displayName: String = "USGS Topo Relief"
    override val description: String = "Relief topographique USGS officiel"
    override val isTopographic: Boolean = true

    override fun createTileSource(): ITileSource {
        return XYTileSource(
            "USGSTopo",
            0,
            16,
            256,
            "",
            arrayOf(
                "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/"
            ),
            "USGS - The National Map"
        )
    }
}

/**
 * Central registry and persistence manager for modular basemap providers.
 */
object BasemapRegistry {
    private const val PREFS_NAME = "vario_basemap_prefs"
    private const val KEY_ACTIVE_PROVIDER = "active_basemap_provider_id"

    private val providers = mutableMapOf<String, BasemapProvider>()

    init {
        register(OpenTopoMapProvider())
        register(CyclOsmProvider())
        register(OsmStandardProvider())
        register(UsgsTopoProvider())
    }

    /**
     * Registers a new basemap provider.
     */
    fun register(provider: BasemapProvider) {
        providers[provider.id] = provider
    }

    /**
     * Gets all registered basemap providers.
     */
    fun getAvailableProviders(): List<BasemapProvider> = providers.values.toList()

    /**
     * Retrieves the currently selected basemap provider.
     */
    fun getActiveProvider(context: Context): BasemapProvider {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeId = prefs.getString(KEY_ACTIVE_PROVIDER, "opentopomap") ?: "opentopomap"
        return providers[activeId] ?: providers["opentopomap"] ?: OsmStandardProvider()
    }

    /**
     * Updates the selected basemap provider and persists it.
     */
    fun setActiveProvider(context: Context, providerId: String) {
        if (providers.containsKey(providerId)) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_ACTIVE_PROVIDER, providerId).apply()
        }
    }
}
