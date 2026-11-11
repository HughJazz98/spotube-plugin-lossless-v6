package io.github.hughjazz98.spotube_plugin_lossless.audio

import org.spotube.plugin_interfaces.plugin_apis.audio.AudioStream
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

/** One candidate recording from a source. [ref] is what that source needs to fetch the file. */
data class Match(
    val source: String,
    val ref: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long,
    val isrc: String?,
    val artwork: String?,
    val externalUri: String?,
)

interface LosslessSource {
    val type: String
    suspend fun matches(track: MetadataTrack, query: String, bases: List<String>): List<Match>
    suspend fun streams(ref: String, durationMs: Long, bases: List<String>): List<AudioStream>
}

private const val KEEP = "abcdefghijklmnopqrstuvwxyz0123456789 "

/** "Beyoncé" and "Beyonce" compare equal; punctuation is dropped. */
fun simplify(value: String?): String {
    if (value == null) return ""
    val lower = value.lowercase()
        .replace('é', 'e').replace('è', 'e').replace('ê', 'e').replace('ë', 'e')
        .replace('á', 'a').replace('à', 'a').replace('â', 'a').replace('ä', 'a').replace('ã', 'a')
        .replace('í', 'i').replace('ï', 'i').replace('ó', 'o').replace('ö', 'o').replace('ô', 'o')
        .replace('ú', 'u').replace('ü', 'u').replace('ñ', 'n').replace('ç', 'c')
    return lower.filter { KEEP.indexOf(it) >= 0 }.trim().split(" ").filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * Keeps results that are really this track: an exact ISRC hit always counts, anything else must be
 * credited to one of the track's artists (searches return covers next to the original). ISRC hits go first.
 */
fun plausible(found: List<Match>, track: MetadataTrack): List<Match> {
    val wanted = track.artists.map { simplify(it.name) }.filter { it.isNotEmpty() }
    val isrc = track.isrcCode?.uppercase()?.takeIf { it.isNotBlank() }
    val exact = found.filter { isrc != null && it.isrc?.uppercase() == isrc }
    val credited = found.filter { m ->
        m !in exact && (wanted.isEmpty() || m.artists.any { credit ->
            val c = simplify(credit)
            c.isNotEmpty() && wanted.any { w -> c.contains(w) || w.contains(c) }
        })
    }
    return exact + credited
}

/**
 * Same length as the track: 15 s or 10 %, whichever is more. An ISRC hit is the recording itself and
 * only fails when it is off by a factor of two (a catalogue error). Unknown lengths pass.
 */
fun sameLength(m: Match, track: MetadataTrack): Boolean {
    val wanted = track.durationMs
    val actual = m.durationMs
    if (wanted <= 0 || actual <= 0) return true
    val isrc = track.isrcCode?.uppercase()
    if (!isrc.isNullOrBlank() && m.isrc?.uppercase() == isrc) return actual < wanted * 2 && actual * 2 > wanted
    val slack = maxOf(15_000L, wanted / 10)
    val gap = if (actual > wanted) actual - wanted else wanted - actual
    return gap <= slack
}

fun searchText(track: MetadataTrack): String {
    val names = track.artists.map { it.name }.filter { it.isNotBlank() }
    return if (names.isEmpty()) track.title else "${track.title} ${names.joinToString(", ")}"
}
