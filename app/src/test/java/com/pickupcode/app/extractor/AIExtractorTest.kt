package com.pickupcode.app.extractor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * AI 配置自检的纯函数部分（真机 2026-09-16：用户配了 DeepSeek 的 Key 却"没作用"，
 * 排查发现主要坑是"地址带了 /chat/completions"和"模型名没改"）。
 */
class AIExtractorTest {

    @Test
    @DisplayName("Base URL 预检：合法地址通过（DeepSeek 两种写法都行）")
    fun baseUrl_ok() {
        assertNull(AIExtractor.validateBaseUrl("https://api.deepseek.com/v1"))
        assertNull(AIExtractor.validateBaseUrl("https://api.deepseek.com"))
        assertNull(AIExtractor.validateBaseUrl("https://api.openai.com/v1"))
        assertNull(AIExtractor.validateBaseUrl("https://api.deepseek.com/v1/"))  // 末尾斜杠由调用方 trim
    }

    @Test
    @DisplayName("Base URL 预检：带 /chat/completions 被拦（会拼成两遍 → 404）")
    fun baseUrl_rejectsFullEndpoint() {
        val err = AIExtractor.validateBaseUrl("https://api.deepseek.com/v1/chat/completions")
        assertNotNull(err)
        assertTrue(err!!.contains("/chat/completions"), "错误信息要说清是哪个问题: $err")
    }

    @Test
    @DisplayName("Base URL 预检：http / IP / localhost / 空值 全部拦下")
    fun baseUrl_rejectsUnsafe() {
        assertNotNull(AIExtractor.validateBaseUrl("http://api.deepseek.com/v1"))
        assertNotNull(AIExtractor.validateBaseUrl("https://192.168.1.5:11434/v1"))
        assertNotNull(AIExtractor.validateBaseUrl("https://localhost:8080/v1"))
        assertNotNull(AIExtractor.validateBaseUrl("https://127.0.0.1/v1"))
        assertNotNull(AIExtractor.validateBaseUrl(""))
        assertNotNull(AIExtractor.validateBaseUrl("api.deepseek.com"))  // 缺协议
    }

    @Test
    @DisplayName("DeepSeek 域名识别：只对官方域名关思考模式，其它服务商不受影响")
    fun deepSeekHost() {
        assertTrue(AIExtractor.isDeepSeekHost("https://api.deepseek.com/v1"))
        assertTrue(AIExtractor.isDeepSeekHost("https://api.deepseek.com"))
        assertTrue(!AIExtractor.isDeepSeekHost("https://api.openai.com/v1"))
        assertTrue(!AIExtractor.isDeepSeekHost("https://api.moonshot.cn/v1"))
    }

    @Test
    @DisplayName("thinking 模式：只有 DeepSeek 关思考（其它服务商不下发该字段）")
    fun thinkingMode() {
        assertEquals("disabled", AIExtractor.thinkingModeFor("https://api.deepseek.com/v1"))
        assertEquals("disabled", AIExtractor.thinkingModeFor("https://api.deepseek.com"))
        assertNull(AIExtractor.thinkingModeFor("https://api.openai.com/v1"))
        assertNull(AIExtractor.thinkingModeFor("https://api.moonshot.cn/v1"))
    }

    // ── 图片通道（2026-09-17：用户要求"发给 AI 的是照片"）──

    @Test
    @DisplayName("图片请求体：content 为数组，含 text + image_url(data URL)，且图片只放在 user 消息里")
    fun imageBody_structure() {
        val body = AIExtractor.buildImageBody(
            model = "deepseek-flash",
            systemPrompt = "SYS",
            userPrompt = "读图",
            imageBase64 = "AAAA",
            maxTokens = 800,
            deepSeek = true
        )
        val messages = body.getJSONArray("messages")
        // system 消息必须是纯文本（DeepSeek 对 system 带图直接 400）
        assertEquals("SYS", messages.getJSONObject(0).getString("content"))
        val parts = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("读图", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/jpeg;base64,AAAA",
            parts.getJSONObject(1).getJSONObject("image_url").getString("url")
        )
        assertEquals("high", parts.getJSONObject(1).getJSONObject("image_url").getString("detail"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
    }

    @Test
    @DisplayName("图片请求体：非 DeepSeek 服务商不带 thinking 字段")
    fun imageBody_noThinkingForOthers() {
        val body = AIExtractor.buildImageBody("gpt-4o-mini", "S", "U", "AAAA", 800, deepSeek = false)
        assertTrue(!body.has("thinking"))
    }

    @Test
    @DisplayName("解析 AI 返回：码值/类型/品牌/地址/站点/柜号，且容忍 ```json 包裹与前后废话")
    fun parse_fullFields() {
        val content = """
            好的，结果如下：
            ```json
            [{"code":"3-7-4162","type":"pickup_parcel","source":"圆通","address":"长兴路北段老李超市旁边","station":"菜鸟驿站","cabinet":"2号柜"},
             {"code":"A12","type":"pickup_food","source":"瑞幸","address":"","station":"","cabinet":""}]
            ```
        """.trimIndent()
        val r = AIExtractor.parseResults(content)
        assertEquals(2, r.size)
        assertEquals("3-7-4162", r[0].code)
        assertEquals("圆通", r[0].source)
        assertEquals("长兴路北段老李超市旁边", r[0].address)
        assertEquals("菜鸟驿站", r[0].station)
        assertEquals("2号柜", r[0].cabinet)
        assertEquals(CodeExtractor.CodeType.pickup_food, r[1].type)
    }

    @Test
    @DisplayName("解析 AI 返回：噪声码（全0/运单号/超长）被过滤")
    fun parse_filters() {
        val content = """[{"code":"0000","type":"pickup_parcel","source":"x"},
            {"code":"435316307300011","type":"pickup_parcel","source":"x"},
            {"code":"7-1234","type":"pickup_parcel","source":"兔喜","address":"长兴路店"}]"""
        val r = AIExtractor.parseResults(content)
        assertEquals(listOf("7-1234"), r.map { it.code })
    }

    @Test
    @DisplayName("视觉不支持判定：用于自动回退文本通道")
    fun visionUnsupported() {
        assertTrue(AIExtractor.isVisionUnsupportedError("HTTP 400: This model does not support image input"))
        assertTrue(AIExtractor.isVisionUnsupportedError("invalid content type: image_url"))
        assertTrue(!AIExtractor.isVisionUnsupportedError("HTTP 401: Authentication Fails"))
        assertTrue(!AIExtractor.isVisionUnsupportedError("HTTP 429 rate limit"))
    }

    @Test
    @DisplayName("错误归类：模型不支持图片时给出可操作提示")
    fun categorize_visionUnsupported() {
        assertEquals(
            "该模型不支持图片输入（改用 deepseek-flash 或关闭图片通道）",
            AIExtractor.categorizeError("HTTP 400: model does not support image input")
        )
    }

    @Test
    @DisplayName("endpoint 拼接：base URL 末尾斜杠不会拼出双斜杠")
    fun endpoint_join() {
        assertEquals("https://api.deepseek.com/chat/completions", AIExtractor.endpointOf("https://api.deepseek.com"))
        assertEquals("https://api.deepseek.com/v1/chat/completions", AIExtractor.endpointOf("https://api.deepseek.com/v1/"))
    }

    @Test
    @DisplayName("错误归类：DeepSeek 常见失败都能给中文原因")
    fun categorize_common() {
        assertEquals("AI密钥无效", AIExtractor.categorizeError("HTTP 401: Authentication Fails"))
        assertEquals("AI账户余额不足", AIExtractor.categorizeError("HTTP 402: Insufficient Balance"))
        // DeepSeek 用错模型名时返回的就是 400 Model Not Exist —— 归类要说"模型名不可用"而不是笼统的 400
        assertEquals("AI模型名不可用", AIExtractor.categorizeError("HTTP 400: Model Not Exist"))
        assertEquals("AI模型名不可用", AIExtractor.categorizeError("model not found"))
        assertEquals("AI请求被拒（检查模型名/参数）", AIExtractor.categorizeError("HTTP 400: invalid request"))
        assertEquals("AI请求过于频繁", AIExtractor.categorizeError("HTTP 429 rate limit"))
        assertEquals("AI服务超时", AIExtractor.categorizeError("timeout"))
        assertEquals("网络连接失败", AIExtractor.categorizeError("Failed to connect to api.deepseek.com"))
        assertEquals("AI识别失败", AIExtractor.categorizeError("莫名其妙"))
    }
}
