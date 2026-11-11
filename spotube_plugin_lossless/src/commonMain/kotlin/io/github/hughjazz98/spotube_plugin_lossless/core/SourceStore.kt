package io.github.hughjazz98.spotube_plugin_lossless.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.spotube.plugin_interfaces.host_apis.PersistedStorageAPI
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class Source(val type: String, val base: String)

/**
 * Where the plugin looks for audio. The list is published by kipavy/spotube-plugin-lossless-sources
 * and re-checked there every hour (only servers that really served a file stay on it), so a dead
 * server drops off and a new one appears without a plugin update. Cached for six hours.
 */
class SourceStore(private val net: Net, private val storage: PersistedStorageAPI) {
    companion object {
        const val REMOTE_URL = "https://raw.githubusercontent.com/kipavy/spotube-plugin-lossless-sources/main/sources.json"
        private const val CACHE_KEY = "sources-cache"
        private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000
        val KNOWN_TYPES = setOf("monochrome", "hifi-api")
        val DEFAULTS = listOf(
            Source("monochrome", "https://tracks.monochrome.st"),
            Source("hifi-api", "https://hifi-api-w9y8.onrender.com"),
            Source("hifi-api", "https://hifi-api-bffw.onrender.com"),
        )
    }

    private val lock = Mutex()
    private var cached: List<Source>? = null
    private var cachedAt = 0L

    @OptIn(ExperimentalTime::class)
    private fun now() = Clock.System.now().toEpochMilliseconds()

    private fun clean(base: String): String = base.trim().trimEnd('/')

    private fun merge(found: List<Source>): List<Source> {
        val out = mutableListOf<Source>()
        for (s in found + DEFAULTS) {
            val c = Source(s.type.trim(), clean(s.base))
            if (c.type !in KNOWN_TYPES || !c.base.startsWith("http")) continue
            if (out.none { it == c }) out.add(c)
        }
        return out
    }

    private fun decode(text: String): List<Source> =
        text.split(",").mapNotNull { part ->
            val f = part.split("=", limit = 2)
            if (f.size == 2) Source(f[0], f[1]) else null
        }

    private fun encodeList(list: List<Source>) = list.joinToString(",") { "${it.type}=${it.base}" }

    suspend fun resolve(): List<Source> = lock.withLock {
        val mem = cached
        if (mem != null && now() - cachedAt < CACHE_TTL_MS) return@withLock mem

        val saved = runCatching { storage.getString(CACHE_KEY) }.getOrNull()
        val parts = saved?.split("|", limit = 2)
        val savedAt = parts?.getOrNull(0)?.toLongOrNull() ?: 0L
        val savedList = parts?.getOrNull(1)?.let { decode(it) }.orEmpty()
        if (savedList.isNotEmpty() && now() - savedAt in 0 until CACHE_TTL_MS) {
            return@withLock merge(savedList).also { cached = it; cachedAt = savedAt }
        }

        val remote = net.getJson(REMOTE_URL)["sources"].arr()?.mapNotNull { e ->
            val type = e["type"].str()
            val base = e["base"].str()
            if (type != null && base != null) Source(type, base) else null
        }.orEmpty()

        val list = when {
            remote.isNotEmpty() -> merge(remote).also {
                runCatching { storage.putString(CACHE_KEY, "${now()}|${encodeList(it)}") }
            }
            savedList.isNotEmpty() -> merge(savedList)       // GitHub unreachable: an old list beats none
            else -> merge(emptyList())
        }
        cached = list
        cachedAt = now()
        list
    }
}
