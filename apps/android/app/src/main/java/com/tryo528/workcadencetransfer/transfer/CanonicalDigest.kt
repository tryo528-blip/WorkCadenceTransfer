package com.tryo528.workcadencetransfer.transfer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID

object CanonicalDigest {
    private const val MAGIC = "WCT1"
    private const val VERSION = 1
    private val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val shaPattern = Regex("^[0-9a-f]{64}$")

    fun normalizeMemo(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n').let {
            Normalizer.normalize(it, Normalizer.Form.NFC)
        }

    fun canonicalBytes(submission: SubmissionMetadata, normalizedMemo: String = normalizeMemo(submission.memo)): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(MAGIC.toByteArray(Charsets.US_ASCII))
        output.write(VERSION)
        writeLp(output, submission.deviceId)
        writeLp(output, submission.submissionId)
        writeLp(output, submission.createdAt)
        writeLp(output, submission.targetDate)
        writeLp(output, normalizedMemo)
        require(submission.photos.size <= 255) { "photo count cannot fit canonical byte" }
        output.write(submission.photos.size)
        submission.photos.forEach { photo ->
            writeLp(output, photo.photoId)
            writeLp(output, photo.mime)
            writeU64(output, photo.bytes)
            val hash = decodeSha256(photo.sha256)
            output.write(hash)
        }
        return output.toByteArray()
    }

    fun compute(submission: SubmissionMetadata): String =
        sha256Hex(canonicalBytes(submission))

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun validateUuidV4(value: String): Boolean = try {
        uuidPattern.matches(value) && UUID.fromString(value).toString() == value && UUID.fromString(value).version() == 4
    } catch (_: IllegalArgumentException) {
        false
    }

    fun decodeSha256(value: String): ByteArray {
        require(shaPattern.matches(value)) { "sha256 must be lowercase hexadecimal" }
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun writeLp(output: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFFFF_FFFFL) { "value is too long" }
        val length = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).array()
        output.write(length)
        output.write(bytes)
    }

    private fun writeU64(output: ByteArrayOutputStream, value: Long) {
        require(value >= 0) { "u64 value must be non-negative" }
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
        output.write(bytes)
    }
}

object SubmissionFactory {
    fun create(
        deviceId: String,
        memo: String,
        targetDate: String,
        photos: List<ImportedPhoto>,
        createdAt: String,
        submissionId: String = UUID.randomUUID().toString()
    ): PendingSubmission {
        val normalizedMemo = CanonicalDigest.normalizeMemo(memo)
        val metadataWithoutDigest = SubmissionMetadata(
            version = 1,
            type = "transfer_submission",
            deviceId = deviceId,
            submissionId = submissionId,
            createdAt = createdAt,
            targetDate = targetDate,
            contentDigest = "0".repeat(64),
            memo = normalizedMemo,
            photos = photos.map { it.metadata }
        )
        SemanticValidator.validateSubmissionFields(metadataWithoutDigest, checkDigest = false)
        val metadata = metadataWithoutDigest.copy(
            contentDigest = CanonicalDigest.compute(metadataWithoutDigest)
        )
        SemanticValidator.validateSubmission(metadata)
        return PendingSubmission(metadata, photos)
    }
}
