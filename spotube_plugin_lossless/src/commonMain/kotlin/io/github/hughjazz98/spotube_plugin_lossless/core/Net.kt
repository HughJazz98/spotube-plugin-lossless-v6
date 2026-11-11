package io.github.hughjazz98.spotube_plugin_lossless.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.spotube.plugin_interfaces.host_apis.HttpClientAPI
import org.spotube.plugin_interfaces.host_apis.HttpMethod
import org.spotube.plugin_interfaces.host_apis.HttpResponse

/** Thin wrapper over Spotube's HTTP client: never throws, and reads JSON leniently. */
class Net(private val http: HttpClientAPI) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val headers = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "spotube-plugin-lossless-v6",
    )

    suspend fun get(url: String): HttpResponse? =
        runCatching { http.request(HttpMethod.Get, url, headers, null) }.getOrNull()

    suspend fun head(url: String): HttpResponse? =
        runCatching { http.request(HttpMethod.Head, url, headers, null) }.getOrNull()

    suspend fun getJson(url: String): JsonElement? {
        val res = get(url) ?: return null
        if (res.statusCode != 200) return null
        val body = res.body ?: return null
        return runCatching { json.parseToJsonElement(body) }.getOrNull()
    }

    fun parse(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()
}

fun encode(value: String): String {
    val keep = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    val out = StringBuilder()
    for (byte in value.encodeToByteArray()) {
        val c = (byte.toInt() and 0xff).toChar()
        if (keep.indexOf(c) >= 0) out.append(c)
        else {
            val hex = (byte.toInt() and 0xff).toString(16).uppercase()
            out.append('%').append(if (hex.length == 1) "0$hex" else hex)
        }
    }
    return out.toString()
}

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
fun JsonElement?.num(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

fun headerValue(res: HttpResponse, name: String): String? =
    res.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
