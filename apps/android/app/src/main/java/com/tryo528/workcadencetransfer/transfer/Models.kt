package com.tryo528.workcadencetransfer.transfer

data class PhotoMetadata(
    val photoId: String,
    val mime: String,
    val bytes: Long,
    val sha256: String
)

data class SubmissionMetadata(
    val version: Int,
    val type: String,
    val deviceId: String,
    val submissionId: String,
    val createdAt: String,
    val targetDate: String,
    val contentDigest: String,
    val memo: String,
    val photos: List<PhotoMetadata>
)

data class ImportedPhoto(
    val photoId: String,
    val bytes: ByteArray,
    val displayName: String?
) {
    val metadata: PhotoMetadata
        get() = PhotoMetadata(
            photoId = photoId,
            mime = "image/jpeg",
            bytes = bytes.size.toLong(),
            sha256 = CanonicalDigest.sha256Hex(bytes)
        )
}

data class PendingSubmission(
    val metadata: SubmissionMetadata,
    val photos: List<ImportedPhoto>
)

data class PairingQr(
    val version: Int,
    val type: String,
    val endpoint: String,
    val spkiSha256: String,
    val enrollmentId: String,
    val enrollmentSecret: String,
    val expiresAt: String
)

data class Enrollment(
    val endpoint: String,
    val spkiSha256: String,
    val deviceId: String,
    val deviceSecret: String,
    val scope: String
)

data class ReadyAck(
    val version: Int,
    val type: String,
    val accepted: Boolean,
    val state: String,
    val deviceId: String,
    val submissionId: String,
    val contentDigest: String,
    val recordId: String,
    val storedAt: String
)

data class EnrollmentResult(
    val version: Int,
    val type: String,
    val deviceId: String,
    val deviceSecret: String,
    val scope: String,
    val issuedAt: String
)

class ContractException(message: String) : IllegalArgumentException(message)
