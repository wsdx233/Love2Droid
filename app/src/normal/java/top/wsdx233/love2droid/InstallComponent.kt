package top.wsdx233.love2droid

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import java.io.File

/**
 * Metadata and specification for a modular, installable PRoot environment component.
 */
interface InstallComponent {
    val id: String
    @get:StringRes
    val displayNameRes: Int
    @get:StringRes
    val descriptionRes: Int
    @get:DrawableRes
    val iconRes: Int
    val downloadSizeEstimateMb: Int
    val diskSizeEstimateMb: Int

    /**
     * ID of other components required before this one can function.
     * Most tools depend on [InstallRegistry.ID_ROOTFS].
     */
    val dependencies: Set<String>

    /**
     * Whether this component is strictly required for any guest Linux functionality.
     */
    val isRequired: Boolean

    /**
     * Checks if this component's binaries and marker are in a fully functional state.
     */
    fun isInstalled(context: Context): Boolean

    /**
     * Marker file indicating successful and complete installation of this specific component.
     */
    fun readyMarker(context: Context): File

    /**
     * Installs this component.
     * @param onProgress Callback receiving progress (0..100) and step description.
     */
    suspend fun install(
        context: Context,
        onProgress: (progress: Int, message: String) -> Unit,
    )
}

/**
 * Central registry of modular installable components.
 */
object InstallRegistry {
    const val ID_ROOTFS = "rootfs"
    const val ID_LSP = "lua_lsp"
    const val ID_OMP = "omp"
    const val ID_DSH = "dsh"
    const val ID_GIT = "git"
    const val ID_LOVE_CHECK = "love_check"

    val availableComponents: List<InstallComponent> by lazy {
        listOf(
            RootfsComponent,
            LuaLspComponent,
            LoveCheckComponent,
            OmpComponent,
            DshComponent,
            GitComponent,
        )
    }

    fun find(id: String): InstallComponent? = availableComponents.firstOrNull { it.id == id }
}

object RootfsComponent : InstallComponent {
    override val id: String = InstallRegistry.ID_ROOTFS
    override val displayNameRes: Int = R.string.components_base_rootfs_name
    override val descriptionRes: Int = R.string.components_base_rootfs_desc
    override val iconRes: Int = R.drawable.terminal_rounded
    override val downloadSizeEstimateMb: Int = 35
    override val diskSizeEstimateMb: Int = 110
    override val dependencies: Set<String> = emptySet()
    override val isRequired: Boolean = true

    override fun isInstalled(context: Context): Boolean = ProotRuntime.isRootfsReady(context)

    override fun readyMarker(context: Context): File = ProotRuntime.rootfsReadyMarker(context)

    override suspend fun install(context: Context, onProgress: (progress: Int, message: String) -> Unit) {
        ProotInstaller.install(context, setOf(id))
    }
}

object LuaLspComponent : InstallComponent {
    override val id: String = InstallRegistry.ID_LSP
    override val displayNameRes: Int = R.string.components_lua_lsp_name
    override val descriptionRes: Int = R.string.components_lua_lsp_desc
    override val iconRes: Int = R.drawable.code_rounded
    override val downloadSizeEstimateMb: Int = 15
    override val diskSizeEstimateMb: Int = 45
    override val dependencies: Set<String> = setOf(InstallRegistry.ID_ROOTFS)
    override val isRequired: Boolean = false

    override fun isInstalled(context: Context): Boolean = ProotRuntime.isLspReady(context)

    override fun readyMarker(context: Context): File = ProotRuntime.lspReadyMarker(context)

    override suspend fun install(context: Context, onProgress: (progress: Int, message: String) -> Unit) {
        ProotInstaller.install(context, setOf(id))
    }
}

object LoveCheckComponent : InstallComponent {
    override val id: String = InstallRegistry.ID_LOVE_CHECK
    override val displayNameRes: Int = R.string.components_love_check_name
    override val descriptionRes: Int = R.string.components_love_check_desc
    override val iconRes: Int = R.drawable.code_rounded
    override val downloadSizeEstimateMb: Int = 100
    override val diskSizeEstimateMb: Int = 350
    override val dependencies: Set<String> = setOf(InstallRegistry.ID_ROOTFS)
    override val isRequired: Boolean = false

    override fun isInstalled(context: Context): Boolean = LoveCheckRuntime.isReady(context)

    override fun readyMarker(context: Context): File = LoveCheckRuntime.readyMarker(context)

    override suspend fun install(context: Context, onProgress: (progress: Int, message: String) -> Unit) {
        ProotInstaller.install(context, setOf(id)).getOrThrow()
    }
}

object OmpComponent : InstallComponent {
    override val id: String = InstallRegistry.ID_OMP
    override val displayNameRes: Int = R.string.components_omp_name
    override val descriptionRes: Int = R.string.components_omp_desc
    override val iconRes: Int = R.drawable.smart_toy_rounded
    override val downloadSizeEstimateMb: Int = 16
    override val diskSizeEstimateMb: Int = 20
    override val dependencies: Set<String> = setOf(InstallRegistry.ID_ROOTFS)
    override val isRequired: Boolean = false

    override fun isInstalled(context: Context): Boolean = ProotRuntime.isOmpReady(context)

    override fun readyMarker(context: Context): File = ProotRuntime.ompReadyMarker(context)

    override suspend fun install(context: Context, onProgress: (progress: Int, message: String) -> Unit) {
        ProotInstaller.install(context, setOf(id))
    }
}

object DshComponent : InstallComponent {
    override val id: String = InstallRegistry.ID_DSH
    override val displayNameRes: Int = R.string.components_dsh_name
    override val descriptionRes: Int = R.string.components_dsh_desc
    override val iconRes: Int = R.drawable.auto_awesome_rounded
    override val downloadSizeEstimateMb: Int = 45
    override val diskSizeEstimateMb: Int = 180
    override val dependencies: Set<String> = setOf(InstallRegistry.ID_ROOTFS)
    override val isRequired: Boolean = false

    override fun isInstalled(context: Context): Boolean = ProotRuntime.isDshReady(context)

    override fun readyMarker(context: Context): File = ProotRuntime.dshReadyMarker(context)

    override suspend fun install(context: Context, onProgress: (progress: Int, message: String) -> Unit) {
        ProotInstaller.install(context, setOf(id))
    }
}

object GitComponent : InstallComponent {
    override val id: String = InstallRegistry.ID_GIT
    override val displayNameRes: Int = R.string.components_git_name
    override val descriptionRes: Int = R.string.components_git_desc
    override val iconRes: Int = R.drawable.commit_rounded
    override val downloadSizeEstimateMb: Int = 25
    override val diskSizeEstimateMb: Int = 80
    override val dependencies: Set<String> = setOf(InstallRegistry.ID_ROOTFS)
    override val isRequired: Boolean = false

    override fun isInstalled(context: Context): Boolean = ProotRuntime.isGitReady(context)

    override fun readyMarker(context: Context): File = File(ProotRuntime.runtimeDir(context), ".git-complete")

    override suspend fun install(context: Context, onProgress: (progress: Int, message: String) -> Unit) {
        GitClient(context).ensureInstalled { progressText ->
            onProgress(50, progressText)
        }
        readyMarker(context).createNewFile()
    }
}
