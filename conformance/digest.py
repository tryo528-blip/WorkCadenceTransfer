"""Reference implementation of the WorkCadenceTransfer v1 content digest.

The functions in this module are deliberately small and dependency-free.  They
are a conformance oracle for the Android, iPhone, and Windows implementations;
they are not the production encryption or upload code.
"""

from __future__ import annotations

import hashlib
import struct
import unicodedata
from collections.abc import Mapping, Sequence


PROTOCOL_MAGIC = b"WCT1"
PROTOCOL_VERSION = 1


def normalize_memo(memo: str) -> str:
    """Apply the contract's CR/LF normalization followed by Unicode NFC."""

    return unicodedata.normalize("NFC", memo.replace("\r\n", "\n").replace("\r", "\n"))


def length_prefix(value: str) -> bytes:
    """Encode a UTF-8 string as the contract's u32 big-endian LP value."""

    encoded = value.encode("utf-8")
    if len(encoded) > 0xFFFFFFFF:
        raise ValueError("length-prefixed value is too large")
    return struct.pack(">I", len(encoded)) + encoded


def canonical_bytes(submission: Mapping[str, object]) -> bytes:
    """Return the exact canonical byte sequence described by protocol v1."""

    photos = submission["photos"]
    if not isinstance(photos, Sequence) or isinstance(photos, (str, bytes, bytearray)):
        raise TypeError("photos must be a sequence")
    if len(photos) > 255:
        raise ValueError("photo count does not fit in u8")

    output = bytearray(PROTOCOL_MAGIC)
    output.append(PROTOCOL_VERSION)
    output.extend(length_prefix(str(submission["deviceId"])))
    output.extend(length_prefix(str(submission["submissionId"])))
    output.extend(length_prefix(str(submission["createdAt"])))
    output.extend(length_prefix(str(submission["targetDate"])))
    output.extend(length_prefix(normalize_memo(str(submission["memo"]))))
    output.append(len(photos))

    for photo in photos:
        if not isinstance(photo, Mapping):
            raise TypeError("each photo must be an object")
        output.extend(length_prefix(str(photo["photoId"])))
        output.extend(length_prefix(str(photo["mime"])))
        output.extend(struct.pack(">Q", int(photo["bytes"])))
        output.extend(bytes.fromhex(str(photo["sha256"])))

    return bytes(output)


def content_digest(submission: Mapping[str, object]) -> str:
    """Return the lowercase SHA-256 hex digest of :func:`canonical_bytes`."""

    return hashlib.sha256(canonical_bytes(submission)).hexdigest()
