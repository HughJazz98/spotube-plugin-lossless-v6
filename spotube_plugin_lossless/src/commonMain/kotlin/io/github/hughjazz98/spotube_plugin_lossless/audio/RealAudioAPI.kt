package io.github.hughjazz98.spotube_plugin_lossless.audio

import io.github.hughjazz98.spotube_plugin_lossless.core.Net
import io.github.hughjazz98.spotube_plugin_lossless.core.SourceStore
import org.spotube.plugin_interfaces.extras.logger.Logger
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioAPI
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioFormat
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioQuality
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioSource
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.Thumbnail
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

/**
 * Asks each lossless source in the published order (Monochrome first) and takes the first one that
 * has this recording at the right length. Source ids are "<type>|<ref>|<durationMs>" so the stream
 * step knows where to go back to.
 */
class RealAudioAPI(net: Net, private val store: SourceStore) : AudioAPI {
    private val logger = Logger("LosslessSources")
    private val sources: List<LosslessSource> = listOf(Monochrome(net), HifiApi(net))

    override val supportedQualities: List<AudioFormat> = listOf(
        AudioFormat(
            codec = "flac",
            container = "flac",
            qualities = listOf(
                AudioQuality.Lossless(sampleRate = 96_000, channels = 2),
                AudioQuality.Lossless(sampleRate = 44_100, channels = 2),
            )
        ),
        AudioFormat(
            codec = "aac",
            container = "mp4",
            qualities = listOf(AudioQuality.Lossy(bitrate = 320)),
        ),
    )

    private suspend fun basesByType(): List<Pair<LosslessSource, List<String>>> {
        val list = store.resolve()
        val order = list.map { it.type }.distinct()
        return order.mapNotNull { type ->
            val src = sources.firstOrNull { it.type == type } ?: return@mapNotNull null
            src to list.filter { it.type == type }.map { it.base }
        }
    }

    override suspend fun getStreamsByTrack(track: MetadataTrack): List<AudioSource> {
        if (track.title.isBlank()) return emptyList()
        val query = searchText(track)
        for ((src, bases) in basesByType()) {
            val found = runCatching { src.matches(track, query, bases) }.getOrElse { emptyList() }
            val fitting = plausible(found, track).filter { sameLength(it, track) }
            if (fitting.isEmpty()) continue
            logger.i { "'${track.title}': ${fitting.size} match(es) on ${src.type}" }
            return fitting.take(5).mapIndexed { i, m ->
                AudioSource.Basic(
                    id = "${m.source}|${m.ref}|${m.durationMs}",
                    title = m.title,
                    artist = m.artists.joinToString(", ").ifBlank { null },
                    album = m.album,
                    thumbnails = m.artwork?.let { listOf(Thumbnail(url = it, width = 640, height = 640)) }.orEmpty(),
                    externalUri = m.externalUri,
                    confidence = if (i == 0) 0.95f else 0.8f,
                )
            }
        }
        logger.w { "'${track.title}': no lossless source has it" }
        return emptyList()
    }

    override suspend fun getStreamsOfAudioSource(source: AudioSource.Basic): List<AudioSource.Streamed> {
        val parts = source.id.split("|")
        if (parts.size < 2) return emptyList()
        val type = parts[0]
        val ref = parts[1]
        val durationMs = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val src = sources.firstOrNull { it.type == type } ?: return emptyList()
        val bases = store.resolve().filter { it.type == type }.map { it.base }
        val streams = runCatching { src.streams(ref, durationMs, bases) }.getOrElse { emptyList() }
        if (streams.isEmpty()) {
            logger.w { "no stream for ${source.id}" }
            return emptyList()
        }
        return listOf(
            AudioSource.Streamed(
                id = source.id,
                title = source.title,
                artist = source.artist,
                album = source.album,
                thumbnails = source.thumbnails,
                externalUri = source.externalUri,
                confidence = source.confidence,
                streams = streams,
            )
        )
    }
}
