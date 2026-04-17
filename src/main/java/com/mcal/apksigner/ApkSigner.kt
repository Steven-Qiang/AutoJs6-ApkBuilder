package com.mcal.apksigner

import com.android.apksig.ApkSigner
import com.mcal.apksigner.utils.KeyStoreHelper
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

class ApkSigner(
    private val unsignedApkFile: File,
    private val signedApkFile: File,
) {
    var useDefaultSignatureVersion = true
    var v1SigningEnabled = true
    var v2SigningEnabled = true
    var v3SigningEnabled = true
    var v4SigningEnabled = false

    fun signRelease(
        keyFile: File,
        password: String,
        alias: String,
        aliasPassword: String,
    ): Boolean {
        return try {
            val keystore = KeyStoreHelper.loadKeyStore(keyFile, password.toCharArray())
            @Suppress("DEPRECATION")
            ApkSigner.Builder(
                listOf(
                    ApkSigner.SignerConfig.Builder(
                        "CERT",
                        keystore.getKey(alias, aliasPassword.toCharArray()) as PrivateKey,
                        listOf(keystore.getCertificate(alias) as X509Certificate)
                    ).build()
                )
            ).apply {
                setInputApk(unsignedApkFile)
                setOutputApk(signedApkFile)
                if (!useDefaultSignatureVersion) {
                    setV1SigningEnabled(v1SigningEnabled)
                    setV2SigningEnabled(v2SigningEnabled)
                    setV3SigningEnabled(v3SigningEnabled)
                    setV4SigningEnabled(v4SigningEnabled)
                }
            }.build().sign()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun validateKeystorePassword(keyFile: File, password: String): Boolean {
        return KeyStoreHelper.validateKeystorePassword(keyFile, password)
    }

}
