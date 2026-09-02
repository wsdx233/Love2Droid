package top.wsdx233.love2droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProjectPropertiesTest {
    @Test
    fun defaultsNormalizeProjectIdIntoValidApplicationId() {
        val properties = AndroidProjectProperties.defaults("12-My Game", "My Game")

        assertEquals("My Game", properties.appName)
        assertEquals("com.love2d.game_12_my_game", properties.applicationId)
        assertEquals(GameScreenOrientation.LANDSCAPE, properties.orientation)
        assertEquals(AndroidProjectProperties.RECOMMENDED_PERMISSIONS, properties.permissions)
    }

    @Test
    fun validationRejectsMalformedApplicationIdsAndPermissions() {
        assertTrue(AndroidProjectProperties.isValidApplicationId("com.example.game"))
        assertFalse(AndroidProjectProperties.isValidApplicationId("com.example.2game"))
        assertTrue(AndroidProjectProperties.isValidPermissionName("android.permission.CAMERA"))
        assertFalse(AndroidProjectProperties.isValidPermissionName("android.permission.BAD-NAME"))
    }
}
