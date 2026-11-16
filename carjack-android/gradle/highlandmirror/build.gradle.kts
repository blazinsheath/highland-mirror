// A separate, public-API screen mirror. No Shizuku, ADB, shell service or privileged launcher.
plugins {
    alias(libs.plugins.android.application)
}

val app = rootDir.resolve("app")
val transport = listOf(
    "AccessoryWifi", "AudioOutputSettings", "AudioStream", "Bluetooth", "Cues", "Fingers",
    "Native", "NavBar", "Relay", "ScreenStream", "Session", "SlipstreamService", "Touch",
)
val transportSources = layout.buildDirectory.dir("generated/mirror-transport")
val prepareTransport = tasks.register<Sync>("prepareMirrorTransport") {
    from(app.resolve("src")) { include(transport.map { "**/$it.kt" }) }
    into(transportSources)
}

android {
    namespace = "io.github.cfraz89.slipstream"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "io.github.blazinsheath.highlandmirror"
        minSdk = 34
        targetSdk = 36
        versionCode = 10001
        versionName = "0.1.0-preview.1"
        ndk.abiFilters += "arm64-v8a"
    }
    sourceSets["main"].apply {
        manifest.srcFile(app.resolve("mirror/AndroidManifest.xml"))
        // Share just the transport and encoder, never the privileged generic variant.
        kotlin.directories += listOf(transportSources.get().asFile.path, app.resolve("src-mirror").path)
        res.directories += app.resolve("mirror/res").path
        jniLibs.directories += (rootProject.extra["nativeLibs"] as File).path
    }
    sourceSets["test"].kotlin.directories += app.resolve("test-mirror").path
    buildTypes {
        release {
            isMinifyEnabled = false // Native JNI callbacks use their source names.
            signingConfig = signingConfigs["debug"]
        }
    }
}

tasks.named("preBuild") { dependsOn(":cargoNdk", prepareTransport) }
dependencies { testImplementation("junit:junit:4.13.2") }
