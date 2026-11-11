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

/** A real artist name: some metadata plugins put the track length ("5:16") where the artist goes. */
fun realName(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    val t = name.trim()
    if (t.all { it.isDigit() || it == ':' || it == '.' || it == ' ' }) return false
    return true
}

/** The track's artists, falling back to the album's artists when the track's own are missing or bogus. */
fun artistNames(track: MetadataTrack): List<String> {
    val own = track.artists.map { it.name }.filter { realName(it) }
    if (own.isNotEmpty()) return own
    return track.album?.artists?.map { it.name }?.filter { realName(it) }.orEmpty()
}

/** Same song title: equal once simplified, or one starts with the other ("Walk" / "Walk (Remastered)"). */
fun sameTitle(a: String, b: String): Boolean {
    val x = simplify(a)
    val y = simplify(b)
    if (x.isEmpty() || y.isEmpty()) return false
    return x == y || x.startsWith("$y ") || y.startsWith("$x ")
}

/**
 * Keeps results that are really this track: an exact ISRC hit always counts; otherwise it must be
 * credited to one of the track's artists (searches return covers next to the original). When the
 * metadata has no usable artist, the title has to match instead. ISRC hits go first.
 */
fun plausible(found: List<Match>, track: MetadataTrack): List<Match> {
    val wanted = artistNames(track).map { simplify(it) }.filter { it.isNotEmpty() }
    val isrc = track.isrcCode?.uppercase()?.takeIf { it.isNotBlank() }
    val exact = found.filter { isrc != null && it.isrc?.uppercase() == isrc }
    val rest = found.filter { it !in exact }
    val credited = if (wanted.isEmpty()) {
        rest.filter { sameTitle(it.title, track.title) }
    } else {
        rest.filter { m ->
            m.artists.any { credit ->
                val c = simplify(credit)
                c.isNotEmpty() && wanted.any { w -> c.contains(w) || w.contains(c) }
            }
        }
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

/** What to search for, best first: "title artists", then "title album", then the title alone. */
fun searchTexts(track: MetadataTrack): List<String> {
    val out = mutableListOf<String>()
    val names = artistNames(track)
    if (names.isNotEmpty()) out.add("${track.title} ${names.joinToString(", ")}")
    val album = track.album?.title
    if (!album.isNullOrBlank() && !sameTitle(album, track.title)) out.add("${track.title} $album")
    out.add(track.title)
    return out.distinct()
}
