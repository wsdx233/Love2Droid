package top.wsdx233.love2droid

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import javax.security.auth.x500.X500Principal

internal class AndroidSigningStore(context: Context) {
    private val appContext = context.applicationContext

    fun signerConfig(project: Project): ApkSigner.SignerConfig {
        val alias = alias(project.androidProperties.signingKeyId)
        val store = androidKeyStore()
        if (!store.containsAlias(alias)) generate(alias, project.androidProperties.appName)
        return signerConfig(store, alias)
    }

    fun fingerprint(project: Project): String? {
        val certificate = androidKeyStore().getCertificate(alias(project.androidProperties.signingKeyId)) ?: return null
        return sha256(certificate.encoded).joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xff) }
    }

    fun importPkcs12(project: Project, uri: Uri, password: CharArray): String {
        val imported = KeyStore.getInstance("PKCS12")
        appContext.contentResolver.openInputStream(uri)?.use { input -> imported.load(input, password) }
            ?: error("Unable to read signing key")
        val sourceAlias = imported.aliases().toList().firstOrNull(imported::isKeyEntry)
            ?: error("PKCS#12 contains no private key")
        val key = imported.getKey(sourceAlias, password) as? PrivateKey
            ?: error("PKCS#12 private key is invalid")
        val chain = imported.getCertificateChain(sourceAlias)
            ?.map { certificate -> certificate as X509Certificate }
            ?.toTypedArray()
            ?: error("PKCS#12 certificate chain is missing")
        require(key.algorithm == "RSA" || key.algorithm == "EC") { "Only RSA and EC signing keys are supported" }

        val targetAlias = alias(project.androidProperties.signingKeyId)
        val protection = KeyStore.PasswordProtection(null)
        val target = androidKeyStore()
        if (target.containsAlias(targetAlias)) target.deleteEntry(targetAlias)
        try {
            target.setEntry(targetAlias, KeyStore.PrivateKeyEntry(key, chain), protection)
        } catch (_: Throwable) {
            val builder = android.security.keystore.KeyProtection.Builder(
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            ).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            if (key.algorithm == "RSA") builder.setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            target.setEntry(targetAlias, KeyStore.PrivateKeyEntry(key, chain), builder.build())
        }
        password.fill('\u0000')
        return fingerprint(project) ?: error("Imported signing certificate is unavailable")
    }

    private fun generate(alias: String, appName: String) {
        val now = Date()
        val expires = Calendar.getInstance().apply {
            time = now
            add(Calendar.YEAR, CERTIFICATE_VALIDITY_YEARS)
        }.time
        val serial = BigInteger(1, sha256(alias.toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)).max(BigInteger.ONE)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEY_STORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setKeySize(RSA_KEY_SIZE)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=${sanitizeSubject(appName)}, O=Love2Droid"))
                .setCertificateSerialNumber(serial)
                .setCertificateNotBefore(now)
                .setCertificateNotAfter(expires)
                .build(),
        )
        generator.generateKeyPair()
    }

    private fun signerConfig(store: KeyStore, alias: String): ApkSigner.SignerConfig {
        val privateKey = store.getKey(alias, null) as? PrivateKey ?: error("Signing private key is unavailable")
        val certificates = store.getCertificateChain(alias)
            ?.map { certificate -> certificate as X509Certificate }
            ?: error("Signing certificate chain is unavailable")
        return ApkSigner.SignerConfig.Builder(alias, privateKey, certificates).build()
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun alias(signingKeyId: String): String =
        "love2droid-${sha256(signingKeyId.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }}"
    private fun sanitizeSubject(value: String): String = value
        .replace(Regex("[,=+<>#;\\\"]"), "_")
        .take(64)
        .ifBlank { "Love2D Game" }

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val RSA_KEY_SIZE = 2048
        const val CERTIFICATE_VALIDITY_YEARS = 30
    }
}
