package com.tryo528.workcadencetransfer.transfer

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader

object JsonContracts {
    fun pairingQrFromJson(json: String): PairingQr {
        val objectValue = strictObject(json)
        requireKeys(objectValue, setOf("version", "type", "endpoint", "spkiSha256", "enrollmentId", "enrollmentSecret", "expiresAt"))
        return PairingQr(
            version = objectValue.getInt("version"),
            type = objectValue.getString("type"),
            endpoint = objectValue.getString("endpoint"),
            spkiSha256 = objectValue.getString("spkiSha256"),
            enrollmentId = objectValue.getString("enrollmentId"),
            enrollmentSecret = objectValue.getString("enrollmentSecret"),
            expiresAt = objectValue.getString("expiresAt")
        )
    }

    fun enrollmentResultFromJson(json: String): EnrollmentResult {
        val value = strictObject(json)
        requireKeys(value, setOf("version", "type", "deviceId", "deviceSecret", "scope", "issuedAt"))
        return EnrollmentResult(
            value.getInt("version"),
            value.getString("type"),
            value.getString("deviceId"),
            value.getString("deviceSecret"),
            value.getString("scope"),
            value.getString("issuedAt")
        )
    }

    fun readyAckFromJson(json: String): ReadyAck {
        val value = strictObject(json)
        requireKeys(value, setOf("version", "type", "accepted", "state", "deviceId", "submissionId", "contentDigest", "recordId", "storedAt"))
        return ReadyAck(
            version = value.getInt("version"),
            type = value.getString("type"),
            accepted = value.getBoolean("accepted"),
            state = value.getString("state"),
            deviceId = value.getString("deviceId"),
            submissionId = value.getString("submissionId"),
            contentDigest = value.getString("contentDigest"),
            recordId = value.getString("recordId"),
            storedAt = value.getString("storedAt")
        )
    }

    fun submissionToJson(metadata: SubmissionMetadata): String {
        val value = JSONObject()
        value.put("version", metadata.version)
        value.put("type", metadata.type)
        value.put("deviceId", metadata.deviceId)
        value.put("submissionId", metadata.submissionId)
        value.put("createdAt", metadata.createdAt)
        value.put("targetDate", metadata.targetDate)
        value.put("contentDigest", metadata.contentDigest)
        value.put("memo", metadata.memo)
        val photos = JSONArray()
        metadata.photos.forEach { photo ->
            photos.put(JSONObject().apply {
                put("photoId", photo.photoId)
                put("mime", photo.mime)
                put("bytes", photo.bytes)
                put("sha256", photo.sha256)
            })
        }
        value.put("photos", photos)
        return value.toString()
    }

    fun enrollmentToJson(enrollment: Enrollment): String = JSONObject().apply {
        put("endpoint", enrollment.endpoint)
        put("spkiSha256", enrollment.spkiSha256)
        put("deviceId", enrollment.deviceId)
        put("deviceSecret", enrollment.deviceSecret)
        put("scope", enrollment.scope)
    }.toString()

    fun enrollmentFromJson(json: String): Enrollment {
        val value = strictObject(json)
        requireKeys(value, setOf("endpoint", "spkiSha256", "deviceId", "deviceSecret", "scope"))
        return Enrollment(
            endpoint = value.getString("endpoint"),
            spkiSha256 = value.getString("spkiSha256"),
            deviceId = value.getString("deviceId"),
            deviceSecret = value.getString("deviceSecret"),
            scope = value.getString("scope")
        )
    }

    fun submissionFromJson(json: String): SubmissionMetadata {
        val value = strictObject(json)
        requireKeys(value, setOf("version", "type", "deviceId", "submissionId", "createdAt", "targetDate", "contentDigest", "memo", "photos"))
        val array = value.getJSONArray("photos")
        val photos = buildList(array.length()) {
            for (index in 0 until array.length()) {
                val photo = array.getJSONObject(index)
                requireKeys(photo, setOf("photoId", "mime", "bytes", "sha256"))
                add(PhotoMetadata(
                    photoId = photo.getString("photoId"),
                    mime = photo.getString("mime"),
                    bytes = photo.getLong("bytes"),
                    sha256 = photo.getString("sha256")
                ))
            }
        }
        return SubmissionMetadata(
            version = value.getInt("version"),
            type = value.getString("type"),
            deviceId = value.getString("deviceId"),
            submissionId = value.getString("submissionId"),
            createdAt = value.getString("createdAt"),
            targetDate = value.getString("targetDate"),
            contentDigest = value.getString("contentDigest"),
            memo = value.getString("memo"),
            photos = photos
        )
    }

    private fun strictObject(json: String): JSONObject {
        StrictJson.rejectDuplicateKeys(json)
        return JSONObject(json)
    }

    private fun requireKeys(value: JSONObject, expected: Set<String>) {
        val actual = value.keys().asSequence().toSet()
        require(actual == expected) { "JSON keys differ: expected=$expected actual=$actual" }
    }
}

private object StrictJson {
    fun rejectDuplicateKeys(json: String) {
        val reader = JsonReader(StringReader(json))
        reader.isLenient = false
        walk(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT) { "trailing JSON data" }
    }

    private fun walk(reader: JsonReader) {
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val names = mutableSetOf<String>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(names.add(name)) { "duplicate JSON key: $name" }
                    walk(reader)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) walk(reader)
                reader.endArray()
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> error("invalid JSON token: ${reader.peek()}")
        }
    }
}
