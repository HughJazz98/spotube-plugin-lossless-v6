package io.github.hughjazz98.spotube_plugin_lossless.audio

import io.github.hughjazz98.spotube_plugin_lossless.core.Net
import io.github.hughjazz98.spotube_plugin_lossless.core.arr
import io.github.hughjazz98.spotube_plugin_lossless.core.encode
import io.github.hughjazz98.spotube_plugin_lossless.core.get
import io.github.hughjazz98.spotube_plugin_lossless.core.num
import io.github.hughjazz98.spotube_plugin_lossless.core.str
import kotlinx.coroutines.delay
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioStream
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Tidal through a hifi-api server: GET /search/?s= -> { data: { items } }, GET /track/?id=&quality= ->
 * { data: { manifestMimeType, manifest (base64), audioQuality, bitDepth, sampleRate } }. Only the
 * "application/vnd.tidal.bts" manifest is a plain file link; Hi-Res DASH manifests are skipped.
 */
class HifiApi(private val net: Net) : LosslessSource {
    companion object {
        private val QUALITIES = listOf("HI_RES_LOSSLESS", "LOSSLESS", "HIGH")
        private val BUSY_SECONDS = listOf(2L, 4L, 8L)            // 202 = all of the server's accounts are busy
    }

    override val type = "hifi-api"

    private fun cover(uuid: String?): String? = when {
        uuid.isNullOrBlank() -> null
        uuid.startsWith("http") -> uuid
        else -> "https://resources.tidal.com/images/${uuid.replace('-', '/')}/640x640.jpg"
    }

    override suspend fun matches(track: MetadataTrack, query: String, bases: List<String>): List<Match> {
        for (base in bases) {
            val items = net.getJson("$base/search/?s=${encode(query)}")["data"]["items"].arr() ?: continue
            val found = items.mapNotNull { item ->
                val id = item["id"].num()?.toString() ?: item["id"].str() ?: return@mapNotNull null
                val names = mutableListOf<String>()
                item["artist"]["name"].str()?.let { names.add(it) }
                item["artists"].arr()?.forEach { a -> a["name"].str()?.let { if (it !in names) names.add(it) } }
                val version = item["version"].str()
                Match(
                    source = type,
                    ref = id,
                    title = (item["title"].str() ?: "") + if (!version.isNullOrBlank()) " ($version)" else "",
                    artists = names,
                    album = item["album"]["title"].str(),
                    durationMs = (item["duration"].num() ?: 0L) * 1000,
                    isrc = item["isrc"].str(),
                    artwork = cover(item["album"]["cover"].str()),
                    externalUri = "https://tidal.com/browse/track/$id",
                )
            }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decode(manifest: String): Pair<String, String>? = runCatching {
        val text = Base64.decode(manifest).decodeToString()
        val parsed = net.parse(text)
        val url = parsed["urls"].arr()?.firstOrNull().str() ?: return null
        val codec = parsed["codecs"].str() ?: ""
        url to codec
    }.getOrNull()

    override suspend fun streams(ref: String, durationMs: Long, bases: List<String>): List<AudioStream> {
        val out = mutableListOf<AudioStream>()
        for (base in bases) {
            for (quality in QUALITIES) {
                var attempt = 0
                while (true) {
                    val res = net.get("$base/track/?id=$ref&quality=$quality")
                    if (res?.statusCode == 202 && attempt < BUSY_SECONDS.size) {
                        delay(BUSY_SECONDS[attempt] * 1000); attempt++; continue
                    }
                    val data = res?.takeIf { it.statusCode == 200 }?.body?.let { net.parse(it) }?.get("data")
                    if (data != null && data["manifestMimeType"].str() == "application/vnd.tidal.bts") {
                        val manifest = data["manifest"].str()
                        val decoded = manifest?.let { decode(it) }
                        if (decoded != null && out.none { it.url == decoded.first }) {
                            val codec = decoded.second.lowercase()
                            out.add(
                                if (codec.contains("flac")) AudioStream.Lossless(
                                    url = decoded.first, codec = "flac", container = "flac",
                                    sampleRate = (data["sampleRate"].num() ?: 44_100L).toInt(), channels = 2,
                                ) else AudioStream.Lossy(
                                    url = decoded.first, codec = "aac", container = "mp4", bitrate = 320,
                                )
                            )
                        }
                    }
                    break
                }
            }
            if (out.isNotEmpty()) return out
        }
        return out
    }
}
