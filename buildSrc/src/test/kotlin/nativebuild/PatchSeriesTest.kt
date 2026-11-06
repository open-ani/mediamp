package nativebuild

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PatchSeriesTest {
    private val tempDir: File = Files.createTempDirectory("patch-series").toFile()

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    private val one = File("one.patch")
    private val two = File("two.patch")
    private val three = File("three.patch")

    /** Records every call; fails the given (patch, reverse) step. */
    private class RecordingRunner(private val failOn: Pair<File, Boolean>? = null) : GitPatchRunner {
        val calls = mutableListOf<String>()

        override fun apply(patch: File, reverse: Boolean) {
            calls += (if (reverse) "-" else "+") + patch.nameWithoutExtension
            if (failOn == patch to reverse) error("${patch.name} does not apply")
        }
    }

    @Test
    fun appliesInOrderAndRevertsNewestFirst() {
        val git = RecordingRunner()
        applyPatchSeries(listOf(one, two, three), git)
        revertPatchSeries(listOf(one, two, three), git)
        assertEquals(listOf("+one", "+two", "+three", "-three", "-two", "-one"), git.calls)
    }

    @Test
    fun applyUndoesEarlierPatchesWhenALaterOneFails() {
        val git = RecordingRunner(failOn = three to false)
        assertFailsWith<IllegalStateException> { applyPatchSeries(listOf(one, two, three), git) }
        assertEquals(listOf("+one", "+two", "+three", "-two", "-one"), git.calls)
    }

    @Test
    fun applyLeavesNothingToUndoWhenTheFirstPatchFails() {
        val git = RecordingRunner(failOn = one to false)
        assertFailsWith<IllegalStateException> { applyPatchSeries(listOf(one, two), git) }
        assertEquals(listOf("+one"), git.calls)
    }

    @Test
    fun revertReappliesWhenAnOlderPatchFailsToRevert() {
        val git = RecordingRunner(failOn = one to true)
        assertFailsWith<IllegalStateException> { revertPatchSeries(listOf(one, two, three), git) }
        assertEquals(listOf("-three", "-two", "-one", "+two", "+three"), git.calls)
    }

    @Test
    fun rollbackFailureIsSuppressedIntoTheOriginalError() {
        val git = object : GitPatchRunner {
            override fun apply(patch: File, reverse: Boolean) {
                if (patch == two || reverse) error("${patch.name} reverse=$reverse")
            }
        }
        val e = assertFailsWith<IllegalStateException> { applyPatchSeries(listOf(one, two), git) }
        assertEquals("two.patch reverse=false", e.message)
        assertEquals(listOf("one.patch reverse=true"), e.suppressed.map { it.message })
    }

    @Test
    fun realGitTreeIsUnchangedAfterAFailedSeries() {
        val repo = tempDir.resolve("repo").apply { mkdirs() }
        git(repo, "init", "-q")
        val file = repo.resolve("f.txt")
        file.writeText("a\nb\nc\n")
        git(repo, "add", ".")
        git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init")

        // `second` only applies on top of `first`: its context contains first's change.
        val first = makePatch(repo, file, "a\nB\nc\n", "first.patch")
        file.writeText("a\nB\nc\n")
        git(repo, "add", ".")
        val second = makePatch(repo, file, "a\nB\nC\n", "second.patch")
        git(repo, "reset", "-q", "--hard")
        val broken = tempDir.resolve("broken.patch").apply {
            writeText(first.readText().replace(" a\n", " x\n"))
        }

        val runner = processRunner(repo)
        applyPatchSeries(listOf(first, second), runner)
        assertEquals("a\nB\nC\n", file.readText())
        revertPatchSeries(listOf(first, second), runner)
        assertEquals("", git(repo, "status", "--porcelain"))

        assertFailsWith<IllegalStateException> { applyPatchSeries(listOf(first, second, broken), runner) }
        assertEquals("", git(repo, "status", "--porcelain"))
    }

    @Test
    fun rejectsMissingAndDuplicatePatchNames() {
        val a = tempDir.resolve("a/fix.patch").apply { parentFile.mkdirs(); writeText("") }
        val b = tempDir.resolve("b/fix.patch").apply { parentFile.mkdirs(); writeText("") }
        val other = tempDir.resolve("b/other.patch").apply { writeText("") }

        validatePatchSeries("test", listOf(a, other))
        validatePatchSeries("test", emptyList())
        val duplicate = assertFailsWith<IllegalArgumentException> { validatePatchSeries("test", listOf(a, b)) }
        assertTrue("fix.patch" in duplicate.message.orEmpty())
        val missing = assertFailsWith<IllegalArgumentException> {
            validatePatchSeries("test", listOf(tempDir.resolve("missing.patch")))
        }
        assertTrue("not found" in missing.message.orEmpty())
    }

    /** Diff of [newText] against the index, which may already hold an earlier patch. */
    private fun makePatch(repo: File, file: File, newText: String, name: String): File {
        file.writeText(newText)
        return tempDir.resolve(name).apply { writeText(git(repo, "diff")) }
    }

    private fun processRunner(repo: File) = GitPatchRunner { patch, reverse ->
        git(repo, *listOfNotNull("apply", "--reverse".takeIf { reverse }, patch.absolutePath).toTypedArray())
    }

    /** Empty global config, so host settings such as `core.autocrlf` cannot rewrite line endings. */
    private val isolatedGitConfig: File by lazy { tempDir.resolve("gitconfig").apply { writeText("") } }

    private fun git(dir: File, vararg args: String): String {
        val builder = ProcessBuilder("git", *args).directory(dir).redirectErrorStream(true)
        builder.environment().apply {
            keys.removeAll { it.startsWith("GIT_") }
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_CONFIG_GLOBAL", isolatedGitConfig.absolutePath)
        }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }
}
