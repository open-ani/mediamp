package nativebuild

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedLibraryManifestTest {
    @Test
    fun parsesOnlyGlibcRequirements() {
        val output = """
            Version symbols section '.gnu.version' contains 3 entries:
             Addr: 0x0

            Version definition section '.gnu.version_d' contains 1 entry:
              0x001c: Rev: 1  Flags: none  Index: 2  Cnt: 1  Name: GLIBC_9.99

            Version needs section '.gnu.version_r' contains 1 entry:
              0x0010:   Name: GLIBC_2.2.5  Flags: none  Version: 4
              0x0020:   Name: GLIBC_2.39  Flags: none  Version: 3
              0x0030:   Name: GLIBC_ABI_DT_RELR  Flags: none  Version: 2
        """.trimIndent()

        assertEquals(
            setOf("GLIBC_2.2.5", "GLIBC_2.39", "GLIBC_ABI_DT_RELR"),
            parseRequiredGlibcVersions(output),
        )
    }

    @Test
    fun enforcesDeclaredBaseline() {
        assertTrue(isGlibcRequirementCompatible("GLIBC_2.2.5"))
        assertTrue(isGlibcRequirementCompatible("GLIBC_2.39"))
        assertTrue(isGlibcRequirementCompatible("GLIBC_ABI_DT_RELR"))
        assertFalse(isGlibcRequirementCompatible("GLIBC_2.40"))
        assertFalse(isGlibcRequirementCompatible("GLIBC_PRIVATE"))
    }
}
