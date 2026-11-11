package io.github.hughjazz98.spotube_plugin_lossless.audio

import io.github.hughjazz98.spotube_plugin_lossless.core.Net
import io.github.hughjazz98.spotube_plugin_lossless.core.arr
import io.github.hughjazz98.spotube_plugin_lossless.core.bool
import io.github.hughjazz98.spotube_plugin_lossless.core.encode
import io.github.hughjazz98.spotube_plugin_lossless.core.get
import io.github.hughjazz98.spotube_plugin_lossless.core.headerValue
import io.github.hughjazz98.spotube_plugin_lossless.core.num
import io.github.hughjazz98.spotube_plugin_lossless.core.str
import kotlinx.coroutines.delay
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioStream
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

/**
 * Monochrome's own catalogue (it left hifi-api in April 2026 and serves the files itself):
 * GET /search/tracks?q= -> { tracks: [{ id, title, artistNames, duration (ms), artwork, isrc, playable }] }
 * GET /track/<id>       -> the FLAC file itself (no manifest, no expiring link).
 */
class Monochrome(private val net: Net) : LosslessSource {
    companion object {
        private val RETRY_SECONDS = listOf(1L, 2L, 4L)          // the host sits behind Cloudflare; a 521 usually clears
        private const val CD_PCM = 1_411_200L                   // 16-bit/44.1 kHz stereo
        private const val HIRES_PCM = 2_116_800L                // 24-bit/44.1 kHz stereo
    }

    override val type = "monochrome"

    override suspend fun matches(track: MetadataTrack, query: String, bases: List<String>): List<Match> {
        for (base in bases) {
            val items = net.getJson("$base/search/tracks?q=${encode(query)}&limit=20")["tracks"].arr() ?: continue
            val found = items.mapNotNull { item ->
                if (item["playable"].bool() == false) return@mapNotNull null
                val id = item["id"].str() ?: item["id"].num()?.toString() ?: return@mapNotNull null
                Match(
                    source = type,
                    ref = id,
                    title = item["title"].str() ?: "",
                    artists = item["artistNames"].arr()?.mapNotNull { it.str() }?.filter { it.isNotBlank() }.orEmpty(),
                    album = null,
                    durationMs = item["duration"].num() ?: 0L,
                    isrc = item["isrc"].str(),
                    artwork = item["artwork"].str(),
                    externalUri = "$base/track/$id",
                )
            }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    override suspend fun streams(ref: String, durationMs: Long, bases: List<String>): List<AudioStream> {
        for (base in bases) {
            val url = "$base/track/$ref"
            var attempt = 0
            while (true) {
                val res = net.head(url)
                val code = res?.statusCode ?: 0
                if (res != null && (code == 200 || code == 206)) {
                    val size = headerValue(res, "content-length")?.trim()?.toLongOrNull() ?: 0L
                    // The host sends no quality info, so it is worked out from the file's average bitrate.
                    val bitrate = if (size > 0 && durationMs > 0) size * 8 * 1000 / durationMs else CD_PCM
                    return listOf(
                        AudioStream.Lossless(
                            url = url,
                            codec = "flac",
                            container = "flac",
                            sampleRate = if (bitrate > HIRES_PCM) 96_000 else 44_100,
                            channels = 2,
                        )
                    )
                }
                // 4xx: this host doesn't have it. 5xx or no answer: wait and retry a few times.
                if ((code >= 500 || code == 0) && attempt < RETRY_SECONDS.size) {
                    delay(RETRY_SECONDS[attempt] * 1000)
                    attempt++
                    continue
                }
                break
            }
        }
        return emptyList()
    }
}
