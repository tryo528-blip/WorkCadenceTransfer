package com.tryo528.workcadencetransfer.transfer

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Base64
import java.util.Locale

object SemanticValidator {
    private const val MAX_MEMO_BYTES = 8_192
    private const val MAX_PHOTOS = 5
    private const val MAX_PHOTO_BYTES = 5_242_880L
    private const val MAX_TOTAL_PHOTO_BYTES = 26_214_400L
    private val uuidV4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val sha256 = Regex("^[0-9a-f]{64}$")
    private val utcMilliseconds = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")
    private val localDate = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val base64Url32 = Regex("^[A-Za-z0-9_-]{43}$")
    private val utcParser = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)
    private val utcFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
    private val dateFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)
    private val contractWhitespace = mutableSetOf<Char>().apply {
        addAll((0x0009..0x000D).map { it.toChar() })
        addAll((0x2000..0x200A).map { it.toChar() })
        addAll(listOf('\u0020', '\u0085', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'))
    }

    fun validateSubmission(
        submission: SubmissionMetadata,
        pathSubmissionId: String? = null,
        authenticatedDeviceId: String? = null
    ) {
        validateSubmissionFields(submission, checkDigest = true)
        if (pathSubmissionId != null && submission.submissionId != pathSubmissionId) {
            reject("path and metadata submission IDs differ")
        }
        if (authenticatedDeviceId != null && submission.deviceId != authenticatedDeviceId) {
            throw ReceiverException("body device ID differs from authenticated principal")
        }
        if (CanonicalDigest.compute(submission) != submission.contentDigest) {
            throw ContractException("contentDigest differs from canonical submission bytes")
        }
    }

    fun validatePending(pending: PendingSubmission) {
        validateSubmission(pending.metadata)
        require(pending.photos.size == pending.metadata.photos.size) { "metadata/photo count mismatch" }
        pending.photos.zip(pending.metadata.photos).forEach { (body, declaration) ->
            require(body.photoId == declaration.photoId) { "photo ID order mismatch" }
            require(body.bytes.size.toLong() == declaration.bytes) { "photo byte declaration mismatch" }
            require(body.bytes.isNotEmpty() && body.bytes.size <= MAX_PHOTO_BYTES) { "photo bytes are outside the limit" }
            require(declaration.mime == "image/jpeg") { "photo mime is not JPEG" }
            require(CanonicalDigest.sha256Hex(body.bytes) == declaration.sha256) { "photo hash declaration mismatch" }
        }
    }

    fun validatePairing(pairing: PairingQr) {
        require(pairing.version == 1 && pairing.type == "transfer_pairing_qr") { "invalid pairing QR type" }
        require(isUuidV4(pairing.enrollmentId)) { "invalid enrollment ID" }
        require(isBase64Url32(pairing.spkiSha256)) { "invalid SPKI pin" }
        require(isBase64Url32(pairing.enrollmentSecret)) { "invalid enrollment secret" }
        require(isUtcMilliseconds(pairing.expiresAt)) { "pairing expiry is not canonical UTC milliseconds" }
        require(Instant.parse(pairing.expiresAt).isAfter(Instant.now())) { "pairing QR is expired" }
    }

    fun validateEnrollment(enrollment: Enrollment) {
        require(enrollment.scope == "transfer_upload") { "unexpected enrollment scope" }
        require(isUuidV4(enrollment.deviceId)) { "invalid device ID" }
        require(isBase64Url32(enrollment.spkiSha256)) { "invalid SPKI pin" }
        require(isBase64Url32(enrollment.deviceSecret)) { "invalid device secret" }
    }

    fun validateEnrollmentResult(result: EnrollmentResult) {
        require(result.version == 1 && result.type == "transfer_enrollment_result") { "invalid enrollment response" }
        require(isUuidV4(result.deviceId)) { "invalid device ID" }
        require(isBase64Url32(result.deviceSecret)) { "invalid device secret" }
        require(result.scope == "transfer_upload") { "unexpected enrollment scope" }
        require(isUtcMilliseconds(result.issuedAt)) { "issuedAt is not canonical UTC milliseconds" }
    }

    fun validateReadyAck(ack: ReadyAck, pending: PendingSubmission) {
        require(ack.version == 1 && ack.type == "transfer_ready_ack" && ack.accepted && ack.state == "READY") { "invalid READY ACK" }
        require(isUuidV4(ack.deviceId) && ack.deviceId == pending.metadata.deviceId) { "READY ACK device mismatch" }
        require(isUuidV4(ack.submissionId) && ack.submissionId == pending.metadata.submissionId) { "READY ACK submission mismatch" }
        require(sha256.matches(ack.contentDigest) && ack.contentDigest == pending.metadata.contentDigest) { "READY ACK digest mismatch" }
        require(isUuidV4(ack.recordId)) { "READY ACK record ID is invalid" }
        require(isUtcMilliseconds(ack.storedAt)) { "READY ACK storedAt is invalid" }
    }

    fun isUuidV4(value: String): Boolean = try {
        uuidV4.matches(value) && java.util.UUID.fromString(value).toString() == value && java.util.UUID.fromString(value).version() == 4
    } catch (_: IllegalArgumentException) {
        false
    }

    fun isSha256(value: String): Boolean = sha256.matches(value)

    internal fun validateSubmissionFields(submission: SubmissionMetadata, checkDigest: Boolean) {
        require(submission.version == 1 && submission.type == "transfer_submission") { "version/type is not transfer_submission v1" }
        require(isUuidV4(submission.deviceId) && isUuidV4(submission.submissionId)) { "IDs must be lowercase canonical UUID v4" }
        require(isUtcMilliseconds(submission.createdAt)) { "createdAt is not canonical UTC milliseconds" }
        require(isLocalDate(submission.targetDate)) { "targetDate is not a valid local date" }

        val normalizedMemo = CanonicalDigest.normalizeMemo(submission.memo)
        require(normalizedMemo.toByteArray(Charsets.UTF_8).size <= MAX_MEMO_BYTES) { "memo exceeds 8,192 UTF-8 bytes" }
        require(normalizedMemo.isNotEmpty() && normalizedMemo.any { it !in contractWhitespace }) { "memo is empty" }

        require(submission.photos.size <= MAX_PHOTOS) { "photos must contain zero to five items" }
        val photoIds = mutableSetOf<String>()
        var totalBytes = 0L
        submission.photos.forEach { photo ->
            require(isUuidV4(photo.photoId) && photoIds.add(photo.photoId)) { "photo IDs must be unique lowercase canonical UUID v4" }
            require(photo.mime == "image/jpeg") { "photo MIME must be image/jpeg" }
            require(photo.bytes in 1..MAX_PHOTO_BYTES) { "photo bytes are outside the v1 limit" }
            require(isSha256(photo.sha256)) { "photo SHA-256 must be lowercase hexadecimal" }
            totalBytes = Math.addExact(totalBytes, photo.bytes)
        }
        require(totalBytes <= MAX_TOTAL_PHOTO_BYTES) { "total photo bytes exceed 25 MiB" }
        if (checkDigest) require(isSha256(submission.contentDigest)) { "contentDigest must be lowercase SHA-256" }
    }

    private fun isBase64Url32(value: String): Boolean {
        if (!base64Url32.matches(value)) return false
        return try {
            val decoded = Base64.getUrlDecoder().decode(value)
            decoded.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun isUtcMilliseconds(value: String): Boolean {
        if (!utcMilliseconds.matches(value)) return false
        return try {
            val parsed = LocalDateTime.parse(value.removeSuffix("Z"), utcParser)
            parsed.format(utcFormatter) == value
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun isLocalDate(value: String): Boolean = try {
        localDate.matches(value) && LocalDate.parse(value, dateFormatter).format(dateFormatter) == value
    } catch (_: RuntimeException) {
        false
    }

    private fun reject(message: String): Nothing = throw ContractException(message)
}
