import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.wire)
    `maven-publish`
}

group = "io.github.nostrord"
version = System.getenv("RELEASE_VERSION")?.takeIf { it.isNotBlank() } ?: "0.1.0-SNAPSHOT"

/**
 * livekit-ffi release these bindings target. The `.proto` files and the prebuilt native
 * libraries are both fetched from this tag, so the generated code always matches the binary
 * it talks to.
 */
val ffiVersion = libs.versions.livekitFfi.get()

private val ffiReleaseUrl = "https://github.com/livekit/rust-sdks/releases/download/livekit-ffi/v$ffiVersion"
private val protoSourceUrl = "https://raw.githubusercontent.com/livekit/rust-sdks/livekit-ffi/v$ffiVersion/livekit-ffi/protocol"

private val protoFiles = listOf(
    "audio_frame", "data_stream", "data_track", "e2ee", "ffi", "handle",
    "participant", "room", "rpc", "stats", "track", "track_publication", "video_frame",
).map { "$it.proto" }

/**
 * Native libraries to bundle, as `<ffi asset name>` to `<JNA resource prefix>`.
 *
 * JNA resolves `<resourcePrefix>/<libname>` off the classpath, so each platform's library
 * lands in the jar under the directory name JNA derives from `os.name` / `os.arch`.
 */
private val desktopNatives = mapOf(
    "linux-x86_64" to "linux-x86-64",
    "linux-arm64" to "linux-aarch64",
    "macos-x86_64" to "darwin-x86-64",
    "macos-arm64" to "darwin-aarch64",
    "windows-x86_64" to "win32-x86-64",
    "windows-arm64" to "win32-aarch64",
)

private val protoDir = layout.buildDirectory.dir("livekit-ffi/protocol")
private val nativesDir = layout.buildDirectory.dir("livekit-ffi/natives")

/** Vendors the FFI protocol definitions for [ffiVersion]. Skipped when already present. */
val downloadFfiProtos by tasks.registering {
    description = "Download the livekit-ffi .proto files for the pinned FFI version."
    val outputDir = protoDir
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        protoFiles.forEach { name ->
            val target = dir.resolve(name)
            if (target.exists()) return@forEach
            logger.lifecycle("livekit-ffi: fetching $name")
            URI("$protoSourceUrl/$name").toURL().openStream().use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
    }
}

/**
 * Downloads the prebuilt `liblivekit_ffi` for every desktop platform and lays them out where
 * JNA will find them on the classpath.
 *
 * The binaries are ~25-30 MB each, so a full jar carries every desktop platform. Consumers
 * that only ship one OS should depend on the per-platform artifact instead.
 */
val downloadFfiNatives by tasks.registering {
    description = "Download prebuilt liblivekit_ffi binaries for the pinned FFI version."
    val outputDir = nativesDir
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile
        desktopNatives.forEach { (asset, jnaPrefix) ->
            val target = dir.resolve(jnaPrefix)
            if (target.exists() && target.listFiles()?.isNotEmpty() == true) return@forEach
            target.mkdirs()
            val zip = File.createTempFile("ffi-$asset", ".zip")
            logger.lifecycle("livekit-ffi: fetching $asset")
            URI("$ffiReleaseUrl/ffi-$asset.zip").toURL().openStream().use { input ->
                zip.outputStream().use { input.copyTo(it) }
            }
            copy {
                from(zipTree(zip)) {
                    // The header and licence ride along in the zip; only the library is needed.
                    include("*.so", "*.dylib", "*.dll")
                }
                into(target)
            }
            zip.delete()
        }
    }
}

wire {
    protoPath {
        srcDir(protoDir)
    }
    sourcePath {
        srcDir(protoDir)
    }
    kotlin {
        javaInterop = false
    }
}

// Wire has to see the .proto files before it can generate; the download task produces them.
// Matched by type: Wire names its tasks per source set (generateCommonMainProtos, ...).
tasks.withType<com.squareup.wire.gradle.WireTask>().configureEach {
    dependsOn(downloadFfiProtos)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            api(libs.wire.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(libs.jna)
        }
    }
}

/*
 * Native packaging.
 *
 * The natives are NOT in the main jvm artifact: at ~20-27 MB each, bundling all six would put
 * 130 MB into every consumer, and a desktop app packaged per OS ships exactly one of them.
 * Each platform is published as its own `livekit-kmp-natives-<platform>` jar, laid out under
 * the JNA resource prefix so `Native.load` finds it on the classpath. Consumers depend on the
 * platforms they ship (the way secp256k1-kmp splits its JNI artifacts).
 */
val nativeJars = desktopNatives.values.associateWith { jnaPrefix ->
    tasks.register<Jar>("nativeJar${jnaPrefix.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }}") {
        dependsOn(downloadFfiNatives)
        archiveBaseName.set("livekit-kmp-natives-$jnaPrefix")
        from(nativesDir.map { it.dir(jnaPrefix) }) {
            into(jnaPrefix)
        }
    }
}

publishing {
    publications {
        nativeJars.forEach { (jnaPrefix, jarTask) ->
            register<MavenPublication>("natives${jnaPrefix.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }}") {
                artifactId = "livekit-kmp-natives-$jnaPrefix"
                artifact(jarTask)
            }
        }
    }
}

/** JNA's resource-prefix name for the machine running the build. */
fun hostJnaPrefix(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = when (val a = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86-64"
        "aarch64", "arm64" -> "aarch64"
        else -> a
    }
    val prefix = when {
        os.startsWith("linux") -> "linux"
        os.startsWith("mac") || os.startsWith("darwin") -> "darwin"
        os.startsWith("windows") -> "win32"
        else -> error("unsupported host os: $os")
    }
    return "$prefix-$arch"
}

// Tests put the host's natives jar on the classpath rather than pointing `jna.library.path` at
// a directory. That is exactly how a consumer gets the library, so the test covers the
// packaging too: a jar with the wrong internal layout fails here instead of in someone's app.
tasks.named<Test>("jvmTest") {
    val hostNativeJar = nativeJars.getValue(hostJnaPrefix())
    dependsOn(hostNativeJar)
    classpath += files(hostNativeJar)
}
