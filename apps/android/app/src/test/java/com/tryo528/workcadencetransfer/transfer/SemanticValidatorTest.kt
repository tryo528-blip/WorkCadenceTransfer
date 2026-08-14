package com.tryo528.workcadencetransfer.transfer

import org.junit.Test

class SemanticValidatorTest {
    @Test
    fun acceptsMemoOnlySubmission() {
        SemanticValidator.validateSubmission(validSubmission())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsContractWhitespaceOnlyMemoWithoutPhoto() {
        val submission = validSubmission().copy(memo = "\u00A0")
        SemanticValidator.validateSubmission(submission.copy(contentDigest = CanonicalDigest.compute(submission)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonexistentCalendarDate() {
        val submission = validSubmission().copy(targetDate = "2026-02-30")
        SemanticValidator.validateSubmission(submission.copy(contentDigest = CanonicalDigest.compute(submission)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUppercasePhotoHash() {
        val submission = validSubmission().copy(
            photos = listOf(
                PhotoMetadata(
                    "55555555-5555-4555-8555-555555555555",
                    "image/jpeg",
                    1,
                    "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789"
                )
            )
        )
        SemanticValidator.validateSubmission(submission.copy(contentDigest = CanonicalDigest.compute(submission)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPendingBodyHashThatDiffersFromMetadata() {
        val pending = SubmissionFactory.create(
            deviceId = "11111111-1111-4111-8111-111111111111",
            memo = "사진",
            targetDate = "2026-08-14",
            photos = listOf(ImportedPhoto("55555555-5555-4555-8555-555555555555", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00), null)),
            createdAt = "2026-08-14T02:03:04.005Z",
            submissionId = "44444444-4444-4444-8444-444444444444"
        )
        val corruptBody = ImportedPhoto(pending.photos.single().photoId, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01), null)
        SemanticValidator.validatePending(pending.copy(photos = listOf(corruptBody)))
    }

    private fun validSubmission(): SubmissionMetadata {
        val withoutDigest = SubmissionMetadata(
            version = 1,
            type = "transfer_submission",
            deviceId = "11111111-1111-4111-8111-111111111111",
            submissionId = "44444444-4444-4444-8444-444444444444",
            createdAt = "2026-08-14T02:03:04.005Z",
            targetDate = "2026-08-14",
            contentDigest = "0".repeat(64),
            memo = "메모",
            photos = emptyList()
        )
        return withoutDigest.copy(contentDigest = CanonicalDigest.compute(withoutDigest))
    }
}
