"""Small, strict JSON loader and JSON Schema subset used by the contract tests.

The repository's schemas use a deliberately small Draft 2020-12 vocabulary:
objects, arrays, primitive types, const/enum, required/properties,
additionalProperties, min/max limits, pattern, allOf/anyOf/oneOf, and local
``$ref``/``$defs``.  This module implements exactly those keywords so the
conformance harness can run with the Python standard library only.

``format`` and the ``x-*`` contract extensions are intentionally not treated as
schema validation.  Those rules are checked by ``conformance.semantic``.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


class DuplicateKeyError(ValueError):
    """Raised when a JSON object contains a duplicate member name."""

    def __init__(self, key: str) -> None:
        super().__init__(f"duplicate JSON object key: {key}")
        self.key = key


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKeyError(key)
        result[key] = value
    return result


def load_json(value: str | bytes | bytearray | Path) -> Any:
    """Parse JSON while rejecting duplicate object keys at every nesting level."""

    if isinstance(value, Path):
        value = value.read_bytes()
    return json.loads(value, object_pairs_hook=_reject_duplicate_keys)


@dataclass(frozen=True)
class SchemaIssue:
    path: str
    keyword: str
    message: str


def _path_for_key(path: str, key: str) -> str:
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
        return f"{path}.{key}"
    return f"{path}[{json.dumps(key, ensure_ascii=False)}]"


def _path_for_index(path: str, index: int) -> str:
    return f"{path}[{index}]"


def _type_matches(instance: Any, expected: str) -> bool:
    if expected == "object":
        return isinstance(instance, dict)
    if expected == "array":
        return isinstance(instance, list)
    if expected == "string":
        return isinstance(instance, str)
    if expected == "boolean":
        return isinstance(instance, bool)
    if expected == "null":
        return instance is None
    if expected == "integer":
        return isinstance(instance, int) and not isinstance(instance, bool)
    if expected == "number":
        return (isinstance(instance, int) and not isinstance(instance, bool)) or isinstance(instance, float)
    return True


def _resolve_local_ref(root_schema: dict[str, Any], reference: str) -> dict[str, Any]:
    if not reference.startswith("#/"):
        raise ValueError(f"only local schema references are supported: {reference}")
    value: Any = root_schema
    for component in reference[2:].split("/"):
        component = component.replace("~1", "/").replace("~0", "~")
        value = value[component]
    if not isinstance(value, dict):
        raise ValueError(f"schema reference does not resolve to an object: {reference}")
    return value


def validate(instance: Any, schema: dict[str, Any], *, root_schema: dict[str, Any] | None = None, path: str = "$") -> list[SchemaIssue]:
    """Validate an instance against the subset used by the WCT schemas."""

    if root_schema is None:
        root_schema = schema

    if "$ref" in schema:
        referenced = _resolve_local_ref(root_schema, schema["$ref"])
        return validate(instance, referenced, root_schema=root_schema, path=path)

    issues: list[SchemaIssue] = []

    if "const" in schema and instance != schema["const"]:
        issues.append(SchemaIssue(path, "const", f"must equal {schema['const']!r}"))

    if "enum" in schema and instance not in schema["enum"]:
        issues.append(SchemaIssue(path, "enum", f"must be one of {schema['enum']!r}"))

    expected_type = schema.get("type")
    if expected_type is not None:
        expected_types = expected_type if isinstance(expected_type, list) else [expected_type]
        if not any(_type_matches(instance, item) for item in expected_types):
            issues.append(SchemaIssue(path, "type", f"must be {expected_type}"))
            return issues

    if isinstance(instance, str):
        if "minLength" in schema and len(instance) < schema["minLength"]:
            issues.append(SchemaIssue(path, "minLength", f"length must be at least {schema['minLength']}"))
        if "maxLength" in schema and len(instance) > schema["maxLength"]:
            issues.append(SchemaIssue(path, "maxLength", f"length must be at most {schema['maxLength']}"))
        if "pattern" in schema and re.search(schema["pattern"], instance) is None:
            issues.append(SchemaIssue(path, "pattern", "does not match the required pattern"))

    if isinstance(instance, (int, float)) and not isinstance(instance, bool):
        if "minimum" in schema and instance < schema["minimum"]:
            issues.append(SchemaIssue(path, "minimum", f"must be at least {schema['minimum']}"))
        if "maximum" in schema and instance > schema["maximum"]:
            issues.append(SchemaIssue(path, "maximum", f"must be at most {schema['maximum']}"))

    if isinstance(instance, list):
        if "minItems" in schema and len(instance) < schema["minItems"]:
            issues.append(SchemaIssue(path, "minItems", f"must contain at least {schema['minItems']} item(s)"))
        if "maxItems" in schema and len(instance) > schema["maxItems"]:
            issues.append(SchemaIssue(path, "maxItems", f"must contain at most {schema['maxItems']} item(s)"))
        item_schema = schema.get("items")
        if isinstance(item_schema, dict):
            for index, item in enumerate(instance):
                issues.extend(validate(item, item_schema, root_schema=root_schema, path=_path_for_index(path, index)))

    if isinstance(instance, dict):
        properties = schema.get("properties", {})
        if not isinstance(properties, dict):
            raise ValueError("schema properties must be an object")

        for required_key in schema.get("required", []):
            if required_key not in instance:
                issues.append(SchemaIssue(path, "required", f"missing required property {required_key!r}"))

        if schema.get("additionalProperties") is False:
            for key in instance:
                if key not in properties:
                    issues.append(SchemaIssue(_path_for_key(path, key), "additionalProperties", "property is not allowed"))
        elif isinstance(schema.get("additionalProperties"), dict):
            additional_schema = schema["additionalProperties"]
            for key, value in instance.items():
                if key not in properties:
                    issues.extend(validate(value, additional_schema, root_schema=root_schema, path=_path_for_key(path, key)))

        for key, property_schema in properties.items():
            if key in instance:
                issues.extend(validate(instance[key], property_schema, root_schema=root_schema, path=_path_for_key(path, key)))

    for subschema in schema.get("allOf", []):
        issues.extend(validate(instance, subschema, root_schema=root_schema, path=path))

    for keyword, expected_count in (("anyOf", 1), ("oneOf", 1)):
        alternatives = schema.get(keyword)
        if alternatives is None:
            continue
        matches = 0
        for alternative in alternatives:
            if not validate(instance, alternative, root_schema=root_schema, path=path):
                matches += 1
        if (keyword == "anyOf" and matches < 1) or (keyword == "oneOf" and matches != expected_count):
            issues.append(SchemaIssue(path, keyword, f"must match {keyword} condition ({matches} alternative(s) matched)"))

    return issues
