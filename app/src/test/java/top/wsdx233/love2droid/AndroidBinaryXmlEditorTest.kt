package top.wsdx233.love2droid

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class AndroidBinaryXmlEditorTest {
    @Test
    fun customizesGeneratedGameManifest() {
        val template = File(requireNotNull(System.getProperty("love2droid.gameTemplateApk")))
        val source = ZipFile(template).use { zip ->
            zip.getInputStream(requireNotNull(zip.getEntry("AndroidManifest.xml"))).use { it.readBytes() }
        }
        val properties = AndroidProjectProperties(
            appName = "测试游戏",
            applicationId = "io.example.customgame",
            versionName = "2.5.7-beta",
            versionCode = 257,
            orientation = GameScreenOrientation.SENSOR_PORTRAIT,
            permissions = setOf("android.permission.INTERNET", "io.example.permission.SAVE"),
            signingKeyId = "manifest-test",
        )

        val customized = AndroidBinaryXmlEditor.customizeManifest(source, properties)

        assertTrue(customized.isNotEmpty())
        assertNotEquals(source.toList(), customized.toList())
    }
}
