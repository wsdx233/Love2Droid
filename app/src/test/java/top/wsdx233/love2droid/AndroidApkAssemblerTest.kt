package top.wsdx233.love2droid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class AndroidApkAssemblerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun assemblesTemplateRuntimeAndGameWithoutEditorOnlyFiles() {
        val template = File(requireNotNull(System.getProperty("love2droid.gameTemplateApk")))
        val runtime = temporary.newFile("runtime.apk")
        ZipOutputStream(runtime.outputStream()).use { zip ->
            listOf(
                "classes.dex",
                "classes2.dex",
                "lib/arm64-v8a/liblove.so",
                "lib/arm64-v8a/libliblove.so",
                "lib/arm64-v8a/libtermux.so",
                "assets/textmate/theme.json",
            ).forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        val lovePackage = temporary.newFile("game.love")
        ZipOutputStream(lovePackage.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("main.lua"))
            zip.write("function love.draw() end".toByteArray())
            zip.closeEntry()
        }
        val output = File(requireNotNull(System.getProperty("love2droid.testApkOutput"))).apply {
            parentFile?.mkdirs()
            delete()
        }
        AndroidApkAssembler.assemble(
            templateInput = FileInputStream(template),
            runtimeApks = listOf(runtime),
            properties = AndroidProjectProperties(
                appName = "Assembler Game",
                applicationId = "io.example.assembler",
                versionName = "3.0",
                versionCode = 30,
                orientation = GameScreenOrientation.PORTRAIT,
                permissions = setOf("android.permission.INTERNET"),
                signingKeyId = "assembler-test",
            ),
            lovePackage = lovePackage,
            icon = null,
            target = output,
        )

        ZipFile(output).use { apk ->
            assertNotNull(apk.getEntry("AndroidManifest.xml"))
            assertNotNull(apk.getEntry("resources.arsc"))
            assertNotNull(apk.getEntry("res/drawable/icon.png"))
            assertNotNull(apk.getEntry("classes.dex"))
            assertNotNull(apk.getEntry("classes2.dex"))
            assertNotNull(apk.getEntry("lib/arm64-v8a/liblove.so"))
            assertNotNull(apk.getEntry("assets/game.love"))
            assertTrue(apk.getEntry("assets/game.love").method == ZipEntry.STORED)
            assertFalse(apk.entries().toList().any { it.name.contains("termux") })
            assertFalse(apk.entries().toList().any { it.name.startsWith("assets/textmate/") })
        }
    }
}
