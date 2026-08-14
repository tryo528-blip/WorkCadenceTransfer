package com.tryo528.workcadencetransfer.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.tryo528.workcadencetransfer.transfer.CanonicalDigest
import com.tryo528.workcadencetransfer.transfer.Enrollment
import com.tryo528.workcadencetransfer.transfer.ImportedPhoto
import com.tryo528.workcadencetransfer.transfer.JsonContracts
import com.tryo528.workcadencetransfer.transfer.PendingSubmission
import com.tryo528.workcadencetransfer.transfer.SemanticValidator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurePendingStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "pending").apply { mkdirs() }
    private val keyStoreName = "AndroidKeyStore"
    private val enrollmentAlias = "wct-enrollment-v1"
    private val pendingMagic = "WCTP2".toByteArray(Charsets.US_ASCII)
    private val enrollmentMagic = "WCTE2".toByteArray(Charsets.US_ASCII)
    private val aadMagic = "WCTENC1".toByteArray(Charsets.US_ASCII)
    private val maxMetadataBytes = 16_384
    private val maxPhotoBytes = 5_242_880
    private val maxTotalPhotoBytes = 26_214_400L

    fun save(payload: PendingSubmission) {
        SemanticValidator.validatePending(payload)
        atomicWrite(File(root, "${payload.metadata.submissionId}.enc"), encodePending(payload))
    }

    fun load(submissionId: String): PendingSubmission {
        require(SemanticValidator.isUuidV4(submissionId)) { "submission ID is not a safe UUID v4" }
        return decodePending(File(root, "$submissionId.enc").readBytes(), submissionId)
    }

    fun pendingIds(): List<String> = root.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".enc") }
        ?.map { it.name.removeSuffix(".enc") }
        ?.filter(SemanticValidator::isUuidV4)
        ?.sorted()
        ?: emptyList()

    fun delete(submissionId: String) {
        require(SemanticValidator.isUuidV4(submissionId)) { "submission ID is not a safe UUID v4" }
        deleteKey(keyAlias(submissionId))
        File(root, "$submissionId.enc").delete()
        File(root, ".$submissionId.enc.tmp").delete()
    }

    fun saveEnrollment(enrollment: Enrollment) {
        SemanticValidator.validateEnrollment(enrollment)
        val plaintext = JsonContracts.enrollmentToJson(enrollment).toByteArray(Charsets.UTF_8)
        val encrypted = encrypt(getOrCreateKey(enrollmentAlias), plaintext, enrollmentAad())
        atomicWrite(File(root, "enrollment.enc"), wrap(enrollmentMagic, encrypted))
    }

    fun loadEnrollment(): Enrollment? {
        val file = File(root, "enrollment.enc")
        if (!file.isFile) return null
        val encrypted = unwrap(enrollmentMagic, file.readBytes())
        val json = decrypt(getOrCreateKey(enrollmentAlias), encrypted, enrollmentAad()).toString(Charsets.UTF_8)
        return JsonContracts.enrollmentFromJson(json).also(SemanticValidator::validateEnrollment)
    }

    fun deleteEnrollment() {
        deleteKey(enrollmentAlias)
        File(root, "enrollment.enc").delete()
        File(root, ".enrollment.enc.tmp").delete()
    }

    private fun encodePending(payload: PendingSubmission): ByteArray {
        val metadataJson = JsonContracts.submissionToJson(payload.metadata).toByteArray(Charsets.UTF_8)
        require(metadataJson.size <= maxMetadataBytes) { "metadata is too large" }
        require(payload.photos.size <= 5) { "photo count is outside the limit" }

        val key = getOrCreateKey(keyAlias(payload.metadata.submissionId))
        val output = ByteArrayOutputStream()
        output.write(pendingMagic)
        writeString(output, payload.metadata.deviceId)
        writeString(output, payload.metadata.submissionId)
        output.write(CanonicalDigest.decodeSha256(payload.metadata.contentDigest))
        output.write(payload.photos.size)
        writeBlob(output, key, "metadata", metadataJson, pendingAad(payload, "metadata", "none"))
        payload.photos.forEach { photo ->
            require(photo.bytes.size in 1..maxPhotoBytes) { "photo bytes are outside the limit" }
            writeString(output, photo.photoId)
            writeBlob(output, key, "photo", photo.bytes, pendingAad(payload, "photo", photo.photoId))
        }
        require(payload.photos.sumOf { it.bytes.size.toLong() } <= maxTotalPhotoBytes) { "photo bytes exceed total limit" }
        return output.toByteArray()
    }

    private fun decodePending(encoded: ByteArray, expectedSubmissionId: String): PendingSubmission {
        val input = DataInputStream(ByteArrayInputStream(encoded))
        readMagic(input, pendingMagic)
        val deviceId = readString(input)
        val submissionId = readString(input)
        require(submissionId == expectedSubmissionId && SemanticValidator.isUuidV4(deviceId)) { "pending header identity mismatch" }
        val digest = readBytes(input, 32).toHex()
        val count = input.readUnsignedByte()
        require(count in 0..5) { "photo count is outside the limit" }
        val key = getOrCreateKey(keyAlias(submissionId))
        val header = PendingHeader(deviceId, submissionId, digest)
        val metadataBytes = readBlob(input, key, "metadata", pendingAad(header, "metadata", "none"), maxMetadataBytes)
        val metadata = JsonContracts.submissionFromJson(metadataBytes.toString(Charsets.UTF_8))
        require(metadata.deviceId == deviceId && metadata.submissionId == submissionId && metadata.contentDigest == digest) {
            "pending metadata/header mismatch"
        }
        val photos = ArrayList<ImportedPhoto>(count)
        repeat(count) {
            val photoId = readString(input)
            val body = readBlob(input, key, "photo", pendingAad(header, "photo", photoId), maxPhotoBytes)
            photos += ImportedPhoto(photoId, body, null)
        }
        require(input.available() == 0) { "trailing pending bytes" }
        return PendingSubmission(metadata, photos).also(SemanticValidator::validatePending)
    }

    private fun pendingAad(payload: PendingSubmission, blobKind: String, photoId: String): ByteArray =
        pendingAad(PendingHeader(payload.metadata.deviceId, payload.metadata.submissionId, payload.metadata.contentDigest), blobKind, photoId)

    private fun pendingAad(header: PendingHeader, blobKind: String, photoId: String): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(aadMagic)
        writeString(output, "mobile-pending")
        writeString(output, header.deviceId)
        writeString(output, header.submissionId)
        writeString(output, blobKind)
        writeString(output, photoId)
        output.write(CanonicalDigest.decodeSha256(header.contentDigest))
        return output.toByteArray()
    }

    private fun enrollmentAad(): ByteArray = aadMagic + "mobile-enrollment".toByteArray(Charsets.UTF_8)

    private fun writeBlob(
        output: ByteArrayOutputStream,
        key: SecretKey,
        blobKind: String,
        plaintext: ByteArray,
        aad: ByteArray
    ) {
        val encrypted = encrypt(key, plaintext, aad)
        output.write(if (blobKind == "metadata") 0 else 1)
        writeByteArray(output, encrypted)
    }

    private fun readBlob(
        input: DataInputStream,
        key: SecretKey,
        expectedKind: String,
        aad: ByteArray,
        maxPlaintextBytes: Int
    ): ByteArray {
        val kind = input.readUnsignedByte()
        val expectedKindCode = if (expectedKind == "metadata") 0 else 1
        require(kind == expectedKindCode) { "pending blob order mismatch" }
        val encrypted = readByteArray(input, maxPlaintextBytes + 33)
        return decrypt(key, encrypted, aad).also { require(it.size in 1..maxPlaintextBytes) { "pending blob exceeds limit" } }
    }

    private fun encrypt(key: SecretKey, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(key: SecretKey, wrapped: ByteArray, aad: ByteArray): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(wrapped))
        val nonceSize = input.readUnsignedByte()
        require(nonceSize in 12..16) { "invalid GCM nonce size" }
        val nonce = readBytes(input, nonceSize)
        val ciphertext = readBytes(input, input.available())
        require(ciphertext.size >= 16) { "encrypted blob is too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun wrap(magic: ByteArray, body: ByteArray): ByteArray = magic + body

    private fun unwrap(magic: ByteArray, value: ByteArray): ByteArray {
        require(value.size > magic.size && value.copyOfRange(0, magic.size).contentEquals(magic)) { "unknown encrypted blob" }
        return value.copyOfRange(magic.size, value.size)
    }

    private fun readMagic(input: DataInputStream, expected: ByteArray) {
        val actual = readBytes(input, expected.size)
        require(actual.contentEquals(expected)) { "unknown pending blob" }
    }

    private fun writeString(output: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..1024) { "pending string is outside the limit" }
        writeU32(output, bytes.size.toLong())
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val size = readU32(input).toInt()
        require(size in 1..1024) { "pending string is outside the limit" }
        return readBytes(input, size).toString(Charsets.UTF_8)
    }

    private fun writeByteArray(output: ByteArrayOutputStream, value: ByteArray) {
        writeU32(output, value.size.toLong())
        output.write(value)
    }

    private fun readByteArray(input: DataInputStream, maxSize: Int): ByteArray {
        val size = readU32(input).toInt()
        require(size in 1..maxSize) { "encrypted pending blob is outside the limit" }
        return readBytes(input, size)
    }

    private fun writeU32(output: ByteArrayOutputStream, value: Long) {
        require(value in 0..0xFFFF_FFFFL)
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array())
    }

    private fun readU32(input: DataInputStream): Long = input.readInt().toLong() and 0xFFFF_FFFFL

    private fun readBytes(input: DataInputStream, size: Int): ByteArray = ByteArray(size).also(input::readFully)

    private fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(keyStoreName).apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, keyStoreName).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }

    private fun keyAlias(submissionId: String): String = "wct-pending-$submissionId"

    private fun deleteKey(alias: String) {
        KeyStore.getInstance(keyStoreName).apply {
            load(null)
            if (containsAlias(alias)) deleteEntry(alias)
        }
    }

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        root.mkdirs()
        val temporary = File(root, ".${destination.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private data class PendingHeader(val deviceId: String, val submissionId: String, val contentDigest: String)
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
