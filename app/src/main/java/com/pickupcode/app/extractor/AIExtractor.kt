package com.pickupcode.app.extractor

import android.util.Log
import com.pickupcode.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 提取器：通过 OpenAI 兼容 API 提取取餐码/取件码（可选带地址）。
 *
 * 两条输入通道：
 * - [extract]：**纯文本**（短信原文 / 分享文本 / 本地 OCR 结果）；
 * - [extractFromImage]：**整张图片**（屏幕截图/相册图，base64 内联给视觉模型），
 *   让模型直接读图上的文字并给出码值、品牌、地址、柜号；若模型不支持图片输入会自动回退到文本通道。
 *
 * 图片只在用户开启「AI 读取图片」且 AI 已启用时才会上传（见 AppPreferences.enableAiImage）。
 */
object AIExtractor {

    private const val TAG = "AIExtractor"

    /** IPv4 点分；IPv6 由下方 contains(':') / 括号字面量另行拦截。 */
    private val IP_HOST_REGEX = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** 视觉请求的读超时：比文本长（上传 + 视觉推理），调用方的等待预算也要相应放宽。 */
    const val VISION_READ_TIMEOUT_MS = 30_000

    /** 文本请求的读超时。 */
    const val TEXT_READ_TIMEOUT_MS = 15_000

    data class AIResult(
        val code: String,
        val type: CodeExtractor.CodeType,
        val source: String,
        /** 取件点/驿站全称（视觉通道常有；文本通道多为空）。 */
        val address: String = "",
        /** 站点名（如「菜鸟驿站(长兴路店)」）；用于地址为空时的兜底。 */
        val station: String = "",
        /** 柜号/格口（如「2号柜」）。 */
        val cabinet: String = ""
    )

    /** 提取结果：results 为识别到的码；error 非空表示本次调用失败（网络/Key/解析），用于上层反馈 */
    data class AIExtractResult(
        val results: List<AIResult> = emptyList(),
        val error: String? = null,
        /** 本次是否实际走了图片通道（false = 纯文本，或图片失败已回退）。 */
        val usedImage: Boolean = false
    )

    /**
     * 连接自检结果（设置页「测试 AI 连接」用）：
     * [ok]=是否成功；[latencyMs]=耗时；[endpoint]=**实际请求的地址**（便于发现 URL 拼接错误）；
     * [httpCode]=HTTP 状态码（null 表示连请求都没发出去）；[reply]=模型回复片段；[error]=失败原因原文。
     */
    data class AIProbe(
        val ok: Boolean,
        val latencyMs: Long,
        val endpoint: String,
        val httpCode: Int? = null,
        val reply: String = "",
        val error: String = ""
    )

    /**
     * Base URL 预检（纯函数，便于单测）：返回可读错误，null = 通过。
     *
     * 真机踩坑清单（2026-09-16 诊断）：
     * - 必须 https（明文 key 不能走 http）；
     * - 必须是域名（拒绝 IP / localhost / IPv6，避免 Bearer key 发往任意端点）；
     * - **不要带 `/chat/completions`**（本类会自己拼），带了就会 404 —— 这是最常见的配置错误。
     */
    fun validateBaseUrl(apiBaseUrl: String): String? {
        val raw = apiBaseUrl.trim()
        if (raw.isBlank()) return "API 地址为空"
        val parsed = try {
            java.net.URI.create(raw)
        } catch (e: Exception) {
            return "API 地址格式不合法：${e.message}"
        }
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "https") return "API 地址必须用 https://（当前为 ${scheme ?: "无协议"}）"
        val host = parsed.host?.lowercase() ?: return "API 地址缺少域名"
        val isIpOrLocal = host == "localhost" || host.endsWith(".local") || host == "0.0.0.0" ||
            IP_HOST_REGEX.matches(host) || host.contains(':')
        if (isIpOrLocal) return "API 地址请使用域名（拒绝 IP / localhost / IPv6）"
        if (parsed.path.orEmpty().trimEnd('/').endsWith("/chat/completions")) {
            return "API 地址不要带 /chat/completions（会自动拼接）"
        }
        return null
    }

    /**
     * 失败原因归类（设置页/通知/toast 共用；原始细节只进日志或设置页自检区）。
     */
    fun categorizeError(raw: String): String {
        val e = raw.lowercase()
        return when {
            e.contains("timeout") || e.contains("timed out") || e.contains("超时") -> "AI服务超时"
            e.contains("401") || e.contains("unauthorized") || e.contains("api key") || e.contains("invalid key") ||
                e.contains("authentication") -> "AI密钥无效"
            e.contains("402") || e.contains("insufficient") || e.contains("balance") -> "AI账户余额不足"
            e.contains("429") || e.contains("rate limit") || e.contains("too many") -> "AI请求过于频繁"
            isVisionUnsupportedError(raw) -> "该模型不支持图片输入（改用 deepseek-flash 或关闭图片通道）"
            e.contains("404") || e.contains("model") && e.contains("not") -> "AI模型名不可用"
            e.contains("400") -> "AI请求被拒（检查模型名/参数）"
            e.contains("connect") || e.contains("network") || e.contains("socket") ||
                e.contains("unreachable") || e.contains("refused") || e.contains("resolve") -> "网络连接失败"
            e.contains("https") || e.contains("域名") -> "API 地址不合法"
            else -> "AI识别失败"
        }
    }

    /**
     * 是否是"模型/端点不支持图片输入"这类错误 —— 用于自动回退到纯文本通道。
     * 覆盖 DeepSeek/OpenAI 兼容服务常见的几种说法（model does not support image / invalid content type / vision unsupported）。
     */
    fun isVisionUnsupportedError(msg: String): Boolean {
        val e = msg.lowercase()
        val mentionsImage = e.contains("image") || e.contains("vision") || e.contains("multimodal") || e.contains("modal")
        val denial = e.contains("not support") || e.contains("unsupport") || e.contains("invalid") ||
            e.contains("does not") || e.contains("not allowed") || e.contains("unknown") || e.contains("unexpected")
        return mentionsImage && denial
    }

    private val SYSTEM_PROMPT = """
你是一个取件码/取餐码识别助手。用户会发来一段取件/取餐通知文字（短信、分享文本或 OCR 结果，可能带错别字、漏字、乱码），或一张手机屏幕截图。请从中提取所有取餐码和取件码。

请只回复一个纯 JSON 数组（不要 markdown、不要解释、不要任何多余文字）。数组每个元素：
{"code":"码值","type":"pickup_food或pickup_parcel","source":"品牌/驿站名","address":"取件地址或取件点全称","station":"站点名","cabinet":"柜号"}

识别规则：
- 取餐码(pickup_food)：外卖/奶茶/咖啡等餐饮的取餐号，如 "229"、"A-356"、"123"
- 取件码(pickup_parcel)：快递/驿站/快递柜的取件码，常见形状：纯数字4-8位("041327")、数字-数字("7-2914"、"3-7-4162")、字母+数字("B6-2-7041"、"D-12345")
- 码值只提取码本身，不要拼接、补零或修改；同一条通知/同一张图有多个码就全部列出；没有则回复 []
- source：承运商或品牌（如"瑞幸""菜鸟""丰巢""兔喜""中通""圆通""顺丰""极兔"）；同屏有快递公司就写快递公司，找不到写 "unknown"
- address：**该码对应的**取件地址（驿站/代收点/快递柜全称或详细地址，如"菜鸟驿站(长兴路北段店)""欢乐智柜快递柜长兴路与长青街交叉口西"）。一张图里有多个取件点时，必须按每个码各自所在的卡片/分组分别填写，**不要张冠李戴**；确实没有就留空字符串 ""
- station：取件点/驿站简称（如"菜鸟驿站""兔喜生活""妈妈驿站""欢乐智柜"），没有留空
- cabinet：柜号/格口（如"2号柜""H36"），没有留空

不要提取（这些不是取件码/取餐码）：
- 订单号、运单号、快递单号（通常很长或紧跟在"单号""运单"后）
- 手机号、客服电话、金额、价格、时间、日期
- 银行/支付类验证码、"尾号"后的数字
- 优惠券/券码（本任务只识别取餐码和取件码）
""".trimIndent()

    /** 图片通道的 user 消息文案（图片只能放在 user 消息里，DeepSeek 对 system 消息带图会 400）。 */
    private const val IMAGE_USER_PROMPT =
        "请读取这张屏幕截图，提取里面所有的取件码/取餐码；" +
            "每个码同时给出它所在卡片的取件地址(address)、站点(station)与柜号(cabinet)。按系统提示的 JSON 数组格式返回。"

    /** DeepSeek 官方域名（用于只对 DeepSeek 追加"关闭思考模式"参数，避免影响其它 OpenAI 兼容服务）。 */
    fun isDeepSeekHost(apiBaseUrl: String): Boolean =
        runCatching { java.net.URI.create(apiBaseUrl.trim()).host?.lowercase() }.getOrNull()
            ?.let { it == "api.deepseek.com" || it.endsWith(".deepseek.com") } == true

    /**
     * 需要下发的 thinking 模式：DeepSeek 默认走思考模式，而"从截图里挑取件码"是短任务，
     * 显式关闭能显著降低延迟（否则常超预算）与费用。其它服务商返回 null（不下发该字段）。
     */
    fun thinkingModeFor(apiBaseUrl: String): String? = if (isDeepSeekHost(apiBaseUrl)) "disabled" else null

    /** 实际请求地址（抽出来便于自检页面显示与单测）。 */
    fun endpointOf(apiBaseUrl: String): String =
        "${java.net.URI.create(apiBaseUrl.trim()).toString().trimEnd('/')}/chat/completions"

    /** 纯文本请求体（抽成纯函数便于单测）。 */
    internal fun buildBody(model: String, systemPrompt: String, userText: String, maxTokens: Int, deepSeek: Boolean): JSONObject =
        JSONObject().apply {
            put("model", model)
            put("temperature", 0.0)
            put("max_tokens", maxTokens)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userText)
                })
            })
            if (deepSeek) {
                // 非思考模式：DeepSeek 的 OpenAI 兼容字段
                put("thinking", JSONObject().put("type", "disabled"))
            }
        }

    /**
     * 图片请求体（OpenAI 兼容多模态）：content 为数组，含 text 块与 image_url 块（data URL 内联 base64）。
     * [detail] 用 "high"：取件码是小字，512×512 的 low 会糊掉。
     */
    internal fun buildImageBody(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imageBase64: String,
        maxTokens: Int,
        deepSeek: Boolean,
        detail: String = "high"
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("temperature", 0.0)
        put("max_tokens", maxTokens)
        put("messages", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", userPrompt)
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$imageBase64")
                            put("detail", detail)
                        })
                    })
                })
            })
        })
        if (deepSeek) put("thinking", JSONObject().put("type", "disabled"))
    }

    /** 从模型返回 content 里取出 JSON 数组文本（容忍 ```json 包裹与前后多余文字）。 */
    internal fun extractJsonArray(content: String): String {
        val cleaned = content.trim()
            .replace(Regex("```[a-zA-Z]*\\s*"), "")
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
    }

    /** 解析模型返回的 JSON 数组为 [AIResult]（含格式白名单/噪声过滤，纯函数便于单测）。 */
    internal fun parseResults(content: String): List<AIResult> {
        val arr = JSONArray(extractJsonArray(content))
        val results = mutableListOf<AIResult>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val code = r.optString("code", "").trim()
            if (code.isBlank()) continue
            val typeStr = r.optString("type", "pickup_parcel")
            // type 解析加固：容忍模型返回 food/parcel/取餐 等变体，只有明确 food 才算取餐
            val type = when (typeStr.trim().lowercase()) {
                "pickup_food", "food", "取餐" -> CodeExtractor.CodeType.pickup_food
                else -> CodeExtractor.CodeType.pickup_parcel
            }
            // 与正则"强前缀"路径对齐：AI 有完整上下文（模型看到"取餐码123"），
            // 放行 2-3 位纯数字取餐码（蜜雪/瑞幸常见）；取件码短码与裸数字噪声仍拒绝。
            val shortFood = type == CodeExtractor.CodeType.pickup_food &&
                code.all { it.isDigit() } && code.length in 2..3
            // 内容噪声（全0全1/递增/连号/手机号子串等）一律拦截
            if (CodeValidator.isContentNoise(code)) continue
            // 格式白名单（复用 CodeExtractor 规则单一来源）；短取餐码跳过格式白名单但已过内容检查
            if (!shortFood && !CodeValidator.isValidPickupCode(code)) continue
            // isExcluded：排除模式（手机号/金额/运单号等）+ 自学习排除词
            if (CodeValidator.isExcluded(code)) continue
            results.add(
                AIResult(
                    code = code,
                    type = type,
                    source = r.optString("source", "unknown").ifBlank { "unknown" }.take(20),
                    address = r.optString("address", "").replace(Regex("\\s+"), " ").trim().take(80),
                    station = r.optString("station", "").replace(Regex("\\s+"), " ").trim().take(30),
                    cabinet = r.optString("cabinet", "").replace(Regex("\\s+"), " ").trim().take(20)
                )
            )
        }
        return results
    }

    /** 发一次 POST；返回 (HTTP 码, 正文)。异常由调用方处理。 */
    private fun postJson(json: String, apiKey: String, apiBaseUrl: String, readTimeoutMs: Int): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(endpointOf(apiBaseUrl)).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.outputStream.use { it.write(json.toByteArray()) }
            val code = conn.responseCode
            val text = try {
                (if (code == 200) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "读取响应体失败", e)
                ""
            }
            return code to text
        } finally {
            conn?.disconnect()
        }
    }

    /** 从 200 响应体里取出 message.content。 */
    private fun contentOf(responseBody: String): String =
        JSONObject(responseBody).getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

    /** 纯文本通道：从文字（短信/分享文本/本地 OCR 结果）里提取码值。 */
    suspend fun extract(
        text: String,
        apiKey: String,
        apiBaseUrl: String = "https://api.openai.com/v1",
        model: String = "gpt-4o-mini"
    ): AIExtractResult = withContext(Dispatchers.IO) {
        validateBaseUrl(apiBaseUrl)?.let { return@withContext AIExtractResult(error = it) }
        try {
            val body = buildBody(model, SYSTEM_PROMPT, text, 500, isDeepSeekHost(apiBaseUrl))
            val (code, resp) = postJson(body.toString(), apiKey, apiBaseUrl, TEXT_READ_TIMEOUT_MS)
            if (code != 200) {
                return@withContext AIExtractResult(error = "HTTP $code：${resp.take(240)}")
            }
            val content = contentOf(resp)
            if (BuildConfig.DEBUG) Log.d(TAG, "AI(text) raw: ${content.take(500)}")
            AIExtractResult(results = parseResults(content), usedImage = false)
        } catch (e: CancellationException) {
            throw e   // H2: 协程取消必须重抛，不能吞
        } catch (e: Exception) {
            Log.e(TAG, "AI识别异常", e)
            AIExtractResult(error = e.message ?: "AI调用失败")
        }
    }

    /**
     * 图片通道：把**整张图**（base64 JPEG）交给视觉模型，让它直接读图上的文字并给出码值/品牌/地址。
     *
     * [fallbackText] 非空时，若模型/端点不支持图片输入（判定见 [isVisionUnsupportedError]），
     * 自动改用纯文本通道重试一次 —— 用户换了 `deepseek-v4-pro` 这类无视觉模型时不会整个失效。
     */
    suspend fun extractFromImage(
        imageBase64: String,
        apiKey: String,
        apiBaseUrl: String,
        model: String,
        fallbackText: String? = null
    ): AIExtractResult = withContext(Dispatchers.IO) {
        validateBaseUrl(apiBaseUrl)?.let { return@withContext AIExtractResult(error = it) }
        try {
            val body = buildImageBody(
                model = model,
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = IMAGE_USER_PROMPT,
                imageBase64 = imageBase64,
                maxTokens = 800,
                deepSeek = isDeepSeekHost(apiBaseUrl)
            )
            val (code, resp) = postJson(body.toString(), apiKey, apiBaseUrl, VISION_READ_TIMEOUT_MS)
            if (code == 200) {
                val content = contentOf(resp)
                if (BuildConfig.DEBUG) Log.d(TAG, "AI(image) raw: ${content.take(600)}")
                return@withContext AIExtractResult(results = parseResults(content), usedImage = true)
            }
            val err = "HTTP $code：${resp.take(240)}"
            if (fallbackText != null && isVisionUnsupportedError(err)) {
                Log.w(TAG, "模型不支持图片输入，回退纯文本通道：$err")
                val textRes = extract(fallbackText, apiKey, apiBaseUrl, model)
                return@withContext textRes.copy(error = textRes.error?.let { "$it（图片通道回退文本）" })
            }
            AIExtractResult(error = err, usedImage = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI图片识别异常", e)
            AIExtractResult(error = e.message ?: "AI图片识别失败", usedImage = true)
        }
    }

    /**
     * 连接自检：用**当前配置**发一次最小请求，把 HTTP 状态码/错误正文/耗时/实际地址全带回来。
     * [imageBase64] 非空时改发一张小图，用来验证"视觉通道"是否可用。
     */
    suspend fun probe(
        apiKey: String,
        apiBaseUrl: String,
        model: String,
        imageBase64: String? = null
    ): AIProbe = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val endpoint = runCatching { endpointOf(apiBaseUrl) }.getOrDefault(apiBaseUrl.trim())
        if (apiKey.isBlank()) return@withContext AIProbe(false, 0, endpoint, error = "未填写 API Key")
        if (model.isBlank()) return@withContext AIProbe(false, 0, endpoint, error = "未填写模型名称")
        validateBaseUrl(apiBaseUrl)?.let { return@withContext AIProbe(false, 0, endpoint, error = it) }
        try {
            val body = if (imageBase64.isNullOrBlank()) {
                buildBody(model, "你是连通性自检助手。", "只回复两个字：正常", 16, isDeepSeekHost(apiBaseUrl))
            } else {
                buildImageBody(model, "你是连通性自检助手。", "这张图里有什么字？只回复你看到的字。", imageBase64, 32, isDeepSeekHost(apiBaseUrl))
            }
            val (code, text) = postJson(
                body.toString(), apiKey, apiBaseUrl,
                if (imageBase64.isNullOrBlank()) 20_000 else VISION_READ_TIMEOUT_MS
            )
            val latency = System.currentTimeMillis() - started
            if (code == 200) {
                val reply = runCatching { contentOf(text) }.getOrDefault("")
                AIProbe(true, latency, endpoint, code, reply = reply.trim().take(120))
            } else {
                AIProbe(false, latency, endpoint, code, error = "HTTP $code：${text.take(240)}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AIProbe(false, System.currentTimeMillis() - started, endpoint, error = e.message ?: e.javaClass.simpleName)
        }
    }
}
