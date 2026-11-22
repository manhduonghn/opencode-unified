package ai.opencode.feature.composer

import ai.opencode.core.log.AppLog
import android.content.Context
import androidx.core.graphics.createBitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Still maps of a shared location, rendered once per place, size and appearance and cached.
 * Composed from OpenStreetMap raster tiles over a 600 m region; dark
 * snapshots are inverted and hue-turned tiles. Tile policy
 * (operations.osmfoundation.org/policies/tiles): a User-Agent naming the app, HTTP caching
 * honoured, a handful of tiles per card, and the card credits “© OpenStreetMap contributors”. Never
 * log coordinates or tile URLs.
 */
internal class SharedLocationSnapshots(private val context: Context) {
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private val client: OkHttpClient by lazy {
        val agent = userAgent(context)
        OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "osm-tiles"), DISK_BYTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", agent).build()) }
            .build()
    }

    private fun key(location: SharedLocation, size: IntSize, scale: Float, dark: Boolean): String =
        "${location.coordinateText}|${size.width}x${size.height}@$scale|$dark"

    fun cachedImage(location: SharedLocation, size: IntSize, scale: Float, dark: Boolean): ImageBitmap? {
        if (size.width <= 0 || size.height <= 0) return null
        return memory.get(key(location, size, scale, dark))
    }

    suspend fun image(location: SharedLocation, size: IntSize, scale: Float, dark: Boolean): ImageBitmap? {
        if (size.width <= 0 || size.height <= 0) return null
        val cacheKey = key(location, size, scale, dark)
        memory.get(cacheKey)?.let { return it }
        val rendered = render(location, size, scale, dark) ?: return null
        memory.put(cacheKey, rendered)
        return rendered
    }

    private suspend fun render(location: SharedLocation, size: IntSize, scale: Float, dark: Boolean): ImageBitmap? {
        val started = System.nanoTime()
        val plan = SnapshotPlan.make(location.latitude, location.longitude, size, max(scale, 1f)) ?: return null
        if (plan.tiles.size > MAX_TILES) {
            AppLog.network.notice { "Map snapshot skipped: ${plan.tiles.size} tiles" }
            return null
        }
        val tiles = coroutineScope {
            plan.tiles.map { tile -> async(Dispatchers.IO) { tile to fetch(tile) } }.awaitAll()
        }
        if (tiles.any { it.second == null }) {
            AppLog.network.notice { "Map snapshot incomplete: ${tiles.count { it.second == null }} of ${tiles.size} tiles failed" }
            return null
        }
        val image = withContext(Dispatchers.Default) {
            val bitmap = createBitmap(size.width, size.height)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (dark) DARK_WATER else LIGHT_LAND)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            if (dark) paint.colorFilter = ColorMatrixColorFilter(darkMatrix)
            val matrix = Matrix()
            for ((tile, data) in tiles) {
                val decoded = BitmapFactory.decodeByteArray(data, 0, data!!.size) ?: continue
                val left = (tile.worldX * TILE_SIZE - plan.centerX) * plan.pixelsPerTilePixel + size.width / 2.0
                val top = (tile.y * TILE_SIZE - plan.centerY) * plan.pixelsPerTilePixel + size.height / 2.0
                val k = (plan.pixelsPerTilePixel * TILE_SIZE / decoded.width).toFloat()
                matrix.reset()
                matrix.postScale(k, k)
                matrix.postTranslate(left.toFloat(), top.toFloat())
                canvas.drawBitmap(decoded, matrix, paint)
                decoded.recycle()
            }
            bitmap.asImageBitmap()
        }
        AppLog.network.debug { "Map snapshot: ${tiles.size} tiles, zoom ${plan.zoom}, ${(System.nanoTime() - started) / 1_000_000} ms" }
        return image
    }

    /** One tile's PNG, through the HTTP cache; null on any failure. Cancelling the caller cancels the call. */
    private suspend fun fetch(tile: SnapshotPlan.Tile): ByteArray? {
        val request = Request.Builder().url("https://tile.openstreetmap.org/${tile.zoom}/${tile.x}/${tile.y}.png").build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) AppLog.network.notice { "Map tile failed: ${e::class.java.simpleName}" }
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val bytes = response.use { if (it.isSuccessful) runCatching { it.body.bytes() }.getOrNull() else null }
                    if (!response.isSuccessful) AppLog.network.notice { "Map tile failed: HTTP ${response.code}" }
                    if (continuation.isActive) continuation.resume(bytes)
                }
            })
        }
    }

    companion object {
        const val TILE_SIZE = 256
        const val REGION_METERS = 600.0
        const val MAX_ZOOM = 19
        private const val MAX_TILES = 16
        private const val MEMORY_BYTES = 8 * 1024 * 1024
        private const val DISK_BYTES = 24L * 1024 * 1024
        private const val LIGHT_LAND = 0xFFF2EFE9.toInt()
        private const val DARK_WATER = 0xFF1C2127.toInt()

        /** “OpenCode/1.2 (Android; ai.opencode)”: the OSM tile policy wants the app named. */
        private fun userAgent(context: Context): String {
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1"
            return "OpenCode/$version (Android; ${context.packageName})"
        }

        /** Inverted, then hue-turned 180° so water stays blue and parks green, and dimmed a little. */
        private val darkMatrix: ColorMatrix by lazy {
            val invert = ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
            invert.postConcat(hueRotation(180.0))
            invert.postConcat(ColorMatrix().apply { setScale(0.88f, 0.88f, 0.9f, 1f) })
            invert
        }

        /** The CSS `hue-rotate()` matrix. */
        private fun hueRotation(degrees: Double): ColorMatrix {
            val c = cos(degrees * PI / 180).toFloat()
            val s = sin(degrees * PI / 180).toFloat()
            return ColorMatrix(
                floatArrayOf(
                    0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0f, 0f,
                    0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0f, 0f,
                    0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
        }
    }
}

/**
 * Which tiles cover a snapshot and how they're scaled (pure Web Mercator maths). The region is
 * fitted into the size (its shorter side spans [SharedLocationSnapshots.REGION_METERS]), at a
 * fractional zoom; tiles come from the nearest whole zoom and are scaled to it.
 */
internal data class SnapshotPlan(
    val zoom: Int,
    /** The spot in tile pixels at [zoom]. */
    val centerX: Double,
    val centerY: Double,
    /** Output pixels per tile pixel. */
    val pixelsPerTilePixel: Double,
    val tiles: List<Tile>,
) {
    /** [worldX] is the unwrapped column (for placement); [x] the wrapped one (for the URL). */
    data class Tile(val zoom: Int, val x: Int, val y: Int, val worldX: Int)

    companion object {
        private const val EARTH_CIRCUMFERENCE = 40_075_016.686
        private const val MAX_LATITUDE = 85.05112878

        fun make(latitude: Double, longitude: Double, size: IntSize, scale: Float): SnapshotPlan? {
            if (size.width <= 0 || size.height <= 0 || !latitude.isFinite() || !longitude.isFinite()) return null
            val lat = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
            val tileSize = SharedLocationSnapshots.TILE_SIZE
            // Points, as MapKit lays the region out; the whole region shows.
            val shorterPoints = min(size.width, size.height) / scale.toDouble()
            val metersPerPoint = SharedLocationSnapshots.REGION_METERS / shorterPoints
            val latRadians = lat * PI / 180
            val metersPerTilePixelAtZero = EARTH_CIRCUMFERENCE * cos(latRadians) / tileSize
            val fractionalZoom = log2(metersPerTilePixelAtZero / metersPerPoint)
            val zoom = fractionalZoom.roundToInt().coerceIn(0, SharedLocationSnapshots.MAX_ZOOM)
            val worldTiles = 2.0.pow(zoom)
            val centerX = (longitude + 180) / 360 * worldTiles * tileSize
            val centerY = (1 - ln(tan(latRadians) + 1 / cos(latRadians)) / PI) / 2 * worldTiles * tileSize
            val pixelsPerTilePixel = scale * 2.0.pow(fractionalZoom - zoom)
            val halfWidth = size.width / 2.0 / pixelsPerTilePixel
            val halfHeight = size.height / 2.0 / pixelsPerTilePixel
            val count = worldTiles.toInt()
            val tiles = ArrayList<Tile>()
            val minY = max(floor((centerY - halfHeight) / tileSize).toInt(), 0)
            val maxY = min(floor((centerY + halfHeight) / tileSize).toInt(), count - 1)
            for (y in minY..maxY) {
                for (worldX in floor((centerX - halfWidth) / tileSize).toInt()..floor((centerX + halfWidth) / tileSize).toInt()) {
                    tiles += Tile(zoom, Math.floorMod(worldX, count), y, worldX)
                }
            }
            return SnapshotPlan(zoom, centerX, centerY, pixelsPerTilePixel, tiles)
        }
    }
}
