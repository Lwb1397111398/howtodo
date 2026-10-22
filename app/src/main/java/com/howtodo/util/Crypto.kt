package com.howtodo.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 的本地加密存储。
 *
 * 使用 Android Keystore 里的 AES-256-GCM 密钥，密钥本身不出安全硬件/系统密钥库，
 * 数据库里只保存密文 + IV。不用已废弃的 androidx.security-crypto。
 *
 * 注意：应用卸载或清除数据后 Keystore 密钥会被销毁，此时解密失败，需要用户重新填写 Key。
 */
object Crypto {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "howtodo_master"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val entry = ks.getEntry(ALIAS, null)
        if (entry is KeyStore.SecretKeyEntry) return entry.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** @return Pair(密文, IV) */
    fun encrypt(plain: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return ct to cipher.iv
    }

    /** 解密失败（密钥被清除/数据来自其他设备）返回 null，由调用方提示重新填写 */
    fun decrypt(cipherText: ByteArray, iv: ByteArray): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    } catch (_: Throwable) {
        null
    }

    fun mask(key: String?): String {
        if (key.isNullOrBlank()) return "未设置"
        if (key.length <= 8) return "••••"
        return key.take(4) + "••••••" + key.takeLast(4)
    }
}
