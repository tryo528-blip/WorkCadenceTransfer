from __future__ import annotations

import copy
import hashlib
import unittest
from pathlib import Path

from conformance.digest import canonical_bytes, content_digest, normalize_memo
from conformance.schema import DuplicateKeyError, load_json, validate
from conformance.semantic import MAX_PHOTO_BYTES, MAX_TOTAL_PHOTO_BYTES, Issue, validate_submission


ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / "contracts"
VECTORS = CONTRACTS / "test-vectors"


def vector(name: str) -> dict:
    value = load_json(VECTORS / name)
    assert isinstance(value, dict)
    return value


def codes(issues: tuple[Issue, ...]) -> set[str]:
    return {issue.code for issue in issues}


class SchemaConformanceTests(unittest.TestCase):
    def test_all_contract_schemas_parse_and_submission_rejects_unknown_fields(self) -> None:
        schema_paths = sorted(CONTRACTS.glob("*.schema.json"))
        self.assertEqual(len(schema_paths), 6)
        for path in schema_paths:
            self.assertIsInstance(load_json(path), dict, path.name)

        submission = vector("digest-v1-memo-only.json")["submission"]
        schema = load_json(CONTRACTS / "submission-v1.schema.json")
        self.assertEqual(validate(submission, schema), [])
        extra = {**submission, "unexpected": True}
        self.assertTrue(any(issue.keyword == "additionalProperties" for issue in validate(extra, schema)))

    def test_duplicate_json_keys_are_rejected(self) -> None:
        with self.assertRaises(DuplicateKeyError):
            load_json('{"memo":"a","memo":"b"}')
        with self.assertRaises(DuplicateKeyError):
            load_json('{"nested":{"photos":[],"photos":[]}}')

    def test_schema_one_of_and_const_rules_are_enforced(self) -> None:
        schema = load_json(CONTRACTS / "error-v1.schema.json")
        valid = {
            "version": 1,
            "type": "transfer_error",
            "accepted": False,
            "code": "STORAGE_BUSY",
            "retryable": True,
        }
        self.assertEqual(validate(valid, schema), [])
        self.assertTrue(validate({**valid, "retryable": False}, schema))


class DigestConformanceTests(unittest.TestCase):
    def test_fixed_vectors_match_canonical_hex_length_and_digest(self) -> None:
        expected = {
            "digest-v1-memo-only.json": (145, "477a05eebcc9a806edd7ef512c804ea88ececbd6ad2c35dc6f6f07de63c0660e"),
            "digest-v1-one-photo-metadata.json": (239, "8318ae176d4bf88c778bd187f02adca4298cdde97766f1a6b49d7ac7b269a5be"),
        }
        for name, (length, digest) in expected.items():
            submission = vector(name)["submission"]
            encoded = canonical_bytes(submission)
            self.assertEqual(len(encoded), length, name)
            self.assertEqual(encoded.hex(), vector(name)["canonicalHex"], name)
            self.assertEqual(content_digest(submission), digest, name)
            self.assertTrue(validate_submission(submission).valid, name)

    def test_normalization_and_order_rules(self) -> None:
        self.assertEqual(normalize_memo("a\r\nb\rc"), "a\nb\nc")
        self.assertEqual(normalize_memo("e\u0301"), "é")

        submission = vector("digest-v1-one-photo-metadata.json")["submission"]
        reordered = {
            "photos": submission["photos"],
            "memo": submission["memo"],
            "targetDate": submission["targetDate"],
            "createdAt": submission["createdAt"],
            "submissionId": submission["submissionId"],
            "type": submission["type"],
            "version": submission["version"],
            "contentDigest": "0" * 64,
            "deviceId": submission["deviceId"],
        }
        self.assertEqual(content_digest(submission), content_digest(reordered))

        second = {
            "photoId": "66666666-6666-4666-8666-666666666666",
            "mime": "image/jpeg",
            "bytes": 123456,
            "sha256": "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
        }
        two = copy.deepcopy(submission)
        two["photos"] = [submission["photos"][0], second]
        reversed_photos = copy.deepcopy(two)
        reversed_photos["photos"] = list(reversed(two["photos"]))
        self.assertNotEqual(content_digest(two), content_digest(reversed_photos))


class SubmissionSemanticTests(unittest.TestCase):
    def memo_submission(self) -> dict:
        return copy.deepcopy(vector("digest-v1-memo-only.json")["submission"])

    def photo_submission(self) -> dict:
        return copy.deepcopy(vector("digest-v1-one-photo-metadata.json")["submission"])

    def test_memo_utf8_boundary_and_fixed_whitespace(self) -> None:
        exact = self.memo_submission()
        exact["memo"] = "가" * 2730 + "ab"  # exactly 8,192 UTF-8 bytes
        exact["contentDigest"] = content_digest(exact)
        self.assertTrue(validate_submission(exact).valid)

        over = copy.deepcopy(exact)
        over["memo"] += "c"
        over["contentDigest"] = content_digest(over)
        self.assertIn("INVALID_SUBMISSION", codes(validate_submission(over).issues))

        blank = self.memo_submission()
        blank["memo"] = "\u00a0\u3000"
        blank["contentDigest"] = content_digest(blank)
        self.assertTrue(any("blank" in issue.message for issue in validate_submission(blank).issues))

    def test_uuid_and_real_date_time_rules(self) -> None:
        for field, value in (
            ("deviceId", "11111111-1111-1111-8111-111111111111"),
            ("deviceId", "11111111-1111-4111-8111-11111111111A"),
            ("createdAt", "2026-02-30T01:02:03.004Z"),
            ("createdAt", "2026-08-14T01:02:60.004Z"),
            ("targetDate", "2026-02-30"),
        ):
            submission = self.memo_submission()
            submission[field] = value
            result = validate_submission(submission)
            self.assertTrue(any(issue.path == f"$.{field}" for issue in result.issues), field)

    def test_photo_count_id_mime_and_byte_limits(self) -> None:
        duplicate = self.photo_submission()
        duplicate["photos"].append(copy.deepcopy(duplicate["photos"][0]))
        duplicate["contentDigest"] = content_digest(duplicate)
        self.assertTrue(any("unique" in issue.message for issue in validate_submission(duplicate).issues))

        too_large = self.photo_submission()
        too_large["photos"][0]["bytes"] = MAX_PHOTO_BYTES + 1
        too_large["contentDigest"] = content_digest(too_large)
        self.assertIn("RESOURCE_LIMIT_EXCEEDED", codes(validate_submission(too_large).issues))

        five = self.photo_submission()
        base_photo = five["photos"][0]
        five["photos"] = []
        for index in range(5):
            photo = copy.deepcopy(base_photo)
            photo["photoId"] = f"{index + 1:08d}-7777-4777-8777-777777777777"
            photo["bytes"] = MAX_PHOTO_BYTES
            five["photos"].append(photo)
        five["contentDigest"] = content_digest(five)
        self.assertTrue(validate_submission(five).valid)
        self.assertEqual(sum(photo["bytes"] for photo in five["photos"]), MAX_TOTAL_PHOTO_BYTES)

    def test_digest_path_identity_and_photo_body_hash(self) -> None:
        changed = self.memo_submission()
        changed["memo"] = "다른 내용"
        self.assertIn("CONTENT_DIGEST_MISMATCH", codes(validate_submission(changed).issues))

        result = validate_submission(
            self.memo_submission(),
            request_submission_id="44444444-4444-4444-8444-444444444444",
            authenticated_device_id="99999999-9999-4999-8999-999999999999",
        )
        self.assertIn("DEVICE_UNAUTHORIZED", codes(result.issues))
        self.assertTrue(any("path" in issue.message for issue in result.issues))

        body = b"jpeg-test-body"
        submission = self.photo_submission()
        photo = submission["photos"][0]
        photo["bytes"] = len(body)
        photo["sha256"] = hashlib.sha256(body).hexdigest()
        submission["contentDigest"] = content_digest(submission)
        self.assertTrue(validate_submission(submission, actual_photo_bodies={photo["photoId"]: body}).valid)
        bad = validate_submission(submission, actual_photo_bodies={photo["photoId"]: body + b"!"})
        self.assertIn("CONTENT_DIGEST_MISMATCH", codes(bad.issues))


if __name__ == "__main__":
    unittest.main()
