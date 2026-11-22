/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package mpv

import Arch
import Os
import getArch
import getOs
import getPropertyOrNull
import nativebuild.resolveMsys2Dir
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Hardware a scenario needs. Each tier includes the ones before it: `renderPathMatrix` runs
 * every scenario up to `-Pmediamp.mpv.renderpath.tier` (default [CI]).
 */
enum class RenderPathTier {
    /** GitHub-hosted runners: no GPU, Skia and mpv on WARP. */
    CI,

    /** Any machine with a hardware GPU. */
    GPU,

    /** Two hardware adapters, e.g. an Optimus laptop. */
    HYBRID,
}

/**
 * One environment the render path matrix reproduces: a Skiko render API to start from plus
 * the overrides that make this machine behave like the target one.
 */
class RenderPathScenario(
    val name: String,
    val tier: RenderPathTier,
    /** What the scenario stands for, shown in the report. */
    val simulates: String,
    val renderApi: String,
    /** `skiko.gpu.priority`: which adapter Skia picks on a hybrid machine. */
    val gpuPriority: String? = null,
    /** `MEDIAMP_D3D11_*` overrides read by the native D3D11 producer. */
    val environment: Map<String, String> = emptyMap(),
    /** mpv `hwdec` set by the test, overriding mediamp's choice. */
    val hwdec: String? = null,
    /** Windows per-app GPU preference for the test JVM: 1 power saving, 2 high performance. */
    val windowsGpuPreference: Int? = null,
    /** `hwdec-current` required in strict mode. */
    val expectedHwdec: String? = null,
)

private fun renderPathScenarios(): List<RenderPathScenario> = when (getOs()) {
    Os.Windows -> windowsRenderPathScenarios(arm64 = getArch() == Arch.AARCH64)
    Os.MacOS -> listOf(
        RenderPathScenario("metal", RenderPathTier.CI, "Default macOS path", renderApi = "METAL"),
        RenderPathScenario(
            "software-compat", RenderPathTier.CI, "Skiko fell back from Metal to software",
            renderApi = "SOFTWARE_COMPAT",
        ),
    )

    else -> emptyList()
}

private fun windowsRenderPathScenarios(arm64: Boolean): List<RenderPathScenario> = buildList {
    val ci = RenderPathTier.CI
    val gpu = RenderPathTier.GPU
    val hybrid = RenderPathTier.HYBRID
    val maxFeatureLevel = "MEDIAMP_D3D11_MAX_FEATURE_LEVEL"
    val adapter = "MEDIAMP_D3D11_ADAPTER"
    // Skiko's chain after a failed D3D12 start: OpenGL, then software. Windows ARM64 has no
    // Skiko OpenGL, so there the chain goes straight to software.
    val afterD3D12 = if (arm64) "SOFTWARE_FAST" else "OPENGL"

    add(RenderPathScenario("d3d12", ci, "GPU with a D3D12 driver (the default path)", renderApi = "DIRECT3D"))
    add(RenderPathScenario("angle", ci, "Skiko on ANGLE", renderApi = "ANGLE"))
    add(RenderPathScenario("software-fast", ci, "Skiko on its fast software renderer", renderApi = "SOFTWARE_FAST"))
    add(RenderPathScenario("software-compat", ci, "Skiko on its compatible software renderer", renderApi = "SOFTWARE_COMPAT"))
    if (!arm64) {
        // On ARM64 this would be software-fast again.
        add(
            RenderPathScenario(
                "no-d3d12", ci, "GPU without a D3D12 driver (e.g. Intel HD 4600): Skiko's post-D3D12 chain",
                renderApi = afterD3D12,
            ),
        )
    }
    add(
        RenderPathScenario(
            "dx10-gpu", ci, "DX10.1-class GPU (e.g. Intel HD 3000): no D3D12, mpv's D3D11 device on WARP",
            renderApi = afterD3D12, environment = mapOf(maxFeatureLevel to "10_1"),
        ),
    )
    add(
        RenderPathScenario(
            "mpv-default-adapter", ci, "mpv on the system default adapter instead of Skia's (pre-0.5.1 behaviour)",
            renderApi = "DIRECT3D", environment = mapOf(adapter to "default"),
        ),
    )
    add(
        RenderPathScenario(
            "os-pref-high-performance", ci,
            "Windows graphics preference \"High performance\" for java.exe (no effect without two GPUs)",
            renderApi = "DIRECT3D", windowsGpuPreference = 2,
        ),
    )

    add(
        RenderPathScenario(
            "software-decode", gpu, "GPU rendering with software decoding", renderApi = "DIRECT3D", hwdec = "no",
        ),
    )
    add(
        RenderPathScenario(
            "hwdec-d3d11va", gpu, "Hardware decoding on the rendering GPU",
            renderApi = "DIRECT3D", expectedHwdec = "d3d11va",
        ),
    )
    add(
        RenderPathScenario(
            "mpv-on-warp", gpu, "Skia on the GPU, mpv on WARP: shared textures across adapters",
            renderApi = "DIRECT3D", environment = mapOf(adapter to "warp"),
        ),
    )

    add(RenderPathScenario("skia-igpu", hybrid, "Skia on the integrated GPU", renderApi = "DIRECT3D", gpuPriority = "integrated"))
    add(RenderPathScenario("skia-dgpu", hybrid, "Skia on the discrete GPU", renderApi = "DIRECT3D", gpuPriority = "discrete"))
    add(
        RenderPathScenario(
            "skia-igpu-mpv-dgpu", hybrid, "Skia on the integrated GPU, mpv forced onto the discrete one",
            renderApi = "DIRECT3D", gpuPriority = "integrated", environment = mapOf(adapter to "high-performance"),
        ),
    )
    add(
        RenderPathScenario(
            "skia-dgpu-mpv-igpu", hybrid, "Skia on the discrete GPU, mpv forced onto the integrated one",
            renderApi = "DIRECT3D", gpuPriority = "discrete", environment = mapOf(adapter to "minimum-power"),
        ),
    )
    add(
        RenderPathScenario(
            "os-pref-power-saving", hybrid, "Windows graphics preference \"Power saving\" for java.exe",
            renderApi = "DIRECT3D", windowsGpuPreference = 1,
        ),
    )
}

/**
 * Registers the render path matrix: `renderPathTest_<scenario>` runs RenderPathMatrixTest
 * in a fresh JVM per scenario (Skiko reads its properties when the window is created, the
 * native loader keeps per-JVM state, and the D3D11 overrides are process environment), and
 * `renderPathMatrix` runs the scenarios of the selected tier. Results:
 * `build/render-path-matrix/<scenario>/`.
 *
 * A requested API the machine cannot provide still runs: Skiko falls back as it would for a
 * user and the scenario reports where it landed. `-Pmediamp.mpv.renderpath.strict=true`
 * makes that, and a scenario's own expectations, failures.
 */
fun Project.configureRenderPathMatrix() {
    val scenarios = renderPathScenarios()
    val tierName = getPropertyOrNull("mediamp.mpv.renderpath.tier") ?: "ci"
    val selectedTier = RenderPathTier.entries.firstOrNull { it.name.equals(tierName, ignoreCase = true) }
        ?: error("Unknown mediamp.mpv.renderpath.tier '$tierName'; expected one of ${RenderPathTier.entries}")
    val strict = getPropertyOrNull("mediamp.mpv.renderpath.strict")
    val matrixDir = layout.buildDirectory.dir("render-path-matrix").get().asFile

    tasks.named("desktopTest", Test::class.java) {
        filter { excludeTestsMatching("*RenderPathMatrixTest") }
    }

    val report = tasks.register("renderPathMatrixReport") {
        group = "mediamp"
        description = "Tabulates the render path matrix results, also into the GitHub step summary"
        // Results left over from an earlier build (a scenario not run this time) are not reported.
        val buildStartMillis = System.currentTimeMillis()
        doLast {
            val text = renderPathReport(scenarios, selectedTier, matrixDir, buildStartMillis)
            logger.lifecycle(text)
            System.getenv("GITHUB_STEP_SUMMARY")?.let { File(it).appendText(text + "\n") }
        }
    }

    val testCompilation = extensions.getByType(KotlinMultiplatformExtension::class.java)
        .targets.getByName("desktop").compilations.getByName("test")
    val nativeStackDumper = NativeStackDumper.of(this)
    val scenarioTasks = scenarios.map { scenario ->
        val outputDir = matrixDir.resolve(scenario.name)
        val restorePreference = scenario.windowsGpuPreference?.let {
            tasks.register("renderPathRestoreGpuPreference_${scenario.name}") {
                description = "Restores the Windows GPU preference changed by renderPathTest_${scenario.name}"
                doLast { WindowsGpuPreferences.restore(matrixDir.resolve(".gpu-preference-${scenario.name}")) }
            }
        }
        tasks.register("renderPathTest_${scenario.name}", Test::class.java) {
            group = "mediamp"
            description = "Render path scenario '${scenario.name}' (${scenario.tier.name.lowercase()}): ${scenario.simulates}"
            testClassesDirs = testCompilation.output.classesDirs
            classpath = files(testCompilation.output.allOutputs, testCompilation.runtimeDependencyFiles)
            filter { includeTestsMatching("*RenderPathMatrixTest") }
            systemProperty("skiko.renderApi", scenario.renderApi)
            scenario.gpuPriority?.let { systemProperty("skiko.gpu.priority", it) }
            environment(scenario.environment)
            systemProperty("mediamp.mpv.renderpath.scenario", scenario.name)
            systemProperty("mediamp.mpv.renderpath.outputDir", outputDir.absolutePath)
            scenario.hwdec?.let { systemProperty("mediamp.mpv.renderpath.hwdec", it) }
            scenario.expectedHwdec?.let { systemProperty("mediamp.mpv.renderpath.expectedHwdec", it) }
            scenario.windowsGpuPreference?.let { systemProperty("mediamp.mpv.renderpath.gpuPreference", it) }
            strict?.let { systemProperty("mediamp.mpv.renderpath.strict", it) }
            // The JUnit XML keeps the run's stdout/stderr, including Skiko's own fallback logs.
            reports.junitXml.outputLocation.set(outputDir.resolve("junit"))
            // The outcome depends on the machine's GPU and drivers, which are not task inputs.
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
            // A test JVM stuck in native teardown or in JVM exit is beyond the test's own
            // timeouts. Gradle kills it here, so the remaining scenarios still run and the
            // runner is not held until the job's time limit.
            timeout.set(SCENARIO_TIMEOUT)
            var hangDump: Thread? = null
            doFirst {
                outputDir.deleteRecursively()
                scenario.windowsGpuPreference?.let { preference ->
                    // Set before the JVM starts; the restore task runs even when the test fails.
                    val java = javaLauncher.orNull?.executablePath?.asFile?.absolutePath ?: executable
                    WindowsGpuPreferences.set(java, preference, matrixDir.resolve(".gpu-preference-${scenario.name}"))
                }
                hangDump = dumpThreadsIfHung(this as Test, outputDir, nativeStackDumper)
            }
            doLast { hangDump?.interrupt() }
            restorePreference?.let { finalizedBy(it) }
            finalizedBy(report)
        }
    }
    report.configure { mustRunAfter(scenarioTasks) }

    tasks.register("renderPathMatrix") {
        group = "mediamp"
        description = "Runs the render path scenarios up to -Pmediamp.mpv.renderpath.tier (ci|gpu|hybrid, " +
            "default ci) for this host; use --continue to run every scenario"
        dependsOn(scenarios.zip(scenarioTasks).filter { (scenario, _) -> scenario.tier <= selectedTier }.map { it.second })
    }
}

/** A healthy scenario ends within seconds, WARP within about half a minute. */
private val SCENARIO_HANG_AFTER: Duration = Duration.ofMinutes(2)

/** How long one stack dump tool may run against a hung test JVM. */
private val HANG_DUMP_TOOL_TIMEOUT: Duration = Duration.ofSeconds(45)

/** Leaves the dump tools their time (three, and one compiles first) before Gradle kills the hung test JVM. */
private val SCENARIO_TIMEOUT: Duration = SCENARIO_HANG_AFTER + HANG_DUMP_TOOL_TIMEOUT.multipliedBy(4) + Duration.ofSeconds(30)

/**
 * Records where the threads of a hung scenario are, as `hang-*.txt` next to the scenario's
 * results, before the task timeout kills its JVM. The process is the only evidence such a hang
 * leaves: the test body has usually passed by then, and the JVM is stuck in native code.
 *
 * The tools run from most to least likely to work on a native deadlock. [NativeStackDumper]
 * gives complete native stacks on Windows. `jhsdb` reads the process from outside too but
 * shows a single frame per native thread there. `jcmd` gives the Java stacks while the JVM
 * still answers, which it does not once a thread is stuck holding the loader lock.
 */
private fun dumpThreadsIfHung(task: Test, outputDir: File, nativeStackDumper: NativeStackDumper?): Thread {
    // Process start times are coarser than Instant.now() on some systems.
    val startedAfter = Instant.now().minusSeconds(2)
    val jdkBin = File(task.javaLauncher.orNull?.executablePath?.asFile?.absolutePath ?: task.executable).parentFile
    return thread(isDaemon = true, name = "render-path-hang-dump-${task.name}") {
        try {
            Thread.sleep(SCENARIO_HANG_AFTER.toMillis())
        } catch (_: InterruptedException) {
            return@thread
        }
        if (!task.state.executing) return@thread
        // Scenarios run one at a time, so a java process this build started after the task
        // began is the scenario's test JVM.
        val testJvms = ProcessHandle.current().descendants()
            .filter { it.isAlive && it.info().startInstant().map { started -> started.isAfter(startedAfter) }.orElse(false) }
            .filter { it.info().command().map { command -> File(command).nameWithoutExtension == "java" }.orElse(false) }
            .toList()
        task.logger.error(
            "${task.path} is still running after ${SCENARIO_HANG_AFTER.toSeconds()} s. " +
                "Dumping the threads of its JVM (pid ${testJvms.joinToString { it.pid().toString() }}) into $outputDir",
        )
        outputDir.mkdirs()
        for (jvm in testJvms) {
            val pid = jvm.pid().toString()
            nativeStackDumper?.dump(pid, outputDir.resolve("hang-native-stacks-$pid.txt"))
            runDumpTool(jdkTool(jdkBin, "jhsdb"), outputDir.resolve("hang-jhsdb-stacks-$pid.txt"), "jstack", "--mixed", "--pid", pid)
            runDumpTool(jdkTool(jdkBin, "jcmd"), outputDir.resolve("hang-java-stacks-$pid.txt"), pid, "Thread.print", "-l")
        }
    }
}

private fun jdkTool(jdkBin: File, name: String): File = jdkBin.resolve(if (getOs() == Os.Windows) "$name.exe" else name)

/** Runs [executable] with its output in [output], for at most [HANG_DUMP_TOOL_TIMEOUT]. False if it did not end with 0. */
private fun runDumpTool(
    executable: File,
    output: File,
    vararg args: String,
    configure: ProcessBuilder.() -> Unit = {},
): Boolean {
    if (!executable.isFile) {
        output.appendText("$executable does not exist\n")
        return false
    }
    return runCatching {
        val process = ProcessBuilder(executable.absolutePath, *args).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(output)).apply(configure).start()
        if (!process.waitFor(HANG_DUMP_TOOL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            output.appendText("\n${executable.name} did not finish within ${HANG_DUMP_TOOL_TIMEOUT.toSeconds()} s and was stopped\n")
            false
        } else {
            process.exitValue() == 0
        }
    }.onFailure { output.appendText("\n${executable.name} could not run: $it\n") }.getOrDefault(false)
}

/**
 * `ci-helper/stackdump.c`, compiled on first use with the MSYS2 gcc that also builds mpv, so
 * Windows x64 only. It walks every thread's stack from outside the process with dbghelp.
 */
private class NativeStackDumper(private val source: File, private val gcc: File, private val executable: File) {
    fun dump(pid: String, output: File) {
        if (!executable.isFile || executable.lastModified() < source.lastModified()) {
            executable.parentFile.mkdirs()
            val compiled = runDumpTool(gcc, output, "-O1", "-o", executable.absolutePath, source.absolutePath, "-ldbghelp") {
                // gcc looks for its own programs and libraries on PATH.
                val path = environment().keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
                environment()[path] = gcc.parent + File.pathSeparator + environment()[path].orEmpty()
            }
            if (!compiled) {
                output.appendText("\n$source did not compile\n")
                return
            }
        }
        runDumpTool(executable, output, pid)
    }

    companion object {
        fun of(project: Project): NativeStackDumper? {
            if (getOs() != Os.Windows || getArch() == Arch.AARCH64) return null
            val gcc = runCatching { project.resolveMsys2Dir() }.getOrNull()?.resolve("ucrt64/bin/gcc.exe")
                ?.takeIf { it.isFile } ?: return null
            return NativeStackDumper(
                source = project.rootProject.file("ci-helper/stackdump.c"),
                gcc = gcc,
                executable = project.layout.buildDirectory.file("stackdump/stackdump.exe").get().asFile,
            )
        }
    }
}

private fun renderPathReport(
    scenarios: List<RenderPathScenario>,
    selectedTier: RenderPathTier,
    matrixDir: File,
    buildStartMillis: Long,
): String = buildString {
    fun cell(value: String?) = (value ?: "").replace("|", "\\|").replace("\n", " ")
    appendLine("### Render path matrix (${System.getProperty("os.name")} ${System.getProperty("os.arch")})")
    appendLine()
    val gpus = machineGpus()
    if (gpus.isNotEmpty()) {
        appendLine("GPUs: " + gpus.joinToString("; "))
        appendLine()
    }
    appendLine("| Scenario | Tier | Skiko actual | mediamp path | Skia adapter | mpv adapter | hwdec | VO drops | Result |")
    appendLine("|---|---|---|---|---|---|---|---|---|")
    val details = StringBuilder()
    val ran = mutableListOf<RenderPathScenario>()
    for (scenario in scenarios) {
        val file = matrixDir.resolve("${scenario.name}/result.properties")
        val fresh = file.isFile && file.lastModified() >= buildStartMillis
        // Written by dumpThreadsIfHung: the test JVM outlived the scenario and was killed.
        val hung = file.parentFile.listFiles().orEmpty()
            .any { it.name.startsWith("hang-") && it.lastModified() >= buildStartMillis }
        val hangNote = "**JVM hung:** the test JVM was still running after ${SCENARIO_HANG_AFTER.toSeconds()} s and was " +
            "killed. Its thread dumps are the `hang-*.txt` files of this scenario in the uploaded results."
        if (!fresh) {
            if (hung) {
                appendLine("| ${scenario.name} | ${scenario.tier.name.lowercase()} | | | | | | | JVM hung |")
                details.appendLine("<details><summary>${scenario.name}</summary>").appendLine()
                details.appendLine(hangNote).appendLine()
                details.appendLine("</details>").appendLine()
            } else if (scenario.tier <= selectedTier) {
                appendLine("| ${scenario.name} | ${scenario.tier.name.lowercase()} | | | | | | | not run |")
            }
            continue
        }
        ran += scenario
        val result = Properties().apply { file.reader().use { load(it) } }
        fun get(key: String) = cell(result.getProperty(key))
        val skiko = get("actualApi") + (result.getProperty("redrawer")?.let { " ($it)" } ?: "")
        appendLine(
            "| ${scenario.name} | ${scenario.tier.name.lowercase()} | $skiko | ${get("mediampRenderer")} | " +
                "${get("skiaAdapter")} | ${get("mpvAdapter")} | ${get("hwdec")} | ${get("droppedFrames")} | " +
                "${get("result")}${if (hung) ", then JVM hung" else ""} |",
        )
        val failure = result.getProperty("failure")
        val log = result.getProperty("log").orEmpty()
        if (failure != null || hung || log.isNotBlank()) {
            details.appendLine("<details><summary>${scenario.name}</summary>").appendLine()
            failure?.let { details.appendLine("**Failure:** `${it.replace("`", "'")}`").appendLine() }
            if (hung) details.appendLine(hangNote).appendLine()
            if (log.isNotBlank()) details.appendLine("```").appendLine(log).appendLine("```")
            details.appendLine("</details>").appendLine()
        }
    }
    appendLine()
    for (scenario in ran) {
        val overrides = buildList {
            add("skiko.renderApi=${scenario.renderApi}")
            scenario.gpuPriority?.let { add("skiko.gpu.priority=$it") }
            scenario.environment.forEach { (key, value) -> add("$key=$value") }
            scenario.hwdec?.let { add("hwdec=$it") }
            scenario.windowsGpuPreference?.let { add("GpuPreference=$it") }
        }
        appendLine("- `${scenario.name}`: ${scenario.simulates} (${overrides.joinToString(", ")})")
    }
    appendLine()
    append(details)
}

/** The machine's display adapters with driver versions, best effort. */
private fun machineGpus(): List<String> = runCatching {
    val command = when (getOs()) {
        Os.Windows -> listOf(
            "powershell", "-NoProfile", "-Command",
            "Get-CimInstance Win32_VideoController | ForEach-Object { \$_.Name + ' (driver ' + \$_.DriverVersion + ')' }",
        )

        Os.MacOS -> listOf("system_profiler", "SPDisplaysDataType")
        else -> return emptyList()
    }
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()
    when (getOs()) {
        Os.MacOS -> output.lines().filter { "Chipset Model:" in it }.map { it.substringAfter(":").trim() }
        else -> output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
}.getOrDefault(emptyList())

/**
 * Windows "Graphics settings" per-app GPU preference, keyed by executable path. The previous
 * value is kept in a backup file so it can be restored after the scenario, even if the test
 * JVM crashed.
 */
private object WindowsGpuPreferences {
    private const val KEY = """HKCU\Software\Microsoft\DirectX\UserGpuPreferences"""
    private const val ABSENT = "<absent>"

    fun set(executable: String, preference: Int, backup: File) {
        backup.parentFile.mkdirs()
        backup.writeText(executable + "\n" + (query(executable) ?: ABSENT))
        reg("add", KEY, "/v", executable, "/t", "REG_SZ", "/d", "GpuPreference=$preference;", "/f")
    }

    fun restore(backup: File) {
        if (!backup.isFile) return
        val (executable, previous) = backup.readText().split("\n", limit = 2)
        if (previous == ABSENT) {
            reg("delete", KEY, "/v", executable, "/f")
        } else {
            reg("add", KEY, "/v", executable, "/t", "REG_SZ", "/d", previous, "/f")
        }
        backup.delete()
    }

    private fun query(executable: String): String? {
        val process = ProcessBuilder("reg", "query", KEY, "/v", executable).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) return null
        return output.lines().firstOrNull { "REG_SZ" in it }?.substringAfter("REG_SZ")?.trim()
    }

    private fun reg(vararg args: String) {
        val process = ProcessBuilder("reg", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "reg ${args.joinToString(" ")} failed: $output" }
    }
}
