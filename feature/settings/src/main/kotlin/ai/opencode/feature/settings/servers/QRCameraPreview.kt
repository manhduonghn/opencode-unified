package ai.opencode.feature.settings.servers

import android.content.Context
import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException

/**
 * The back camera's live picture, reading QR codes from it. Bind it only once camera access is
 * granted.
 *
 * @param isRunning the session runs only while true (and while the screen is started).
 * @param once report one code per appearance (the pairing scanner: a QR in frame fires repeatedly);
 *   false reports every decoded frame (the connect page and the onboarding playground).
 * @param onScanned a code's text, on the main thread. Never log it: it may be a pairing code.
 */
@Composable
fun QRCameraPreview(
    isRunning: Boolean,
    modifier: Modifier = Modifier,
    once: Boolean = false,
    onScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnScanned by rememberUpdatedState(onScanned)
    val session = remember { QRCameraSession(once) { latestOnScanned(it) } }
    DisposableEffect(session) { onDispose { session.close() } }
    LaunchedEffect(session, isRunning, lifecycleOwner) {
        if (isRunning) session.start(context, lifecycleOwner) else session.stop()
    }
    Box(modifier.background(Color.Black).clearAndSetSemantics {}) {
        session.surfaceRequest?.let { request ->
            CameraXViewfinder(surfaceRequest = request, contentScale = ContentScale.Crop)
        }
    }
}

/**
 * One capture session (plain object, not a reducer): CameraX use cases bound to the screen's
 * lifecycle, frames decoded off the main thread, codes delivered on it.
 */
private class QRCameraSession(private val once: Boolean, private val onScanned: (String) -> Unit) {
    var surfaceRequest: SurfaceRequest? by mutableStateOf(null)
        private set

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var hasScanned = false
    private var running = false

    suspend fun start(context: Context, owner: LifecycleOwner) {
        if (running) return
        running = true
        val provider = try {
            ProcessCameraProvider.awaitInstance(context).also { provider = it }
        } catch (cancelled: CancellationException) {
            running = false
            throw cancelled
        }
        if (!running) return
        if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) && !provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) return
        val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val preview = Preview.Builder().build().also { it.setSurfaceProvider { request -> surfaceRequest = request } }
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val decoder = QRDecoder()
        val analysis = ImageAnalysis.Builder()
            // CameraX's default 640x480 analysis only reads codes up close. 1080 rows, 4:3 like the
            // preview.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1440, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { image ->
                    val text = image.use(decoder::decode) ?: return@setAnalyzer
                    mainExecutor.execute { deliver(text) }
                }
            }
        this.preview = preview
        this.analysis = analysis
        runCatching { provider.bindToLifecycle(owner, selector, preview, analysis) }
            .onFailure { stop() }
    }

    private fun deliver(text: String) {
        if (!running) return
        if (once && hasScanned) return
        hasScanned = true
        onScanned(text)
    }

    fun stop() {
        running = false
        val provider = provider ?: return
        listOfNotNull(preview, analysis).let { if (it.isNotEmpty()) provider.unbind(*it.toTypedArray()) }
        analysis?.clearAnalyzer()
        preview = null
        analysis = null
        surfaceRequest = null
    }

    fun close() {
        stop()
        analysisExecutor.shutdown()
    }
}

/**
 * ZXing over a frame's luminance plane: QR codes only, in either polarity (Desktop draws its
 * pairing code light-on-dark in dark mode). `ALSO_INVERTED` blackens a dark page's quiet zone and
 * misses small codes, so a frame with no code is read again with its luminance inverted before
 * binarizing.
 */
private class QRDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.ALSO_INVERTED to true,
            ),
        )
    }
    private val invertedReader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    private var buffer = ByteArray(0)

    fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val rowStride = plane.rowStride
        val size = rowStride * image.height
        if (buffer.size != size) buffer = ByteArray(size)
        val source = plane.buffer.duplicate().apply { rewind() }
        source.get(buffer, 0, minOf(size, source.remaining()))
        val luminance = PlanarYUVLuminanceSource(buffer, rowStride, image.height, 0, 0, image.width, image.height, false)
        return read(reader, luminance) ?: read(invertedReader, luminance.invert())
    }

    private fun read(reader: MultiFormatReader, luminance: LuminanceSource): String? {
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(luminance))).text
        } catch (_: NotFoundException) {
            null
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
