package io.github.hughjazz98.spotube_plugin_lossless.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.swiftzer.semver.SemVer
import org.spotube.plugin_interfaces.plugin_apis.core.CoreAPI
import org.spotube.plugin_interfaces.plugin_apis.core.PluginUpdateInfo

/** Nothing to log into. Updates come from this repository's GitHub releases. */
class RealCoreAPI(private val net: Net) : CoreAPI {
    companion object {
        private const val LATEST =
            "https://api.github.com/repos/HughJazz98/spotube-plugin-lossless-v6/releases/latest"
    }

    override val requiresAuthentication: Boolean = false
    private val loggedIn = MutableStateFlow(true)
    override val loggedInFlow: StateFlow<Boolean> = loggedIn.asStateFlow()

    override suspend fun checkPluginUpdates(currentVersion: SemVer): PluginUpdateInfo? {
        val release = net.getJson(LATEST) ?: return null
        val tag = release["tag_name"].str()?.removePrefix("v") ?: return null
        val latest = runCatching { SemVer.parse(tag) }.getOrNull() ?: return null
        if (latest <= currentVersion) return null
        val url = release["assets"].arr()
            ?.firstOrNull { it["name"].str()?.endsWith(".smplug") == true }
            ?.get("browser_download_url").str() ?: return null
        return PluginUpdateInfo(latestVersion = tag, directDownloadUrl = url, releaseNotes = release["body"].str() ?: "")
    }

    override fun supportMarkdownText(currentVersion: SemVer): String = """
        **Lossless Sources v6** plays tracks as FLAC from Monochrome, or from a Tidal hifi-api server when one works.

        - Set **Settings → Playback → streaming format** to **FLAC**.
        - Tracks no lossless source has won't play with this plugin; switch the audio plugin back to YouTube for those.
        - Source list: kipavy/spotube-plugin-lossless-sources (re-checked hourly).
    """.trimIndent()

    override suspend fun login() {}

    override suspend fun logout() {}
}
