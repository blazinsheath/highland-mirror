// One APK: public screen mirroring plus an owner-paired, bundled shell audio helper.
plugins {
    alias(libs.plugins.android.application)
}

val app = rootDir.resolve("app")
val transport = listOf(
    "AccessoryWifi", "AudioOutputSettings", "AudioStream", "Bluetooth", "Cues", "Fingers",
    "Native", "NavBar", "NetworkIdentity", "Relay", "ScreenStream", "Session", "SlipstreamService", "Touch",
)
val transportSources = layout.buildDirectory.dir("generated/mirror-transport")
val prepareTransport = tasks.register<Sync>("prepareMirrorTransport") {
    from(app.resolve("src")) { include(transport.map { "**/$it.kt" }) }
    // Reuse the physically verified capture implementation, without any Shizuku classes.
    from(app.resolve("src-generic")) { include("**/PlaybackCapture.kt") }
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
        versionCode = 10007
        versionName = "0.1.0-preview.7"
        ndk.abiFilters += "arm64-v8a"
    }
    sourceSets["main"].apply {
        manifest.srcFile(app.resolve("mirror/AndroidManifest.xml"))
        // Share just the transport and encoder, never the privileged generic variant.
        kotlin.directories += listOf(transportSources.get().asFile.path, app.resolve("src-mirror").path)
        res.directories += app.resolve("mirror/res").path
        assets.directories += app.resolve("mirror/assets").path
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
// The shared launcher uses the same SDK-36-compatible metadata override as Slipstream.
tasks.matching { it.name.matches(Regex("check.*AarMetadata")) }.configureEach { enabled = false }
dependencies {
    implementation(project(":launcher"))
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    testImplementation("junit:junit:4.13.2")
}
