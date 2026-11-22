package ai.opencode.feature.system.notifications

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This phone's P-256 key and the derived key for each notification ref. Unlike the keychain shared
 * keys, every unavailable read and failed write is an error: enrollment must not send a public key
 * whose private key wasn't saved.
 *
 * Android has no extension (FCM delivers to the app process), so [Storage.live] seals each record
 * with a non-exportable Android Keystore AES key into `noBackupFilesDir/notification-keys/`, which
 * never leaves the device or reaches a backup. The app is the only writer, so the
 * [NotificationKeyCache] stays true.
 */
class NotificationKeyStore(
    private val storage: Storage,
    private val cache: NotificationKeyCache? = null,
) {
    sealed class Failure(message: String) : Exception(message) {
        data class Keychain(val reason: String) : Failure("keychain $reason")
        data object MissingKey : Failure("missing key") { private fun readResolve(): Any = MissingKey }
        data object InvalidKey : Failure("invalid key") { private fun readResolve(): Any = InvalidKey }
    }

    /** Where records live. Every method throws [Failure.Keychain] when the store is unavailable. */
    interface Storage {
        fun read(ref: String): ByteArray?

        /** Writes a new record; `false` when one already exists. */
        fun add(ref: String, data: ByteArray): Boolean

        /** Replaces an existing record. */
        fun update(ref: String, data: ByteArray)

        /** Removes a record; a missing one is fine. */
        fun delete(ref: String)

        companion object {
            val live: Storage by lazy { KeystoreStorage(File(dependency(AppContext).applicationContext.noBackupFilesDir, "notification-keys")) }

            /** In memory, for JVM tests (the Android Keystore needs a device). */
            fun inMemory(): Storage = InMemoryStorage()
        }
    }

    @Serializable
    private data class Record(val privateKey: String, val publicKey: String, val sharedKey: String? = null)

    /** Returns the same device public key on retries, including after app restarts. */
    fun prepare(ref: String): String {
        NotificationCrypto.validate(ref)
        read(ref)?.let { return it.publicKey }
        // After the write: a read that overlapped it is dropped, not kept.
        try {
            val (privateKey, publicKey) = NotificationCrypto.generateKeyPair()
            val record = Record(privateKey = encode(privateKey), publicKey = encode(publicKey))
            // A concurrent enrollment may have won the insert. Read its key, not ours.
            storage.add(ref, json.encodeToString(Record.serializer(), record).toByteArray())
            val stored = read(ref) ?: throw Failure.MissingKey
            return stored.publicKey
        } finally {
            cache?.invalidate(ref)
        }
    }

    /** Call only with the public key returned by the authenticated server RPC. */
    fun establish(ref: String, serverPublicKey: String) {
        NotificationCrypto.validate(ref)
        try {
            val record = read(ref) ?: throw Failure.MissingKey
            val key = NotificationCrypto.sharedKey(
                privateKey = NotificationCrypto.privateKey(decode(record.privateKey)),
                publicKey = serverPublicKey,
                ref = ref,
            )
            val updated = record.copy(sharedKey = encode(key))
            storage.update(ref, json.encodeToString(Record.serializer(), updated).toByteArray())
            // Fail closed if persistence was lost; the caller must not cache a callback.
            if (read(ref)?.sharedKey != updated.sharedKey) throw Failure.InvalidKey
        } finally {
            cache?.invalidate(ref)
        }
    }

    fun sharedKey(ref: String): ByteArray {
        NotificationCrypto.validate(ref)
        val load = {
            val data = read(ref)?.sharedKey?.let(::decode) ?: throw Failure.MissingKey
            if (data.size != 32) throw Failure.InvalidKey
            data
        }
        return cache?.key(ref, load) ?: load()
    }

    fun remove(ref: String) {
        NotificationCrypto.validate(ref)
        try {
            storage.delete(ref)
        } finally {
            cache?.invalidate(ref)
        }
    }

    private fun read(ref: String): Record? {
        val data = storage.read(ref) ?: return null
        return runCatching { json.decodeFromString(Record.serializer(), data.decodeToString()) }
            .getOrElse { throw Failure.InvalidKey }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        private fun decode(text: String): ByteArray = runCatching { Base64.getDecoder().decode(text) }.getOrElse { throw Failure.InvalidKey }

        /** The app's store, with its cache. */
        val live: NotificationKeyStore by lazy { NotificationKeyStore(Storage.live, NotificationKeyCache()) }
    }
}

/**
 * Each read unseals a file, so a derived key is read once per ref. A read that overlaps an
 * invalidation isn't kept: it may hold the key that was just replaced or removed.
 */
class NotificationKeyCache {
    private val lock = Any()
    private val keys = HashMap<String, ByteArray>()
    private var generation = 0L

    fun key(ref: String, load: () -> ByteArray): ByteArray {
        val (cached, seen) = synchronized(lock) { keys[ref] to generation }
        if (cached != null) return cached
        val key = load()
        synchronized(lock) { if (generation == seen) keys[ref] = key }
        return key
    }

    fun invalidate(ref: String) {
        synchronized(lock) {
            keys.remove(ref)
            generation += 1
        }
    }
}

private class InMemoryStorage : NotificationKeyStore.Storage {
    private val records = HashMap<String, ByteArray>()

    @Synchronized override fun read(ref: String): ByteArray? = records[ref]?.copyOf()

    @Synchronized override fun add(ref: String, data: ByteArray): Boolean {
        if (ref in records) return false
        records[ref] = data.copyOf()
        return true
    }

    @Synchronized override fun update(ref: String, data: ByteArray) {
        if (ref !in records) throw NotificationKeyStore.Failure.Keychain("not found")
        records[ref] = data.copyOf()
    }

    @Synchronized override fun delete(ref: String) {
        records.remove(ref)
    }
}

/** AES-GCM sealing with a Keystore key that never leaves secure hardware, one file per ref. */
private class KeystoreStorage(private val directory: File) : NotificationKeyStore.Storage {
    private val lock = Any()

    private fun file(ref: String) = File(directory, "$ref.bin")

    override fun read(ref: String): ByteArray? = synchronized(lock) {
        val file = file(ref)
        if (!file.exists()) return null
        guarded {
            val sealed = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }
    }

    override fun add(ref: String, data: ByteArray): Boolean = synchronized(lock) {
        if (file(ref).exists()) return false
        write(ref, data)
        true
    }

    override fun update(ref: String, data: ByteArray) = synchronized(lock) {
        if (!file(ref).exists()) throw NotificationKeyStore.Failure.Keychain("not found")
        write(ref, data)
    }

    override fun delete(ref: String) = synchronized(lock) {
        val file = file(ref)
        if (file.exists() && !file.delete()) throw NotificationKeyStore.Failure.Keychain("delete")
    }

    private fun write(ref: String, data: ByteArray) = guarded {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(data)
        directory.mkdirs()
        val target = file(ref)
        val temp = File(directory, "${target.name}.tmp")
        temp.writeBytes(sealed)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw NotificationKeyStore.Failure.Keychain("write")
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (failure: NotificationKeyStore.Failure) {
        throw failure
    } catch (error: Exception) {
        throw NotificationKeyStore.Failure.Keychain(error::class.java.simpleName)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ALIAS = "opencode-notification-keys-v2"
        private const val IV_BYTES = 12
    }
}
