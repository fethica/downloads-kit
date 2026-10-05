package com.fethica.downloadskit.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module ships in three languages, and a count is a plural in all of them.
 * Without plurals English says "1 items" and Arabic uses one noun form for one,
 * two and eleven. Neither is visible from a JVM test that only formats strings,
 * so this reads the resources themselves, with the module directory as the
 * working directory.
 */
class DownloadsStringResourcesTest {

    private val pluralNameOf = Regex("""<plurals\s+name="([^"]+)"""")

    private fun text(qualifier: String): String =
        File("src/main/res/$qualifier/strings.xml").readText()

    private fun pluralNames(qualifier: String): Set<String> =
        pluralNameOf.findAll(text(qualifier)).map { it.groupValues[1] }.toSet()

    private fun quantities(qualifier: String, name: String): Map<String, String> {
        val block = Regex("""<plurals\s+name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .find(text(qualifier))
            ?.groupValues
            ?.get(1)
            .orEmpty()
        return Regex("""<item\s+quantity="([^"]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `every plural exists in all three languages`() {
        val en = pluralNames("values")
        assertTrue("the group summary is a plural", "downloads_offline_group_summary" in en)
        listOf("values-fr", "values-ar").forEach { qualifier ->
            assertEquals("missing from $qualifier", emptySet<String>(), en - pluralNames(qualifier))
            assertEquals("only in $qualifier", emptySet<String>(), pluralNames(qualifier) - en)
        }
    }

    /**
     * The quantity classes each language actually HAS. English and French pick a
     * different form at one; Arabic picks a different noun at zero, one, two, the
     * three-to-ten range and the eleven-and-up range, which is the whole reason a
     * single string was wrong there.
     */
    @Test
    fun `each language declares the quantities it distinguishes`() {
        val name = "downloads_offline_group_summary"
        assertEquals(setOf("one", "other"), quantities("values", name).keys)
        assertEquals(setOf("one", "many", "other"), quantities("values-fr", name).keys)
        assertEquals(
            setOf("zero", "one", "two", "few", "many", "other"),
            quantities("values-ar", name).keys
        )
    }

    @Test
    fun `every quantity still carries the size, and the counted forms carry the count`() {
        val name = "downloads_offline_group_summary"
        listOf("values", "values-fr", "values-ar").forEach { qualifier ->
            val forms = quantities(qualifier, name)
            forms.forEach { (quantity, value) ->
                assertTrue("$qualifier/$quantity has no size", value.contains("%2\$s"))
            }
            // The catch-all form always states the number; only the forms a language
            // names in words (Arabic's one and two) may leave it out.
            assertTrue("$qualifier/other has no count", forms.getValue("other").contains("%1\$d"))
        }
    }
}
