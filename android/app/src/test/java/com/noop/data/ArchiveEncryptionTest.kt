package com.noop.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom

class ArchiveEncryptionTest {
    @get:Rule val folder = TemporaryFolder()
    private val password = "test-only-recovery-passphrase!".toCharArray()
    private val wrongPassword = "wrong-recovery-passphrase!".toCharArray()
    private val testIterations = 200_000

    @Test fun `large file round trip is lossless and source stays untouched`() {
        val original = ByteArray(1024 * 1024 + 15).also(SecureRandom()::nextBytes)
        val source = newFile("original.nooparc", original)
        val encrypted = File(folder.root, "backup.noopenc")
        val recovered = File(folder.root, "recovered.nooparc")
        ArchiveEncryption.encrypt(source, encrypted, password, testIterations)
        assertTrue(encrypted.isFile)
        assertFalse(encrypted.readBytes().contentEquals(original))
        ArchiveEncryption.decrypt(encrypted, recovered, password)
        assertArrayEquals(original, recovered.readBytes())
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun `empty archive has authenticated end marker`() {
        val source = newFile("empty.nooparc", byteArrayOf())
        val encrypted = File(folder.root, "empty.noopenc")
        val recovered = File(folder.root, "empty-restored.nooparc")
        ArchiveEncryption.encrypt(source, encrypted, password, testIterations)
        ArchiveEncryption.decrypt(encrypted, recovered, password)
        assertTrue(recovered.isFile && recovered.length() == 0L)
    }

    @Test fun `wrong passphrase leaves no recovered file`() {
        val encrypted = makeEncrypted()
        val recovered = File(folder.root, "wrong.nooparc")
        mustFail { ArchiveEncryption.decrypt(encrypted, recovered, wrongPassword) }
        assertFalse(recovered.exists())
    }

    @Test fun `altered ciphertext leaves no recovered file`() {
        val encrypted = makeEncrypted()
        val bytes = encrypted.readBytes()
        bytes[bytes.size - 24] = (bytes[bytes.size - 24].toInt() xor 0x01).toByte()
        encrypted.writeBytes(bytes)
        val recovered = File(folder.root, "corrupted.nooparc")
        mustFail { ArchiveEncryption.decrypt(encrypted, recovered, password) }
        assertFalse(recovered.exists())
    }

    @Test fun `truncation and trailing bytes are both rejected`() {
        val encrypted = makeEncrypted()
        val original = encrypted.readBytes()
        encrypted.writeBytes(original.copyOf(original.size - 8))
        val truncatedRecovery = File(folder.root, "truncated.nooparc")
        mustFail { ArchiveEncryption.decrypt(encrypted, truncatedRecovery, password) }
        assertFalse(truncatedRecovery.exists())
        encrypted.writeBytes(original + byteArrayOf(1, 2, 3))
        val trailingRecovery = File(folder.root, "trailing.nooparc")
        mustFail { ArchiveEncryption.decrypt(encrypted, trailingRecovery, password) }
        assertFalse(trailingRecovery.exists())
    }

    @Test fun `plaintext is never accepted as an encrypted archive`() {
        val source = newFile("plaintext.nooparc", "unencrypted".toByteArray())
        val recovered = File(folder.root, "bad.nooparc")
        mustFail { ArchiveEncryption.decrypt(source, recovered, password) }
        assertFalse(recovered.exists())
    }

    @Test fun `existing recovery destination is never overwritten`() {
        val encrypted = makeEncrypted()
        val recovered = newFile("important.nooparc", "keep-me".toByteArray())
        mustFail { ArchiveEncryption.decrypt(encrypted, recovered, password) }
        assertArrayEquals("keep-me".toByteArray(), recovered.readBytes())
    }

    private fun makeEncrypted(): File {
        val source = newFile("source.nooparc", ByteArray(140_000) { (it % 251).toByte() })
        return File(folder.root, "source.noopenc").also {
            ArchiveEncryption.encrypt(source, it, password, testIterations)
        }
    }

    private fun newFile(name: String, content: ByteArray): File =
        File(folder.root, name).also { it.writeBytes(content) }

    private fun mustFail(block: () -> Unit) {
        try { block(); fail("Expected archive verification to fail") }
        catch (expected: Exception) { /* no recovery file may be published on failure */ }
    }
}
