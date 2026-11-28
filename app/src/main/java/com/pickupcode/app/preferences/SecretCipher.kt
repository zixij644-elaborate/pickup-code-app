package com.pickupcode.app.preferences

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore AES-GCM 加解密（从 [AppPreferences] 抽出，供多处复用）。
 *
 * 背景：原先这套逻辑是 [AppPreferences] 的 private 实现，只有 API Key 能用；
 * 「常用取件地址」是 PII（家/公司），按检查报告 3-5 也要求加密落盘，故抽成共享工具。
 *
 * 行为（与抽出前完全一致，保持既有密文可解）：
 *  - 密文格式：`v1:<base64(iv)>.<base64(ciphertext)>`；密钥别名与 [AppPreferences] 相同，**旧数据无需迁移**。
 *  - [encrypt] 空串原样返回（保持"未设置"语义）；Keystore 不可用或加密失败时**抛异常拒绝明文落盘**。
 *  - [decrypt] 非 `v1:` 前缀（历史明文）原样返回；密钥丢失/密文损坏返回空串。
 *
 * 注意（检查报告 3-18 的坑）：调用方**不要在识别热路径/主线程反复解密**——
 * 读一次到内存缓存，写时再解密改写。
 */
object SecretCipher {

    private const val TAG = "SecretCipher"
    private const val KEYSTORE_ALIAS = "pickup_code_keys"
    private const val ENC_PREFIX = "v1:"
    private const val AES_TRANSFORM = "AES/GCM/NoPadding"

    @Volatile
    private var cachedKey: SecretKey? = null

    /** 是否是本工具产出的密文。 */
    fun isEncrypted(value: String): Boolean = value.startsWith(ENC_PREFIX)

    /** 取/生成 Keystore 内不可导出的 AES 密钥（进程内缓存，避免反复 KeyStore.load 走 Binder）。 */
    private fun keystoreKey(): SecretKey? {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey?.let { return it }
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val key = (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey) ?: run {
                    val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    g.init(
                        KeyGenParameterSpec.Builder(
                            KEYSTORE_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build()
                    )
                    g.generateKey()
                }
                cachedKey = key
                key
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 加密明文；空串原样返回。
     * @param what 出错信息里的人类可读名称（如 "API Key" / "常用取件地址"），便于定位。
     * @throws IllegalStateException Keystore 不可用或加密失败 —— **拒绝明文落盘**。
     */
    fun encrypt(plain: String, what: String = "敏感数据"): String {
        if (plain.isEmpty()) return plain
        val key = keystoreKey()
            ?: throw IllegalStateException("AndroidKeyStore 密钥不可用，拒绝明文存储$what")
        return try {
            val cipher = Cipher.getInstance(AES_TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            ENC_PREFIX + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                "." + Base64.encodeToString(ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw IllegalStateException("AES-GCM 加密失败，拒绝明文存储$what", e)
        }
    }

    /** 解密存储值；非密文（旧明文/空）原样返回，密钥丢失/损坏返回空串。 */
    fun decrypt(stored: String, what: String = "敏感数据"): String {
        if (stored.isEmpty() || !stored.startsWith(ENC_PREFIX)) return stored
        return try {
            val body = stored.removePrefix(ENC_PREFIX)
            val parts = body.split(".", limit = 2)
            if (parts.size != 2) return ""
            val cipher = Cipher.getInstance(AES_TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keystoreKey() ?: return "",
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "AES-GCM 解密失败（$what 需重新录入）", e)
            ""
        }
    }
}
