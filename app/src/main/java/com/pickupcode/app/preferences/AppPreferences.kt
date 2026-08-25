package com.pickupcode.app.preferences

import android.content.Context
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 应用级单例 DataStore（对应文件 settings.preferences_pb，随 App 数据目录保存）。 */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 全局设置集中管理（DataStore Preferences 封装）。
 *
 * 所有设置项的读写统一走本对象：读取用 [observe] 订阅 Flow（UI 组合期 collectAsState），
 * 写入用对应的 setXxx 方法。Key 名与默认值在此单一维护，新增设置项需同步 [Settings] 与 [observe]。
 *
 * 安全说明：API Key（AI / 高德 / 快递100）经 AndroidKeyStore AES-GCM 加密后存 DataStore
 * （B6）。密钥在 Keystore 内不可导出；备份恢复/换机后密钥丢失，旧密文解密失败按空值处理，
 * 用户需重新输入。首次升级自动解密旧明文（非 "v1:" 前缀视为明文原样返回）。
 */
object AppPreferences {

    private const val TAG = "AppPreferences"

    /** 识别置信度阈值（默认 0.5）：低于阈值的正则结果不展示（AI 结果目前不过此阈值）。 */
    private val KEY_CONFIDENCE_THRESHOLD = floatPreferencesKey("confidence_threshold")

    /** 是否识别取餐码（餐饮场景，如瑞幸 A12）。 */
    private val KEY_ENABLE_FOOD = booleanPreferencesKey("enable_food")

    /** 是否识别取件码（快递场景，如丰巢 1-2-3456）。 */
    private val KEY_ENABLE_PARCEL = booleanPreferencesKey("enable_parcel")

    /** 是否识别券码（二维码，走 ML Kit Barcode 解码，与取餐/取件码互斥）。 */
    private val KEY_ENABLE_COUPON = booleanPreferencesKey("enable_coupon")

    /** 主题模式："system" 跟随系统 / "light" / "dark"（对应 Theme.kt 的三态）。 */
    private val KEY_DARK_MODE = stringPreferencesKey("dark_mode")

    /** AI 识别 API Key（任意 OpenAI 兼容服务；B6 加密存储：AndroidKeyStore AES-GCM 密文写入 DataStore）。 */
    private val KEY_API_KEY = stringPreferencesKey("api_key")

    /** AI 识别 API Base URL（默认 OpenAI 官方，可换任意兼容服务）。 */
    private val KEY_API_BASE_URL = stringPreferencesKey("api_base_url")

    /** AI 识别模型名（默认 gpt-4o-mini）。 */
    private val KEY_API_MODEL = stringPreferencesKey("api_model")

    /** 是否启用 AI 增强识别（与正则并行，失败不影响主流程）。 */
    private val KEY_ENABLE_AI = booleanPreferencesKey("enable_ai")

    /** 是否接收外部分享（ACTION_SEND：分享面板入口）。 */
    private val KEY_ENABLE_INTENT_RECEIVE = booleanPreferencesKey("enable_intent_receive")

    /** 是否响应文字分享检测（ACTION_PROCESS_TEXT：长按选中文字路径）。 */
    private val KEY_ENABLE_SHARE_DETECTION = booleanPreferencesKey("enable_share_detection")

    /** 是否启用地图地址验证（Android Geocoder + 高德，配 amap key 可提精度）。 */
    private val KEY_ENABLE_MAP_VERIFY = booleanPreferencesKey("enable_map_verify")

    /** 高德 API Key（可选，仅提升地址验证精度；B6 加密存储）。 */
    private val KEY_AMAP_API_KEY = stringPreferencesKey("amap_api_key")

    /** 是否启用快递100 反向验证（运单号查取件码/地址，校验 OCR 结果）。 */
    private val KEY_ENABLE_KUAIDI100 = booleanPreferencesKey("enable_kuaidi100")

    /** 快递100 开放平台 API Key（B6 加密存储）。 */
    private val KEY_KUAIDI100_KEY = stringPreferencesKey("kuaidi100_key")

    /** 是否隐藏主页的无障碍服务引导卡片（首次设置完成后可关）。 */
    private val KEY_HIDE_ACCESSIBILITY_CARD = booleanPreferencesKey("hide_accessibility_card")
    /** 主页「怎么添加取件码」引导卡是否已隐藏（永久） */
    private val KEY_HIDE_GUIDE_CARD = booleanPreferencesKey("hide_guide_card")

    /** 是否接收短信取件码自动识别（需 READ_SMS 权限；参考同类产品实现）。 */
    private val KEY_ENABLE_SMS_RECEIVE = booleanPreferencesKey("enable_sms_receive")

    /** 是否启用到期提醒（快递码存放 3 天/文本时限到达时自动提醒；v6）。 */
    private val KEY_ENABLE_EXPIRY_REMIND = booleanPreferencesKey("enable_expiry_remind")

    /** 全部设置项的聚合快照：observe 的每次发射即一个不可变副本。 */
    data class Settings(
        val confidenceThreshold: Float = 0.5f,
        val enableFoodCodes: Boolean = true,
        val enableParcelCodes: Boolean = true,
        val enableCouponCodes: Boolean = true,
        val darkMode: String = "system",
        val apiKey: String = "",
        val apiBaseUrl: String = "https://api.openai.com/v1",
        val apiModel: String = "gpt-4o-mini",
        val enableAI: Boolean = false,  // 默认关闭：避免配置 Key 后静默外发 OCR/短信全文；用户显式开启
        val enableIntentReceive: Boolean = true,
        val enableShareDetection: Boolean = true,
        val enableMapVerify: Boolean = false,
        val amapApiKey: String = "",
        val enableKuaidi100: Boolean = false,
        val kuaidi100Key: String = "",
        val hideAccessibilityCard: Boolean = false,
        val hideGuideCard: Boolean = false,
        val enableSmsReceive: Boolean = false,
        val enableExpiryRemind: Boolean = true
    )

    /** 订阅设置 Flow：任一 key 变化即发射新的 [Settings] 快照；UI 侧用 collectAsState 消费。 */
    fun observe(context: Context): Flow<Settings> {
        return context.dataStore.data.map { prefs ->
            Settings(
                confidenceThreshold = prefs[KEY_CONFIDENCE_THRESHOLD] ?: 0.5f,
                enableFoodCodes = prefs[KEY_ENABLE_FOOD] ?: true,
                enableParcelCodes = prefs[KEY_ENABLE_PARCEL] ?: true,
                enableCouponCodes = prefs[KEY_ENABLE_COUPON] ?: true,
                darkMode = prefs[KEY_DARK_MODE] ?: "system",
                apiKey = decrypt(prefs[KEY_API_KEY] ?: ""),
                apiBaseUrl = prefs[KEY_API_BASE_URL] ?: "https://api.openai.com/v1",
                apiModel = prefs[KEY_API_MODEL] ?: "gpt-4o-mini",
                enableAI = prefs[KEY_ENABLE_AI] ?: false,
                enableIntentReceive = prefs[KEY_ENABLE_INTENT_RECEIVE] ?: true,
                enableShareDetection = prefs[KEY_ENABLE_SHARE_DETECTION] ?: true,
                enableMapVerify = prefs[KEY_ENABLE_MAP_VERIFY] ?: false,
                amapApiKey = decrypt(prefs[KEY_AMAP_API_KEY] ?: ""),
                enableKuaidi100 = prefs[KEY_ENABLE_KUAIDI100] ?: false,
                kuaidi100Key = decrypt(prefs[KEY_KUAIDI100_KEY] ?: ""),
                hideAccessibilityCard = prefs[KEY_HIDE_ACCESSIBILITY_CARD] ?: false,
                hideGuideCard = prefs[KEY_HIDE_GUIDE_CARD] ?: false,
                enableSmsReceive = prefs[KEY_ENABLE_SMS_RECEIVE] ?: false,
                enableExpiryRemind = prefs[KEY_ENABLE_EXPIRY_REMIND] ?: true
            )
        }
    }

    /** 到期提醒开关（供入库管线排程前检查）。 */
    suspend fun isExpiryRemindEnabled(context: Context): Boolean =
        context.dataStore.data.first()[KEY_ENABLE_EXPIRY_REMIND] ?: true

    suspend fun setEnableExpiryRemind(context: Context, value: Boolean) =
        write(context, KEY_ENABLE_EXPIRY_REMIND, value)

    // ── 泛化写入：消除 18 个重复的 dataStore.edit 样板 ──
    private suspend fun <T> write(context: Context, key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    /** 加密字符串写入（API Key 类）。 */
    private suspend fun writeEncrypted(context: Context, key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = encrypt(value) }
    }

    suspend fun setConfidenceThreshold(context: Context, value: Float) =
        write(context, KEY_CONFIDENCE_THRESHOLD, value)

    suspend fun setEnableFood(context: Context, value: Boolean) =
        write(context, KEY_ENABLE_FOOD, value)

    suspend fun setEnableParcel(context: Context, value: Boolean) =
        write(context, KEY_ENABLE_PARCEL, value)

    suspend fun setEnableCoupon(context: Context, value: Boolean) =
        write(context, KEY_ENABLE_COUPON, value)

    suspend fun setDarkMode(context: Context, value: String) =
        write(context, KEY_DARK_MODE, value)

    suspend fun setApiKey(context: Context, value: String) =
        writeEncrypted(context, KEY_API_KEY, value)

    suspend fun setApiBaseUrl(context: Context, value: String) =
        write(context, KEY_API_BASE_URL, value)

    suspend fun setApiModel(context: Context, value: String) =
        write(context, KEY_API_MODEL, value)

    suspend fun setEnableAI(context: Context, value: Boolean) =
        write(context, KEY_ENABLE_AI, value)

    suspend fun setEnableIntentReceive(context: Context, value: Boolean) =
        write(context, KEY_ENABLE_INTENT_RECEIVE, value)

    suspend fun setEnableShareDetection(context: Context, value: Boolean) =
        write(context, KEY_ENABLE_SHARE_DETECTION, value)

    suspend fun setEnableMapVerify(context: Context, value: Boolean) =
        write(context, KEY_ENABLE_MAP_VERIFY, value)

    suspend fun setAmapApiKey(context: Context, value: String) =
        writeEncrypted(context, KEY_AMAP_API_KEY, value)

    suspend fun setEnableKuaidi100(context: Context, value: Boolean) =
        write(context, KEY_ENABLE_KUAIDI100, value)

    suspend fun setKuaidi100Key(context: Context, value: String) =
        writeEncrypted(context, KEY_KUAIDI100_KEY, value)

    suspend fun setHideAccessibilityCard(context: Context, value: Boolean) =
        write(context, KEY_HIDE_ACCESSIBILITY_CARD, value)

    suspend fun setHideGuideCard(context: Context, value: Boolean) =
        write(context, KEY_HIDE_GUIDE_CARD, value)

    suspend fun setEnableSmsReceive(context: Context, value: Boolean) =
        write(context, KEY_ENABLE_SMS_RECEIVE, value)

    // ---------------------------------------------------------------
    // B6: API Key 加密（AndroidKeyStore AES-GCM，密文存 DataStore）
    // ---------------------------------------------------------------

    private const val KEYSTORE_ALIAS = "pickup_code_keys"
    private const val ENC_PREFIX = "v1:"
    private val AES_TRANSFORM = "AES/GCM/NoPadding"

    /** 取/生成 Keystore 内不可导出的 AES 密钥（备份恢复后密钥丢失→解密失败按空值处理）。 */
    private fun keystoreKey(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey) ?: run {
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
    } catch (_: Exception) {
        null
    }

    /** 加密明文；空串原样返回（保持默认值语义）；Keystore 不可用或加密失败时抛异常拒绝存储（H2）。 */
    private fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        val key = keystoreKey()
            ?: throw IllegalStateException("AndroidKeyStore 密钥不可用，拒绝明文存储 API Key")
        return try {
            val cipher = Cipher.getInstance(AES_TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            ENC_PREFIX + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                "." + Base64.encodeToString(ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            // H2: 加密失败拒绝明文落盘，抛异常让写入失败（调用方 runCatching 捕获，保留旧值）
            throw IllegalStateException("AES-GCM 加密失败，拒绝明文存储 API Key", e)
        }
    }

    /** 解密存储值；非密文（旧明文/空）原样返回，密钥丢失/损坏返回空串。 */
    private fun decrypt(stored: String): String {
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
            Log.w(TAG, "AES-GCM 解密失败，API Key 需重新输入", e)
            ""
        }
    }
}
