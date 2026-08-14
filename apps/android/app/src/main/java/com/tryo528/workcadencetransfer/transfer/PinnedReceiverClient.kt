package com.tryo528.workcadencetransfer.transfer

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ReceiverException(message: String, val retryable: Boolean = false) : IllegalStateException(message)

class PinnedReceiverClient(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000
) {
    fun enroll(pairing: PairingQr, clientPlatform: String = "android"): Enrollment {
        SemanticValidator.validatePairing(pairing)
        EndpointPolicy.validate(pairing.endpoint)
        val pin = EndpointPolicy.decodeSpki(pairing.spkiSha256)
        val body = "{\"version\":1,\"type\":\"transfer_enrollment_request\",\"clientPlatform\":\"$clientPlatform\"}"
        val response = postJson(
            endpoint = pairing.endpoint,
            spki = pin,
            path = "/v1/enrollments/${pairing.enrollmentId}/complete",
            authorization = "WCTEnrollment ${pairing.enrollmentSecret}",
            body = body.toByteArray(Charsets.UTF_8)
        )
        if (response.status != HttpURLConnection.HTTP_OK) throw ReceiverException("enrollment failed: HTTP ${response.status}")
        requireJsonResponse(response)
        val result = JsonContracts.enrollmentResultFromJson(response.body.toString(Charsets.UTF_8))
        SemanticValidator.validateEnrollmentResult(result)
        return Enrollment(pairing.endpoint, pairing.spkiSha256, result.deviceId, result.deviceSecret, result.scope)
    }

    fun submit(enrollment: Enrollment, pending: PendingSubmission): ReadyAck {
        SemanticValidator.validateEnrollment(enrollment)
        EndpointPolicy.validate(enrollment.endpoint)
        val pin = EndpointPolicy.decodeSpki(enrollment.spkiSha256)
        SemanticValidator.validatePending(pending)
        require(pending.metadata.deviceId == enrollment.deviceId) { "pending device ID differs from enrollment" }

        val boundary = "WCT-${UUID.randomUUID().toString().replace("-", "")}"
        val response = postMultipart(
            endpoint = enrollment.endpoint,
            spki = pin,
            path = "/v1/submissions/${pending.metadata.submissionId}",
            authorization = "WCTDevice ${enrollment.deviceId}.${enrollment.deviceSecret}",
            boundary = boundary,
            pending = pending
        )
        if (response.status != HttpURLConnection.HTTP_OK) {
            throw ReceiverException("submission failed: HTTP ${response.status}", response.status >= 500)
        }
        requireJsonResponse(response)
        val ack = JsonContracts.readyAckFromJson(response.body.toString(Charsets.UTF_8))
        SemanticValidator.validateReadyAck(ack, pending)
        return ack
    }

    private fun postJson(endpoint: String, spki: ByteArray, path: String, authorization: String, body: ByteArray): HttpResult {
        val connection = open(endpoint, spki, path)
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", authorization)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        return read(connection)
    }

    private fun postMultipart(
        endpoint: String,
        spki: ByteArray,
        path: String,
        authorization: String,
        boundary: String,
        pending: PendingSubmission
    ): HttpResult {
        val connection = open(endpoint, spki, path)
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", authorization)
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.doOutput = true
        connection.setChunkedStreamingMode(16 * 1024)
        connection.outputStream.use { output ->
            fun write(text: String) = output.write(text.toByteArray(Charsets.UTF_8))
            write("--$boundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n")
            write(JsonContracts.submissionToJson(pending.metadata))
            write("\r\n")
            pending.photos.forEach { photo ->
                write("--$boundary\r\n")
                write("Content-Disposition: form-data; name=\"photo\"; filename=\"ignored.jpg\"\r\n")
                write("Content-Type: image/jpeg\r\n")
                write("Content-ID: <${photo.photoId}>\r\n\r\n")
                output.write(photo.bytes)
                write("\r\n")
            }
            write("--$boundary--\r\n")
        }
        return read(connection)
    }

    private fun open(endpoint: String, spki: ByteArray, path: String): HttpsURLConnection {
        val url = URL(endpoint.trimEnd('/') + path)
        val connection = url.openConnection() as HttpsURLConnection
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.instanceFollowRedirects = false
        connection.sslSocketFactory = PinnedTls.socketFactory(spki)
        connection.hostnameVerifier = HostnameVerifier { host, _ -> host == url.host && EndpointPolicy.isPrivateIpv4(host) }
        return connection
    }

    private fun read(connection: HttpURLConnection): HttpResult {
        val status = connection.responseCode
        val contentType = connection.getHeaderField("Content-Type")
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.use { readAtMost(it, 64 * 1024) } ?: ByteArray(0)
        connection.disconnect()
        return HttpResult(status, contentType, body)
    }

    private fun requireJsonResponse(response: HttpResult) {
        val mediaType = response.contentType?.substringBefore(';')?.trim()?.lowercase()
        require(mediaType == "application/json") { "receiver response must be application/json" }
    }

    private fun readAtMost(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return output.toByteArray()
            if (output.size() + read > limit) throw ReceiverException("response exceeds limit")
            output.write(buffer, 0, read)
        }
    }

    private data class HttpResult(val status: Int, val contentType: String?, val body: ByteArray)
}

private object EndpointPolicy {
    private val ipv4 = Regex("^(\\d{1,3})(\\.\\d{1,3}){3}$")

    fun validate(endpoint: String) {
        val url = URL(endpoint)
        require(url.protocol == "https") { "endpoint must use HTTPS" }
        require(isPrivateIpv4(url.host)) { "endpoint must be RFC1918 IPv4" }
        require(url.port in 1024..65535) { "endpoint port is outside the allowed range" }
        require(url.path.isEmpty() || url.path == "/") { "endpoint path is not allowed" }
        require(url.query == null && url.ref == null && url.userInfo == null) { "endpoint extras are not allowed" }
    }

    fun isPrivateIpv4(host: String): Boolean {
        if (!ipv4.matches(host)) return false
        val octets = host.split('.').map(String::toInt)
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    fun decodeSpki(value: String): ByteArray {
        val decoded = Base64.getUrlDecoder().decode(value)
        require(decoded.size == 32) { "SPKI pin must be 32 bytes" }
        return decoded
    }
}

private object PinnedTls {
    fun socketFactory(expectedSpki: ByteArray): SSLSocketFactory {
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("empty server certificate chain")
                chain[0].checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].publicKey.encoded)
                if (!actual.contentEquals(expectedSpki)) throw CertificateException("server SPKI pin mismatch")
            }
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }.socketFactory
    }
}
