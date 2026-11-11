import org.spotube.PluginAbility
import org.spotube.PluginCapability
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.zipline.gradle.plugin)
    alias(libs.plugins.spotubeGradle)
}

spotubePlugin {
    name = "Lossless Sources v6"
    version = "0.1.0"
    apiVersion = "0.1.0"
    description = "Lossless FLAC for Spotube 6: plays each track from Monochrome (or a working Tidal hifi-api server) using a source list that is re-checked every hour. No account, nothing to set up. Set Playback > streaming format to FLAC."
    author = "HughJazz98"
    contact = "https://github.com/HughJazz98/spotube-plugin-lossless-v6/issues"
    repository = "https://github.com/HughJazz98/spotube-plugin-lossless-v6"
    bugs = "https://github.com/HughJazz98/spotube-plugin-lossless-v6/issues"
    capabilities = listOf(
        PluginCapability.NETWORK_REQUESTS,
        PluginCapability.PERSISTENT_STORAGE
    )
    abilities = listOf(PluginAbility.AUDIO)
    cookieDomains = emptyList()
    license = "AGPL-3.0-or-later"
}

kotlin {
    applyDefaultHierarchyTemplate()

    js {
        browser()
        binaries.executable()
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.zipline.core)
            implementation(libs.spotube.plugin.interfaces)
            implementation(libs.semver)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

zipline {
    mainFunction.set("io.github.hughjazz98.spotube_plugin_lossless.main")
}

plugins.withType<YarnPlugin> {
    the<YarnRootExtension>().yarnLockAutoReplace = true
}
