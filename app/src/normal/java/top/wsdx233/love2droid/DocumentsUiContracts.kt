package top.wsdx233.love2droid

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * SAF contracts that prefer the AOSP DocumentsUI implementations used by R2Droid.
 * Some vendor file pickers do not implement the standard contracts consistently.
 */
internal object DocumentsUiPackages {
    private val preferred = listOf(
        "com.google.android.documentsui",
        "com.android.documentsui",
    )

    fun prefer(context: Context, source: Intent): Intent {
        val packageManager = context.packageManager
        val packageName = preferred.firstOrNull { candidate ->
            Intent(source).setPackage(candidate).resolveActivity(packageManager) != null
        }
        return source.setPackage(packageName)
    }
}

internal class DocumentsUiOpenDocumentTreeContract : ActivityResultContract<Uri?, Uri?>() {
    private val delegate = ActivityResultContracts.OpenDocumentTree()

    override fun createIntent(context: Context, input: Uri?): Intent =
        DocumentsUiPackages.prefer(context, delegate.createIntent(context, input))

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        delegate.parseResult(resultCode, intent)
}

internal class DocumentsUiCreateDocumentContract(
    mimeType: String = "*/*",
) : ActivityResultContract<String, Uri?>() {
    private val delegate = ActivityResultContracts.CreateDocument(mimeType)

    override fun createIntent(context: Context, input: String): Intent =
        DocumentsUiPackages.prefer(context, delegate.createIntent(context, input))

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        delegate.parseResult(resultCode, intent)
}
internal class DocumentsUiOpenDocumentContract : ActivityResultContract<Array<String>, Uri?>() {
    private val delegate = ActivityResultContracts.OpenDocument()

    override fun createIntent(context: Context, input: Array<String>): Intent =
        DocumentsUiPackages.prefer(context, delegate.createIntent(context, input))

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        delegate.parseResult(resultCode, intent)
}
