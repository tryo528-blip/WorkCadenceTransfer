package com.tryo528.workcadencetransfer.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalDigestTest {
    @Test
    fun matchesTheSharedWct1OnePhotoVector() {
        val submission = SubmissionMetadata(
            version = 1,
            type = "transfer_submission",
            deviceId = "11111111-1111-4111-8111-111111111111",
            submissionId = "44444444-4444-4444-8444-444444444444",
            createdAt = "2026-08-14T02:03:04.005Z",
            targetDate = "2026-08-14",
            contentDigest = "8318ae176d4bf88c778bd187f02adca4298cdde97766f1a6b49d7ac7b269a5be",
            memo = "서류\r\n확인",
            photos = listOf(
                PhotoMetadata(
                    photoId = "55555555-5555-4555-8555-555555555555",
                    mime = "image/jpeg",
                    bytes = 123456,
                    sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                )
            )
        )

        assertEquals("서류\n확인", CanonicalDigest.normalizeMemo(submission.memo))
        val canonical = CanonicalDigest.canonicalBytes(submission)
        assertEquals(239, canonical.size)
        assertEquals(
            "57435431010000002431313131313131312d313131312d343131312d383131312d3131313131313131313131310000002434343434343434342d343434342d343434342d383434342d34343434343434343434343400000018323032362d30382d31345430323a30333a30342e3030355a0000000a323032362d30382d31340000000dec849ceba5980aed9995ec9db8010000002435353535353535352d353535352d343535352d383535352d3535353535353535353535350000000a696d6167652f6a706567000000000001e2400123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            canonical.toHex()
        )
        assertEquals(submission.contentDigest, CanonicalDigest.compute(submission))
    }

    @Test
    fun memoNormalizationIsStableAndPhotoOrderIsSignificant() {
        val photoA = PhotoMetadata(
            "55555555-5555-4555-8555-555555555555",
            "image/jpeg",
            1,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        )
        val photoB = photoA.copy(photoId = "66666666-6666-4666-8666-666666666666")
        val base = SubmissionMetadata(
            1,
            "transfer_submission",
            "11111111-1111-4111-8111-111111111111",
            "44444444-4444-4444-8444-444444444444",
            "2026-08-14T02:03:04.005Z",
            "2026-08-14",
            "0".repeat(64),
            "A\rB\r\nC",
            listOf(photoA, photoB)
        )

        assertEquals("A\nB\nC", CanonicalDigest.normalizeMemo(base.memo))
        assertNotEquals(
            CanonicalDigest.compute(base),
            CanonicalDigest.compute(base.copy(photos = listOf(photoB, photoA)))
        )
    }

    @Test
    fun validatesUuidAndSha256ContractBoundaries() {
        assertTrue(CanonicalDigest.validateUuidV4("11111111-1111-4111-8111-111111111111"))
        assertFalse(CanonicalDigest.validateUuidV4("11111111-1111-3111-8111-111111111111"))
        assertFalse(CanonicalDigest.validateUuidV4("11111111-1111-4111-c111-111111111111"))
        assertEquals(32, CanonicalDigest.decodeSha256("00".repeat(32)).size)
    }

    @Test
    fun factoryNormalizesMemoAndBindsDigestToPhotoBytes() {
        val pending = SubmissionFactory.create(
            deviceId = "11111111-1111-4111-8111-111111111111",
            memo = "메모\r\n확인",
            targetDate = "2026-08-14",
            photos = listOf(ImportedPhoto("55555555-5555-4555-8555-555555555555", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00), "원본.jpg")),
            createdAt = "2026-08-14T02:03:04.005Z",
            submissionId = "44444444-4444-4444-8444-444444444444"
        )

        assertEquals("메모\n확인", pending.metadata.memo)
        assertEquals(CanonicalDigest.compute(pending.metadata), pending.metadata.contentDigest)
        assertEquals(3L, pending.metadata.photos.single().bytes)
        assertEquals("image/jpeg", pending.metadata.photos.single().mime)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
