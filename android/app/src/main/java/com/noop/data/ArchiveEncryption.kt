package com.noop.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer

/**
 * Authenticated encryption for opt-in monthly Google Drive archives and encrypted
 * full-recovery backups. Legacy automatic backup files can still be unencrypted.
 *
 * Format v1 (big-endian):
 *   ASCII "THOOPENC" (8), version (1), PBKDF2 iterations (4), salt (16), nonce prefix (8),
 *   followed by authenticated frames: ciphertext length (4), final flag (1), AES-GCM payload.
 * An empty, authenticated final frame is required, so truncation is always detected.
 *
 * PBKDF2-HMAC-SHA256 derives an AES-256 key from a user-held recovery passphrase. Frame
 * nonces are the 8-byte random prefix followed by a 32-bit frame counter. Each frame AAD
 * covers the header, counter, frame length, and final flag; reordering and tampering fail.
 * Only 64 KiB of plaintext is processed at once, including during decryption. Decryption
 * writes to a sibling temporary file and publishes it only after all tags are verified.
 *
 * IMPORTANT: The calling feature must keep the passphrase out of logs, cloud metadata and
 * ordinary preferences; a future UI must explain that losing it means losing cloud access.
 */
internal object ArchiveEncryption {
    const val EXTENSION = ".noopenc"
    const val DEFAULT_ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 200_000
    private const val MAX_ITERATIONS = 1_200_000
    private const val CHUNK_BYTES = 64 * 1024
    private const val SALT_BYTES = 16
    private const val NONCE_PREFIX_BYTES = 8
    private const val GCM_TAG_BYTES = 16
    private const val VERSION: Byte = 1
    private val MAGIC = "THOOPENC".toByteArray(Charsets.US_ASCII)
    private const val HEADER_BYTES = 8 + 1 + 4 + SALT_BYTES + NONCE_PREFIX_BYTES
    private val random = SecureRandom()

    /** Leaves [source] in place and creates a new encrypted file at [destination]. */
    fun encrypt(source: File, destination: File, passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS) {
        require(source.isFile) { "Source archive does not exist." }
        require(passphrase.size >= 12) { "An archive recovery passphrase must be at least 12 characters." }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Unsupported encryption work factor." }
        checkDistinctPaths(source, destination)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
        val header = ByteArrayOutputStream(HEADER_BYTES).also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.write(MAGIC)
                out.writeByte(VERSION.toInt())
                out.writeInt(iterations)
                out.write(salt)
                out.write(noncePrefix)
            }
        }.toByteArray()
        val key = deriveKey(passphrase, salt, iterations)
        try {
            publishVerified(destination) { temp ->
                FileInputStream(source).use { rawInput ->
                    FileOutputStream(temp).use { rawOutput ->
                        DataOutputStream(BufferedOutputStream(rawOutput)).use { output ->
                            output.write(header)
                            val input = BufferedInputStream(rawInput)
                            val buffer = ByteArray(CHUNK_BYTES)
                            var counter = 0
                            while (true) {
                                val size = input.read(buffer)
                                if (size < 0) break
                                if (size == 0) continue
                                writeFrame(output, key, header, noncePrefix, counter++, buffer, size, false)
                            }
                            writeFrame(output, key, header, noncePrefix, counter, ByteArray(0), 0, true)
                            output.flush()
                            rawOutput.fd.sync()
                        }
                    }
                }
            }
        } finally {
            key.fill(0)
            salt.fill(0)
        }
    }

    /**
     * Creates [destination] only if the complete encrypted file authenticates successfully.
     * On wrong passphrase, corruption, truncation, or trailing garbage, no destination remains.
     */
    fun decrypt(source: File, destination: File, passphrase: CharArray) {
        require(source.isFile) { "Encrypted archive does not exist." }
        require(passphrase.isNotEmpty()) { "An archive recovery passphrase is required." }
        checkDistinctPaths(source, destination)
        DataInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
            val header = ByteArray(HEADER_BYTES).also(input::readFully)
            require(header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                "Not a THOOP encrypted archive."
            }
            require(header[MAGIC.size] == VERSION) { "Unsupported archive encryption version." }
            val iterations = ByteBuffer.wrap(header, MAGIC.size + 1, 4).int
            require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) {
                "Unsupported archive encryption work factor."
            }
            val saltStart = MAGIC.size + 1 + 4
            val salt = header.copyOfRange(saltStart, saltStart + SALT_BYTES)
            val noncePrefix = header.copyOfRange(saltStart + SALT_BYTES, HEADER_BYTES)
            val key = deriveKey(passphrase, salt, iterations)
            try {
                publishVerified(destination) { temp ->
                    FileOutputStream(temp).use { rawOutput ->
                        BufferedOutputStream(rawOutput).use { output ->
                            var counter = 0
                            while (true) {
                                val size = input.readInt() // a missing final frame is an error
                                val flag = input.readUnsignedByte()
                                require(size in GCM_TAG_BYTES..(CHUNK_BYTES + GCM_TAG_BYTES)) {
                                    "Invalid archive frame size."
                                }
                                require(flag == 0 || flag == 1) { "Invalid archive frame type." }
                                require(flag != 1 || size == GCM_TAG_BYTES) {
                                    "Invalid archive final frame."
                                }
                                val ciphertext = ByteArray(size).also(input::readFully)
                                val cleartext = processFrame(
                                    Cipher.DECRYPT_MODE, key, header, noncePrefix,
                                    counter++, flag, size, ciphertext,
                                )
                                if (flag == 1) {
                                    check(cleartext.isEmpty()) { "Archive end marker was not empty." }
                                    check(input.read() == -1) { "Trailing bytes after archive end marker." }
                                    break
                                }
                                check(cleartext.isNotEmpty()) { "Unexpected empty archive data frame." }
                                output.write(cleartext)
                            }
                            output.flush()
                            rawOutput.fd.sync()
                        }
                    }
                }
            } finally {
                key.fill(0)
                salt.fill(0)
            }
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun writeFrame(
        output: DataOutputStream, key: ByteArray, header: ByteArray,
        noncePrefix: ByteArray, counter: Int, cleartext: ByteArray, size: Int, final: Boolean,
    ) {
        check(counter >= 0) { "Archive is too large for the v1 frame counter." }
        val flag = if (final) 1 else 0
        val ciphertextSize = size + GCM_TAG_BYTES
        val encrypted = processFrame(
            Cipher.ENCRYPT_MODE, key, header, noncePrefix, counter, flag, ciphertextSize,
            if (size == cleartext.size) cleartext else cleartext.copyOf(size),
        )
        check(encrypted.size == ciphertextSize)
        output.writeInt(ciphertextSize)
        output.writeByte(flag)
        output.write(encrypted)
    }

    private fun processFrame(
        mode: Int, key: ByteArray, header: ByteArray, noncePrefix: ByteArray,
        counter: Int, flag: Int, ciphertextSize: Int, content: ByteArray,
    ): ByteArray {
        check(counter >= 0) { "Archive is too large for the v1 frame counter." }
        val nonce = ByteBuffer.allocate(12).put(noncePrefix).putInt(counter).array()
        val metadata = ByteBuffer.allocate(9)
            .putInt(counter).put(flag.toByte()).putInt(ciphertextSize).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        cipher.updateAAD(metadata)
        return cipher.doFinal(content)
    }

    private fun checkDistinctPaths(source: File, destination: File) {
        require(source.canonicalFile != destination.canonicalFile) {
            "Source and destination must be different files."
        }
    }

    private inline fun publishVerified(destination: File, write: (File) -> Unit) {
        val target = destination.absoluteFile
        require(!target.exists()) { "Refusing to overwrite an existing archive or recovery file." }
        val parent = target.parentFile ?: error("Archive destination must have a parent folder.")
        check(parent.isDirectory) { "Archive destination folder does not exist." }
        val temp = File.createTempFile(".thoop-crypt-", ".tmp", parent)
        try {
            write(temp)
            check(!target.exists()) { "Archive destination already exists." }
            check(temp.renameTo(target)) { "Could not publish the verified archive." }
        } finally {
            temp.delete()
        }
    }
}
