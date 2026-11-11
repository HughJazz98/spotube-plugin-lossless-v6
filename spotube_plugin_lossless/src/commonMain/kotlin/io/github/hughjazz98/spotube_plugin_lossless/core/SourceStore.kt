package io.github.hughjazz98.spotube_plugin_lossless.core

import kotlinx.coroutines.sync.Mutex
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

    suspend fun resolve(): List<Source> {
        lock.lock()
        try {
            return load()
        } finally {
            lock.unlock()
        }
    }

    // Kept free of returns from inside lambdas / try blocks: Zipline's QuickJS (2021) can't compile some of those.
    private suspend fun load(): List<Source> {
        val mem = cached
        if (mem != null && now() - cachedAt < CACHE_TTL_MS) return mem

        val saved: String? = try { storage.getString(CACHE_KEY) } catch (e: Throwable) { null }
        val parts = saved?.split("|", limit = 2)
        val savedAt = parts?.getOrNull(0)?.toLongOrNull() ?: 0L
        val savedList = parts?.getOrNull(1)?.let { decode(it) }.orEmpty()
        val age = now() - savedAt
        if (savedList.isNotEmpty() && age >= 0 && age < CACHE_TTL_MS) {
            val fromCache = merge(savedList)
            cached = fromCache
            cachedAt = savedAt
            return fromCache
        }

        val remote = readRemote()
        val list: List<Source>
        if (remote.isNotEmpty()) {
            list = merge(remote)
            try { storage.putString(CACHE_KEY, "${now()}|${encodeList(list)}") } catch (e: Throwable) { }
        } else if (savedList.isNotEmpty()) {
            list = merge(savedList)                         // GitHub unreachable: an old list beats none
        } else {
            list = merge(emptyList())
        }
        cached = list
        cachedAt = now()
        return list
    }

    private suspend fun readRemote(): List<Source> {
        val items = net.getJson(REMOTE_URL)["sources"].arr() ?: return emptyList()
        val out = mutableListOf<Source>()
        for (e in items) {
            val type = e["type"].str()
            val base = e["base"].str()
            if (type != null && base != null) out.add(Source(type, base))
        }
        return out
    }
}
