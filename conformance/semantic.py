"""Minimal semantic validator for the submission contract.

JSON Schema checks shape.  This module checks the submission rules that cannot
be expressed by the current schemas alone and feeds the canonical digest
oracle.  Pairing state, TLS pinning, encryption, JPEG decoding, and receiver
storage belong to the later platform implementations.
"""

from __future__ import annotations

import hashlib
import re
import uuid
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from typing import Any

from .digest import content_digest, normalize_memo
from .schema import load_json, validate


CONTRACTS_DIR = Path(__file__).resolve().parents[1] / "contracts"

UUID_V4_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
UTC_MILLISECONDS_RE = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$")
LOCAL_DATE_RE = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
HEX_32_RE = re.compile(r"^[0-9a-f]{64}$")

MAX_MEMO_UTF8_BYTES = 8192
MAX_PHOTOS = 5
MAX_PHOTO_BYTES = 5_242_880
MAX_TOTAL_PHOTO_BYTES = 26_214_400

CONTRACT_WHITESPACE = (
    set(range(0x0009, 0x000E))
    | {0x0020, 0x0085, 0x00A0, 0x1680}
    | set(range(0x2000, 0x200B))
    | {0x2028, 0x2029, 0x202F, 0x205F, 0x3000}
)


@dataclass(frozen=True)
class Issue:
    code: str
    path: str
    message: str


@dataclass(frozen=True)
class ValidationResult:
    issues: tuple[Issue, ...]
    normalized_memo: str | None = None
    canonical_digest: str | None = None

    @property
    def valid(self) -> bool:
        return not self.issues


def _schema_issues(value: Any) -> list[Issue]:
    schema = load_json(CONTRACTS_DIR / "submission-v1.schema.json")
    return [
        Issue("INVALID_SUBMISSION", issue.path, f"schema {issue.keyword}: {issue.message}")
        for issue in validate(value, schema)
    ]


def _valid_uuid_v4(value: object) -> bool:
    if not isinstance(value, str) or UUID_V4_RE.fullmatch(value) is None:
        return False
    try:
        parsed = uuid.UUID(value)
    except ValueError:
        return False
    return parsed.version == 4 and parsed.variant == uuid.RFC_4122 and str(parsed) == value


def _valid_utc_milliseconds(value: object) -> bool:
    if not isinstance(value, str) or UTC_MILLISECONDS_RE.fullmatch(value) is None:
        return False
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%S.%fZ")
    except ValueError:
        return False
    return parsed.strftime("%Y-%m-%dT%H:%M:%S.%f")[:23] + "Z" == value


def _valid_local_date(value: object) -> bool:
    if not isinstance(value, str) or LOCAL_DATE_RE.fullmatch(value) is None:
        return False
    try:
        parsed = date.fromisoformat(value)
    except ValueError:
        return False
    return parsed.isoformat() == value


def _valid_sha256(value: object) -> bool:
    return isinstance(value, str) and HEX_32_RE.fullmatch(value) is not None


def validate_submission(
    submission: Any,
    *,
    actual_photo_bodies: Mapping[str, bytes] | None = None,
    request_submission_id: str | None = None,
    authenticated_device_id: str | None = None,
) -> ValidationResult:
    """Return deterministic schema and semantic issues for one submission."""

    issues = _schema_issues(submission)
    if not isinstance(submission, Mapping):
        return ValidationResult(tuple(issues))

    memo = submission.get("memo")
    normalized_memo: str | None = None
    if isinstance(memo, str):
        normalized_memo = normalize_memo(memo)
        if len(normalized_memo.encode("utf-8")) > MAX_MEMO_UTF8_BYTES:
            issues.append(Issue("INVALID_SUBMISSION", "$.memo", "normalized memo exceeds 8,192 UTF-8 bytes"))
        if not submission.get("photos") and all(ord(character) in CONTRACT_WHITESPACE for character in normalized_memo):
            issues.append(Issue("INVALID_SUBMISSION", "$.memo", "memo is blank under the fixed contract whitespace set"))

    if not _valid_uuid_v4(submission.get("deviceId")):
        issues.append(Issue("INVALID_SUBMISSION", "$.deviceId", "must be a lowercase canonical UUID v4"))
    if not _valid_uuid_v4(submission.get("submissionId")):
        issues.append(Issue("INVALID_SUBMISSION", "$.submissionId", "must be a lowercase canonical UUID v4"))
    if not _valid_utc_milliseconds(submission.get("createdAt")):
        issues.append(Issue("INVALID_SUBMISSION", "$.createdAt", "must be an existing UTC millisecond timestamp"))
    if not _valid_local_date(submission.get("targetDate")):
        issues.append(Issue("INVALID_SUBMISSION", "$.targetDate", "must be an existing Gregorian date"))

    if request_submission_id is not None and submission.get("submissionId") != request_submission_id:
        issues.append(Issue("INVALID_SUBMISSION", "$.submissionId", "path and metadata submission IDs differ"))
    if authenticated_device_id is not None and submission.get("deviceId") != authenticated_device_id:
        issues.append(Issue("DEVICE_UNAUTHORIZED", "$.deviceId", "body device ID differs from authenticated principal"))

    photos = submission.get("photos")
    digest_ready = isinstance(memo, str) and isinstance(photos, list)
    photo_ids: list[str] = []
    if isinstance(photos, list):
        if len(photos) > MAX_PHOTOS:
            issues.append(Issue("RESOURCE_LIMIT_EXCEEDED", "$.photos", "at most five photos are allowed"))
        total_bytes = 0
        for index, photo in enumerate(photos):
            path = f"$.photos[{index}]"
            if not isinstance(photo, Mapping):
                digest_ready = False
                continue

            photo_id = photo.get("photoId")
            if not _valid_uuid_v4(photo_id):
                issues.append(Issue("INVALID_SUBMISSION", f"{path}.photoId", "must be a lowercase canonical UUID v4"))
                digest_ready = False
            else:
                photo_ids.append(photo_id)

            if photo.get("mime") != "image/jpeg":
                issues.append(Issue("INVALID_MEDIA", f"{path}.mime", "MIME must be exactly image/jpeg"))
                digest_ready = False

            declared_bytes = photo.get("bytes")
            if not isinstance(declared_bytes, int) or isinstance(declared_bytes, bool):
                digest_ready = False
            else:
                total_bytes += declared_bytes
                if not 1 <= declared_bytes <= MAX_PHOTO_BYTES:
                    issues.append(Issue("RESOURCE_LIMIT_EXCEEDED", f"{path}.bytes", "photo bytes must be between 1 and 5 MiB"))

            if not _valid_sha256(photo.get("sha256")):
                issues.append(Issue("INVALID_SUBMISSION", f"{path}.sha256", "must be lowercase hexadecimal SHA-256"))
                digest_ready = False

        if len(photo_ids) != len(set(photo_ids)):
            issues.append(Issue("INVALID_SUBMISSION", "$.photos", "photo IDs must be unique"))
        if total_bytes > MAX_TOTAL_PHOTO_BYTES:
            issues.append(Issue("RESOURCE_LIMIT_EXCEEDED", "$.photos", "total photo bytes exceed 25 MiB"))
    else:
        digest_ready = False

    if actual_photo_bodies is not None and isinstance(photos, list):
        for index, photo in enumerate(photos):
            if not isinstance(photo, Mapping) or not isinstance(photo.get("photoId"), str):
                continue
            photo_id = photo["photoId"]
            body = actual_photo_bodies.get(photo_id)
            if body is None:
                issues.append(Issue("INVALID_MEDIA", f"$.photos[{index}]", "photo body is missing"))
                continue
            if photo.get("bytes") != len(body):
                issues.append(Issue("CONTENT_DIGEST_MISMATCH", f"$.photos[{index}].bytes", "declared bytes differ from body length"))
            if hashlib.sha256(body).hexdigest() != photo.get("sha256"):
                issues.append(Issue("CONTENT_DIGEST_MISMATCH", f"$.photos[{index}].sha256", "declared hash differs from body hash"))

    computed_digest: str | None = None
    if digest_ready:
        try:
            computed_digest = content_digest(submission)
        except (KeyError, TypeError, ValueError, OverflowError):
            pass
    if computed_digest is not None and _valid_sha256(submission.get("contentDigest")):
        if submission["contentDigest"] != computed_digest:
            issues.append(Issue("CONTENT_DIGEST_MISMATCH", "$.contentDigest", "declared digest differs from canonical digest"))

    return ValidationResult(tuple(issues), normalized_memo=normalized_memo, canonical_digest=computed_digest)
