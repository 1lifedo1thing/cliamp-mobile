package stream.kleeamp.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeImportTest {
    private fun themeJson(nameFragment: String = ""): String {
        val roles = CustomThemeRoles.joinToString(",") { "\"$it\": \"#112233\"" }
        return "{$nameFragment\"dark\": true,$roles}"
    }

    @Test fun namedThemeShowsItsName() {
        val raw = themeJson("\"name\": \"noir dawn\",")
        assertEquals("noir dawn", customThemeNameOrNull(raw))
        assertTrue(parseCustomTheme(raw).isSuccess)
    }

    @Test fun missingNameFallsBackToNull() {
        assertNull(customThemeNameOrNull(themeJson()))
    }

    @Test fun blankNameFallsBackToNull() {
        assertNull(customThemeNameOrNull(themeJson("\"name\": \"  \",")))
    }

    @Test fun nonStringNameFallsBackToNull() {
        assertNull(customThemeNameOrNull(themeJson("\"name\": 123,")))
    }

    @Test fun brokenFileHasNoName() {
        assertNull(customThemeNameOrNull("not json"))
        assertNull(customThemeNameOrNull(""))
    }
}
