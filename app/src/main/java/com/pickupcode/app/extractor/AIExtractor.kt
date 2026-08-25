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
 * AI 提取器：通过 OpenAI 兼容 API 从屏幕文字中提取取餐码/取件码
 */
object AIExtractor {

    /** IPv4 点分；IPv6 由下方 contains(':') / 括号字面量另行拦截。 */
    private val IP_HOST_REGEX = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    data class AIResult(
        val code: String,
        val type: CodeExtractor.CodeType,
        val source: String
    )

    /** 提取结果：results 为识别到的码；error 非空表示本次调用失败（网络/Key/解析），用于上层反馈 */
    data class AIExtractResult(
        val results: List<AIResult> = emptyList(),
        val error: String? = null
    )

    private val SYSTEM_PROMPT = """
你是一个取件码/取餐码识别助手。用户会发来一段取件/取餐通知文字——可能是短信、分享文本或手机屏幕 OCR 识别结果（OCR 可能带错别字、漏字或乱码，请按语义理解）。从中提取所有取餐码和取件码。

请只回复一个纯 JSON 数组（不要 markdown、不要解释、不要任何多余文字）。数组每个元素：
{"code":"码值","type":"pickup_food或pickup_parcel","source":"品牌/驿站名"}

识别规则：
- 取餐码(pickup_food)：外卖/奶茶/咖啡等餐饮的取餐号，如 "229"、"A-356"、"123"
- 取件码(pickup_parcel)：快递/驿站/快递柜的取件码，常见形状：纯数字4-8位("067865")、数字-数字("5-3858"、"1-6-5020")、字母+数字("A8-3-3315"、"D-12345")
- 码值只提取码本身，不要拼接、补零或修改；同一条短信有多个码全部列出；没有则回复 []
- source：品牌或驿站名（如"瑞幸""蜜雪冰城""菜鸟驿站""丰巢""兔喜生活"），找不到写 "unknown"

不要提取（这些不是取件码/取餐码）：
- 订单号、运单号、快递单号（通常很长或紧跟在"单号""运单"后）
- 手机号、客服电话、金额、价格、时间、日期
- 银行/支付类验证码、"尾号"后的数字
- 优惠券/券码（本任务只识别取餐码和取件码）
""".trimIndent()

    suspend fun extract(
        text: String,
        apiKey: String,
        apiBaseUrl: String = "https://api.openai.com/v1",
        model: String = "gpt-4o-mini"
    ): AIExtractResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val parsed = java.net.URI.create(apiBaseUrl).toURL()
            require(parsed.protocol == "https") { "API Base URL must use HTTPS" }
            // H-C: 拒绝 IP/localhost，避免 Bearer key 发往任意端点（用户仍可配信任的域名服务）
            val host = parsed.host?.lowercase()
            // 拒绝 IPv4 / IPv6（含 [//::1] 字面量 host）/ localhost / *.local，避免 Bearer key 发往任意端点
            val isIpOrLocal = host == null
                || host == "localhost"
                || host.endsWith(".local")
                || host == "0.0.0.0"
                || IP_HOST_REGEX.matches(host)
                || host.contains(':') // IPv6 raw or zone-id forms
            require(!isIpOrLocal) {
                "API 地址请使用域名（拒绝 IP / localhost / IPv6）"
            }
            val url = URL("${parsed.toString().trimEnd('/')}/chat/completions")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true

            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 0.0)
                put("max_tokens", 500)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", text)
                    })
                })
            }

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode != 200) {
                val errBody = try {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
                } catch (e: Exception) {
                    Log.w("AIExtractor", "Failed to read error response body", e)
                    null
                }
                return@withContext AIExtractResult(error = "HTTP ${conn.responseCode}: ${errBody?.take(120) ?: ""}".trim())
            }

            val response = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()

            val json = JSONObject(response)
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .replace(Regex("```[a-zA-Z]*\\s*"), "")
                .replace("```", "")
                .trim()

            if (BuildConfig.DEBUG) {
                Log.d("AIExtractor", "AI raw content: ${content.take(500)}")
            }
            val arr = JSONArray(content)
            val results = mutableListOf<AIResult>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
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
                results.add(AIResult(code = code, type = type,
                    source = r.optString("source", "unknown").ifBlank { "unknown" }))
            }
            AIExtractResult(results = results)
        } catch (e: CancellationException) {
            throw e   // H2: 协程取消必须重抛，不能吞
        } catch (e: Exception) {
            Log.e("AIExtractor", "AI识别异常", e)
            AIExtractResult(error = e.message ?: "AI调用失败")
        } finally {
            conn?.disconnect()
        }
    }
}
