package ai.opencode.feature.media

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Plain JVM unit tests run against the `android.jar` stubs, where `Bitmap` can't be made or
 * decoded. Reducers and stores that only carry an image (by identity) get a [stubBitmap]: a
 * distinct instance whose methods all return stub defaults. Tests that need the framework (real
 * pixels, `rowBytes`, PNG output, `Patterns`, `LruCache`, the main `Looper`) run under
 * Robolectric with native graphics in their own classes; see the `## Tests` notes in
 * docs/parity/media.md.
 */
internal fun stubBitmap(): Bitmap = unsafe.allocateInstance(Bitmap::class.java) as Bitmap

private val unsafe: sun.misc.Unsafe by lazy {
    val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
    field.isAccessible = true
    field.get(null) as sun.misc.Unsafe
}

/** A value effects and closures update from any thread. */
internal class Locked<T>(initial: T) {
    @Volatile private var value: T = initial
    val current: T get() = synchronized(this) { value }
    fun set(newValue: T) = synchronized(this) { value = newValue }
    fun <R> withValue(block: (T) -> Pair<T, R>): R = synchronized(this) {
        val (next, result) = block(value)
        value = next
        result
    }
    fun update(block: (T) -> T) = synchronized(this) { value = block(value) }
}

/**
 * A [SiteIcon] without decoding (its constructor is private and decoding needs real Bitmaps):
 * icons compare by their PNG bytes, so [png] names it.
 */
internal fun stubSiteIcon(png: String, tone: SiteIcon.Tone? = null): SiteIcon {
    val constructor = SiteIcon::class.java.declaredConstructors.first { it.parameterCount == 3 }
    constructor.isAccessible = true
    return constructor.newInstance(stubBitmap(), png.toByteArray(), tone) as SiteIcon
}

/** A fresh directory under the JVM's temp folder. */
internal fun temporaryDirectory(prefix: String): java.io.File =
    java.io.File(System.getProperty("java.io.tmpdir"), "$prefix-${java.util.UUID.randomUUID()}")

/** Runs [block] with `Dispatchers.Main` on the test's scheduler, eagerly. */
internal suspend fun TestScope.onMain(block: suspend () -> Unit) {
    Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    try {
        block()
    } finally {
        Dispatchers.resetMain()
    }
}
