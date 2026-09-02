package top.wsdx233.love2droid

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test
    fun invalidStoredThemeFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("unknown"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
    }

    @Test
    fun editorThemeResolvesExplicitModes() {
        assertEquals("quietlight", ThemeMode.LIGHT.resolveEditorThemeId(systemIsDark = true))
        assertEquals("darcula", ThemeMode.DARK.resolveEditorThemeId(systemIsDark = false))
    }

    @Test
    fun editorSystemThemeFollowsSystemBrightness() {
        assertEquals("quietlight", ThemeMode.SYSTEM.resolveEditorThemeId(systemIsDark = false))
        assertEquals("darcula", ThemeMode.SYSTEM.resolveEditorThemeId(systemIsDark = true))
    }

    @Test
    fun appThemeResolvesToAppCompatMode() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeMode.LIGHT.toAppCompatNightMode())
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeMode.DARK.toAppCompatNightMode())
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeMode.SYSTEM.toAppCompatNightMode())
    }
}
