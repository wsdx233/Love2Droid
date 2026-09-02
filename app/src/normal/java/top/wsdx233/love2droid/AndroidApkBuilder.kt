package top.wsdx233.love2droid

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File

internal enum class AndroidPackageStage {
    PACKAGE_GAME,
    ASSEMBLE_APK,
    SIGN_APK,
}

internal class AndroidApkBuilder(context: Context) {
    private val appContext = context.applicationContext
    private val signingStore = AndroidSigningStore(appContext)
    private val outputRoot = File(appContext.cacheDir, OUTPUT_DIRECTORY)

    fun build(project: Project, onStage: (AndroidPackageStage) -> Unit = {}): File {
        val properties = project.androidProperties.validated()
        require(File(project.root, "main.lua").isFile) { "main.lua is missing" }
        if (!outputRoot.exists()) check(outputRoot.mkdirs()) { "Unable to create Android package directory" }
        outputRoot.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") }?.forEach(File::delete)

        onStage(AndroidPackageStage.PACKAGE_GAME)
        val lovePackage = LovePackageBuilder.build(project, appContext.cacheDir)
        val unsigned = File(outputRoot, "${project.id}-${properties.versionCode}-unsigned.apk")
        val output = File(outputRoot, "${project.id}-${properties.versionCode}.apk")
        unsigned.delete()
        output.delete()
        try {
            onStage(AndroidPackageStage.ASSEMBLE_APK)
            AndroidApkAssembler.assemble(
                templateInput = appContext.assets.open(TEMPLATE_ASSET),
                runtimeApks = runtimeApks(),
                properties = properties,
                lovePackage = lovePackage,
                icon = projectIcon(project),
                target = unsigned,
            )
            onStage(AndroidPackageStage.SIGN_APK)
            ApkSigner.Builder(listOf(signingStore.signerConfig(project)))
                .setInputApk(unsigned)
                .setOutputApk(output)
                .setMinSdkVersion(23)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .setV4SigningEnabled(false)
                .build()
                .sign()
            check(output.isFile && output.length() > 0L) { "APK signing produced no output" }
            return output
        } finally {
            unsigned.delete()
        }
    }

    fun signingFingerprint(project: Project): String? = signingStore.fingerprint(project)

    fun importSigningKey(project: Project, uri: android.net.Uri, password: CharArray): String =
        signingStore.importPkcs12(project, uri, password)


    private fun runtimeApks(): List<File> = buildList {
        add(File(appContext.applicationInfo.sourceDir))
        appContext.applicationInfo.splitSourceDirs?.mapTo(this, ::File)
    }.filter(File::isFile)

    private fun projectIcon(project: Project): File? =
        ProjectIconStore(appContext).iconFile(project.id)


    private companion object {
        const val TEMPLATE_ASSET = "game-template.apk"
        const val OUTPUT_DIRECTORY = "android-packages"
    }
}
