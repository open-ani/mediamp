package nativebuild

import Os
import getPropertyOrNull
import org.gradle.api.Project
import java.io.File
import java.util.Locale

internal data class AndroidAbi(
    val abi: String,
    val arch: String,
    val clangTriple: String,
    val apiLevel: Int,
)

internal data class DesktopRuntimeTarget(
    val os: String,
    val arch: String,
    val targetName: String,
)

internal data class NativeBuildProperties(
    val moduleDisplayName: String,
    val buildVariantPropertyName: String,
    val androidAbiPropertyNames: List<String>,
)

internal val FFMPEG_NATIVE_BUILD_PROPERTIES: NativeBuildProperties = NativeBuildProperties(
    moduleDisplayName = "FFmpeg",
    buildVariantPropertyName = "mediamp.ffmpeg.buildvariant",
    androidAbiPropertyNames = listOf("mediamp.ffmpeg.androidabis"),
)

internal val MPV_NATIVE_BUILD_PROPERTIES: NativeBuildProperties = NativeBuildProperties(
    moduleDisplayName = "mpv",
    buildVariantPropertyName = "mediamp.mpv.buildvariant",
    androidAbiPropertyNames = listOf("mediamp.mpv.androidabis", "mediamp.ffmpeg.androidabis"),
)

internal val DEFAULT_ANDROID_ABIS: List<AndroidAbi> = listOf(
    AndroidAbi("armeabi-v7a", "arm", "armv7a-linux-androideabi", 21),
    AndroidAbi("arm64-v8a", "aarch64", "aarch64-linux-android", 21),
    AndroidAbi("x86", "x86", "i686-linux-android", 21),
    AndroidAbi("x86_64", "x86_64", "x86_64-linux-android", 21),
)

internal val DEFAULT_DESKTOP_RUNTIME_TARGETS: List<DesktopRuntimeTarget> = listOf(
    DesktopRuntimeTarget("windows", "x64", "WindowsX64"),
    DesktopRuntimeTarget("linux", "x64", "LinuxX64"),
    DesktopRuntimeTarget("macos", "arm64", "MacosArm64"),
    DesktopRuntimeTarget("macos", "x64", "MacosX64"),
)

private data class ResolvedPropertySelection(
    val propertyName: String,
    val values: List<String>,
)

private fun Project.resolveCommaSeparatedPropertySelection(
    propertyNames: List<String>,
    valueTransform: (String) -> String = { it },
): ResolvedPropertySelection? {
    propertyNames.distinct().forEach { propertyName ->
        val values = getPropertyOrNull(propertyName)
            ?.split(",")
            ?.map(String::trim)
            ?.map(valueTransform)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            ?: return@forEach
        return ResolvedPropertySelection(propertyName, values)
    }
    return null
}

internal fun Project.resolveEnabledBuildVariantFamilies(
    properties: NativeBuildProperties,
    supportedFamilies: Set<String>,
): Set<String> =
    resolveEnabledBuildVariantFamilies(
        propertyName = properties.buildVariantPropertyName,
        supportedFamilies = supportedFamilies,
    )

internal fun Project.resolveEnabledBuildVariantFamilies(
    propertyName: String,
    supportedFamilies: Set<String>,
): Set<String> =
    resolveCommaSeparatedPropertySelection(propertyNames = listOf(propertyName)) { value ->
        value.lowercase(Locale.getDefault())
    }
        ?.values
        ?.toSet()
        ?.also { selected ->
            val unknown = selected - supportedFamilies
            require(unknown.isEmpty()) {
                "Unknown values in $propertyName: ${unknown.joinToString()}. " +
                    "Supported values: ${supportedFamilies.joinToString()}."
            }
        }
        ?: supportedFamilies

internal fun Set<String>.includesBuildVariant(family: String): Boolean =
    family.lowercase(Locale.getDefault()) in this

internal fun Project.resolveAndroidAbis(
    properties: NativeBuildProperties,
    availableAbis: List<AndroidAbi> = DEFAULT_ANDROID_ABIS,
): List<AndroidAbi> =
    resolveAndroidAbis(
        propertyNames = properties.androidAbiPropertyNames,
        availableAbis = availableAbis,
    )

internal fun Project.resolveAndroidAbis(
    propertyName: String,
    fallbackPropertyName: String? = null,
    availableAbis: List<AndroidAbi> = DEFAULT_ANDROID_ABIS,
): List<AndroidAbi> =
    resolveAndroidAbis(
        propertyNames = listOfNotNull(propertyName, fallbackPropertyName),
        availableAbis = availableAbis,
    )

internal fun Project.resolveAndroidAbis(
    propertyNames: List<String>,
    availableAbis: List<AndroidAbi> = DEFAULT_ANDROID_ABIS,
): List<AndroidAbi> {
    val resolved = resolveCommaSeparatedPropertySelection(propertyNames) ?: return availableAbis
    val byAbi = availableAbis.associateBy(AndroidAbi::abi)
    val unknown = resolved.values.filterNot(byAbi::containsKey)
    require(unknown.isEmpty()) {
        "Unknown values in ${resolved.propertyName}: ${unknown.joinToString()}. " +
            "Supported values: ${availableAbis.joinToString { it.abi }}."
    }
    return resolved.values.map { byAbi.getValue(it) }
}

internal fun androidTargetName(abi: AndroidAbi): String =
    "Android${abi.abi.replace("-", "")}"

internal fun Project.resolveNdkDir(): File {
    val explicit = getPropertyOrNull("ndk.dir")
        ?: getPropertyOrNull("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK_HOME")
    if (explicit != null) {
        return file(explicit).also {
            require(it.isDirectory) { "Android NDK not found at '$explicit'." }
        }
    }

    val sdkDir = getPropertyOrNull("sdk.dir")
        ?: getPropertyOrNull("ANDROID_HOME")
        ?: getPropertyOrNull("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Android SDK/NDK not found. Set ndk.dir or ANDROID_NDK_HOME.")
    val ndkRoot = file(sdkDir).resolve("ndk")
    require(ndkRoot.isDirectory) {
        "Android NDK directory not found under '$sdkDir/ndk'. Set ndk.dir or ANDROID_NDK_HOME."
    }
    val versions = ndkRoot.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }.orEmpty()
    return versions.firstOrNull() ?: error("No Android NDK versions found under '$ndkRoot'.")
}

internal fun Project.resolveMsys2Dir(): File {
    val path = getPropertyOrNull("msys2.dir") ?: "C:\\msys64"
    val dir = file(path)
    require(dir.isDirectory) {
        "MSYS2 directory not found at '$path'. Set Gradle property msys2.dir to your MSYS2 installation root."
    }
    return dir
}

internal fun androidNdkHostTag(hostOs: Os): String = when (hostOs) {
    Os.Windows -> "windows-x86_64"
    Os.MacOS -> "darwin-x86_64"
    Os.Linux -> "linux-x86_64"
    Os.Unknown -> error("Unsupported host OS for Android builds")
}

internal fun DesktopRuntimeTarget.artifactSuffix(): String = "$os-$arch"

internal fun DesktopRuntimeTarget.publicationSuffix(): String =
    os.replaceFirstChar { it.uppercase() } + arch.replaceFirstChar { it.uppercase() }
