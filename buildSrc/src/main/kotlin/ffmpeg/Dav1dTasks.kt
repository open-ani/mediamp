/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package ffmpeg

import nativebuild.makePkgConfigRelocatable
import nativebuild.pathForShell
import nativebuild.recreateDirectory
import nativebuild.shellQuote
import nativebuild.shellScriptWithExports
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

internal fun missingDav1dSourceTreeMessage(sourceDir: File): String = """
    dav1d source tree is missing at ${sourceDir.absolutePath}.

    The dav1d sources are a git submodule and have not been checked out.

    If you already cloned this repository, run:
      git submodule update --init --recursive mediamp-ffmpeg/dav1d

    For a fresh checkout, clone with submodules:
      git clone --recursive git@github.com:open-ani/mediamp.git
""".trimIndent()

/**
 * Builds dav1d (AV1 software decoder) with meson and installs it as a static library.
 *
 * FFmpeg 8 removed the native AV1 software decoder — `libavcodec/av1dec.c` is now a
 * hwaccel-only shim that returns ENOSYS when no hwaccel is available — so software AV1
 * playback needs the external `libdav1d` decoder. FFmpeg links it statically into
 * `libavcodec`, which keeps runtime packaging unchanged: no extra dll/dylib/so to ship,
 * no install-name rewriting, no new entries in the Windows runtime DLL collection.
 *
 * This is a single task (setup + compile + install) rather than the configure/build split
 * used for FFmpeg and mpv: dav1d compiles in seconds, so the extra layer would only add
 * bookkeeping. The task is still the cache boundary for the FFmpeg build — [FfmpegBuildTask]
 * keys on this install directory.
 */
@CacheableTask
abstract class Dav1dBuildTask : DefaultTask() {
    /** dav1d submodule source content — the "what are we building" cache key input. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    /** Extra `meson setup` arguments (per-target, e.g. macOS version-min c_args). */
    @get:Input
    abstract val mesonArgs: ListProperty<String>

    @get:Input
    abstract val shell: Property<String>

    @get:Input
    abstract val envVars: MapProperty<String, String>

    @get:Input
    abstract val hostOsName: Property<String>

    @get:Input
    abstract val msys2Packages: ListProperty<String>

    /** See [nativebuild.ToolchainFingerprintValueSource]. */
    @get:Input
    abstract val toolchainFingerprint: Property<String>

    /** Meson scratch directory; not an input or output. */
    @get:Internal
    abstract val buildDirPath: DirectoryProperty

    @get:OutputDirectory
    abstract val installDir: DirectoryProperty

    @get:OutputFile
    abstract val buildStamp: RegularFileProperty

    /** Tool location, not content input: versions are captured by the toolchain fingerprint. */
    @get:Internal
    abstract val msys2Dir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val source = sourceDir.get().asFile
        val mesonBuildDir = buildDirPath.get().asFile
        val install = installDir.get().asFile
        val windowsMsys = hostOsName.get() == "Windows"

        require(source.resolve("meson.build").isFile) {
            missingDav1dSourceTreeMessage(source)
        }

        if (windowsMsys) {
            val msys2Root = msys2Dir.orNull?.asFile
                ?: error("MSYS2 directory must be configured for Windows dav1d builds.")
            val packages = msys2Packages.get()
            if (packages.isNotEmpty()) {
                logger.lifecycle("Ensuring MSYS2 packages: ${packages.joinToString()}")
                execOperations.exec {
                    commandLine(
                        shell.get(),
                        "-l",
                        "-c",
                        "pacman -S --needed --noconfirm ${packages.joinToString(" ")}",
                    )
                    environment(envVars.get())
                    workingDir = msys2Root
                }
            }
        }

        recreateDirectory(mesonBuildDir)
        recreateDirectory(install)

        val setupArgs = buildList {
            add("setup")
            add(pathForShell(mesonBuildDir, windowsMsys))
            add(pathForShell(source, windowsMsys))
            add("--prefix")
            add(pathForShell(install, windowsMsys))
            // Debian/Ubuntu meson defaults libdir to the multiarch subdirectory
            // (lib/x86_64-linux-gnu); the FFmpeg build expects a flat lib/.
            add("--libdir=lib")
            add("--buildtype=release")
            add("--default-library=static")
            add("-Denable_tools=false")
            add("-Denable_tests=false")
            add("-Denable_examples=false")
            add("--wrap-mode=nodownload")
            addAll(mesonArgs.get())
        }.joinToString(" ") { shellQuote(it) }

        execOperations.exec {
            commandLine(
                shell.get(),
                "-l",
                "-c",
                shellScriptWithExports(
                    envVars.get(),
                    "meson $setupArgs && " +
                        "meson compile -C ${shellQuote(pathForShell(mesonBuildDir, windowsMsys))} && " +
                        "meson install -C ${shellQuote(pathForShell(mesonBuildDir, windowsMsys))}",
                ),
            )
            environment(envVars.get())
        }

        require(install.resolve("lib/pkgconfig/dav1d.pc").isFile) {
            "dav1d install did not produce lib/pkgconfig/dav1d.pc under ${install.absolutePath}"
        }
        makePkgConfigRelocatable(install, logger)
        buildStamp.get().asFile.writeText(System.currentTimeMillis().toString())
        logger.lifecycle("dav1d installed to: ${install.absolutePath}")
    }
}
