package com.pickupcode.app.extractor

import com.pickupcode.app.ocr.OCREngine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CodeExtractorTest {

    private fun line(text: String) = OCREngine.TextLine(text = text, boundingBox = null, confidence = null)

    // ── normalizeText：全角→半角 + 符号归一 ──
    @Test
    @DisplayName("全角数字/减号转半角")
    fun normalize_fullwidth() {
        assertEquals("123-1", CodeExtractor.normalizeText("１２３－１"))
        assertEquals("3-7-4162", CodeExtractor.normalizeText("３－７－４１６２"))
    }

    @Test
    @DisplayName("全角括号/逗号归一 + 空白压缩")
    fun normalize_punct() {
        assertEquals("(3-7-4162)", CodeExtractor.normalizeText("（３－７－４１６２）"))
        assertEquals("a b c", CodeExtractor.normalizeText("a\tb\nc"))
    }

    @Test
    @DisplayName("波浪号/长破折号归一为连字符")
    fun normalize_dash() {
        assertEquals("1-2", CodeExtractor.normalizeText("1～2"))
        assertEquals("1-2", CodeExtractor.normalizeText("1—2"))
    }

    // ── isFinancialNoise：金融词拦截 ──
    @Test
    @DisplayName("金融词且无快递词 → 判定噪音")
    fun finance_noise() {
        assertTrue(CodeExtractor.isFinancialNoise("您的余额为 100 元"))
        assertTrue(CodeExtractor.isFinancialNoise("支付宝到账 50 元"))
        assertTrue(CodeExtractor.isFinancialNoise("信用卡还款提醒"))
    }

    @Test
    @DisplayName("金融词 + 快递词 → 放行")
    fun finance_with_express() {
        assertFalse(CodeExtractor.isFinancialNoise("您的快递已到，取件码 3-7-4162"))
        assertFalse(CodeExtractor.isFinancialNoise("包裹驿站取件通知，微信支付已扣"))
    }

    @Test
    @DisplayName("空文本 → 非噪音")
    fun finance_blank() {
        assertFalse(CodeExtractor.isFinancialNoise(""))
    }

    // ── extract：核心提取 ──
    @Test
    @DisplayName("提取前缀取件码")
    fun extract_parcel() {
        val r = CodeExtractor.extract(listOf(line("【菜鸟驿站】您的取件码 3-7-4162 已到")))
        assertTrue(r.isNotEmpty(), "应提取出取件码")
        assertEquals("3-7-4162", r.first().code)
        assertEquals(CodeExtractor.CodeType.pickup_parcel, r.first().type)
    }

    @Test
    @DisplayName("提取取餐码")
    fun extract_food() {
        val r = CodeExtractor.extract(listOf(line("您的取餐码 A-3-315 请取餐")))
        assertTrue(r.isNotEmpty(), "应提取出取餐码")
        assertEquals("A-3-315", r.first().code)
        assertEquals(CodeExtractor.CodeType.pickup_food, r.first().type)
    }

    @Test
    @DisplayName("强前缀放行 3 位纯数字取餐码（取餐码123）")
    fun extract_prefixedShortPure() {
        val r = CodeExtractor.extract(listOf(line("【蜜雪冰城】取餐码123，请到柜台取餐")))
        assertTrue(r.isNotEmpty(), "应提取出取餐码")
        assertEquals("123", r.first().code)
        assertEquals(CodeExtractor.CodeType.pickup_food, r.first().type)
    }

    @Test
    @DisplayName("无前缀裸 3 位数字仍拒绝（123 不当作码）")
    fun extract_bareShortPureRejected() {
        val r = CodeExtractor.extract(listOf(line("订单金额 123 元")))
        assertTrue(r.isEmpty(), "裸数字 123 不应被提取")
    }

    @Test
    @DisplayName("提取兔喜式单段码（取件码为7-2914）")
    fun extract_tuxiDigitDash() {
        val r = CodeExtractor.extract(listOf(
            line("【兔喜生活】您有包裹已到达长兴路北段店，取件码为7-2914，地址:长兴路北段老李超市旁边")
        ))
        assertTrue(r.isNotEmpty(), "应提取出取件码")
        assertEquals("7-2914", r.first().code)
        assertEquals(CodeExtractor.CodeType.pickup_parcel, r.first().type)
    }

    @Test
    @DisplayName("三段式码的子串不会被兔喜规则误抓（3-7-4162 不应同时产出 7-4162）")
    fun extract_noSubstringDup() {
        val r = CodeExtractor.extract(listOf(line("【菜鸟驿站】您的取件码 3-7-4162 已到")))
        assertEquals(listOf("3-7-4162"), r.map { it.code })
    }

    @Test
    @DisplayName("无码文本返回空列表")
    fun extract_none() {
        assertTrue(CodeExtractor.extract(emptyList()).isEmpty())
        assertTrue(CodeExtractor.extract(listOf(line("这是一段没有码的文字"))).isEmpty())
    }

    // ── 边界回归：Android(ICU) 的 \b 把中文当词字符，码值紧贴中文时 \b 失效漏抓 ──
    // 桌面 JVM 的 \b 是 ASCII 语义，此 bug 在单测环境复现不出（设备必现），
    // 这些用例锁定「码值紧贴中文仍须提取」的行为要求，防止边界写法被改回 \b。
    @Test
    @DisplayName("6位纯数字码紧贴中文（欢猫智柜 306284复制）应被提取")
    fun extract_six_digit_adjacent_chinese() {
        val r = CodeExtractor.extract(listOf(
            line("韵达快递435316307300011 取件码"),
            line("长兴路与长青街西长青社区卫生所对面3号柜欢猫智柜"),
            line("306284复制 您的快件己暂存至新阳市长兴路3号柜")
        ))
        assertTrue(r.any { it.code == "306284" && it.type == CodeExtractor.CodeType.pickup_parcel },
            "应提取出 306284(pickup_parcel)，实际: ${r.map { "${it.code}(${it.type})" }}")
    }

    @Test
    @DisplayName("字母段式码紧贴中文（H-24137取件）应被提取")
    fun extract_letter_dash_adjacent_chinese() {
        val r = CodeExtractor.extract(listOf(line("【菜鸟驿站】您的快件在快递柜，凭H-24137取件")))
        assertTrue(r.any { it.code == "H-24137" }, "应提取出 H-24137，实际: ${r.map { it.code }}")
    }

    @Test
    @DisplayName("三段式码紧贴中文（3-7-4162到）应被提取")
    fun extract_three_seg_adjacent_chinese() {
        val r = CodeExtractor.extract(listOf(line("凭3-7-4162到1号柜取件")))
        assertTrue(r.any { it.code == "3-7-4162" }, "应提取出 3-7-4162，实际: ${r.map { it.code }}")
    }

    @Test
    @DisplayName("长运单号（15位纯数字）不被当作取件码")
    fun extract_rejects_long_tracking_number() {
        val r = CodeExtractor.extract(listOf(line("韵达快递435316307300011 您的快件已到")))
        assertTrue(r.none { it.code == "435316307300011" }, "运单号不应被识别为取件码")
    }

    // ── 真机日志对照分析修复的回归测试（49 张真实截图发现）──

    @Test
    @DisplayName("电量百分比 529% 不当作取件码，同屏真实码仍提取")
    fun extract_rejects_battery_percent() {
        val r = CodeExtractor.extract(listOf(line("取件码 590297"), line("529%")))
        assertTrue(r.none { it.code == "529" }, "电量 529% 不应被提取，实际: ${r.map { it.code }}")
        assertTrue(r.any { it.code == "590297" }, "真实码 590297 应保留，实际: ${r.map { it.code }}")
    }

    @Test
    @DisplayName("国标号 GB/T19777 不当作取件码（T19777 拒绝）")
    fun extract_rejects_gb_standard_number() {
        val r = CodeExtractor.extract(listOf(line("买醋认准GB/T19777 山西老陈醋")))
        assertTrue(r.none { it.code == "T19777" }, "标准号 T19777 不应被提取，实际: ${r.map { it.code }}")
    }

    @Test
    @DisplayName("座机区号前缀 0394-6728150 不当作取件码")
    fun extract_rejects_area_code_phone() {
        val r = CodeExtractor.extract(listOf(line("揽投部[电话:0394-6728150,投诉电话]")))
        assertTrue(r.none { it.code == "6728150" }, "座机号码 6728150 不应被提取，实际: ${r.map { it.code }}")
    }

    @Test
    @DisplayName("真实中通行（网点电话:0394-6728150）即使同屏有取件码也不混入")
    fun extract_rejects_real_area_code_line() {
        val r = CodeExtractor.extract(listOf(
            line("包裏等间题诗联系快递员.网点电话:0394-6728150,投"),
            line("取件码:3-1-1099 复制")
        ))
        assertTrue(r.none { it.code == "6728150" }, "6728150 不应出现，实际: ${r.map { it.code }}")
        assertTrue(r.any { it.code == "3-1-1099" }, "真实码 3-1-1099 应保留，实际: ${r.map { it.code }}")
    }

    @Test
    @DisplayName("掩码手机号 86-135****2468 不当作取件码")
    fun extract_rejects_masked_phone() {
        val r = CodeExtractor.extract(listOf(line("李明 86-135****2468 号码保护中")))
        assertTrue(r.none { it.code == "86-135" }, "掩码手机号片段 86-135 不应被提取，实际: ${r.map { it.code }}")
    }

    @Test
    @DisplayName("时间 20:15 不当作取件码（跨行前缀路径也不抓）")
    fun extract_rejects_clock_time() {
        val r = CodeExtractor.extract(listOf(line("取件码"), line("20:15")))
        assertTrue(r.none { it.code == "20" }, "时间 20 不应被提取，实际: ${r.map { it.code }}")
    }

    @Test
    @DisplayName("跨行前缀路径不抓订单页 UI 文案（202 查看订单详情）")
    fun extract_nextLine_ui_noise() {
        val r = CodeExtractor.extract(listOf(
            line("取件码3-6-4035"),
            line("202 查看订单详情>")
        ))
        assertTrue(r.none { it.code == "202" }, "UI 文案 202 不应被提取，实际: ${r.map { it.code }}")
        assertTrue(r.any { it.code == "3-6-4035" }, "真实码 3-6-4035 应保留，实际: ${r.map { it.code }}")
    }

    @Test
    @DisplayName("OCR 中文一误读段分隔符：3一1-1099 归一化后提取 3-1-1099")
    fun extract_normalize_chinese_dash() {
        assertEquals("取件码:3-1-1099", CodeExtractor.normalizeText("取件码:3一1-1099"))
        val r = CodeExtractor.extract(listOf(line("取件码:3一1-1099 复制")))
        assertTrue(r.any { it.code == "3-1-1099" }, "应提取 3-1-1099，实际: ${r.map { it.code }}")
        assertTrue(r.none { it.code == "1-1099" }, "残码 1-1099 不应出现，实际: ${r.map { it.code }}")
    }

    @Test
    @DisplayName("OCR 码尾粘字母（3-1-403x）拒绝")
    fun extract_rejects_trailing_letter() {
        val r = CodeExtractor.extract(listOf(line("取件码3-1-403x")))
        assertTrue(r.none { it.code == "3-1-403x" }, "残码 3-1-403x 不应被提取，实际: ${r.map { it.code }}")
    }

    @Test
    @DisplayName("OCR 截断残码是长码子串时只保留长码（3-6-403 与 3-6-4035 同屏）")
    fun extract_substring_dedup() {
        val r = CodeExtractor.extract(listOf(line("取件码3-6-4035"), line("取件码3-6-403")))
        assertEquals(listOf("3-6-4035"), r.map { it.code }, "应只保留完整码 3-6-4035")
    }

    @Test
    @DisplayName("餐饮取餐截图带支付信息不被金融闸门误杀（取单码+微信支付）")
    fun financial_gate_keeps_dining_order() {
        assertFalse(
            CodeExtractor.isFinancialNoise("取单码 商品明细 LINLEE林里 微信支付 付款方式 实付14.8"),
            "带取单码的餐饮截图不应判为金融噪音"
        )
        assertFalse(
            CodeExtractor.isFinancialNoise("茶百道 排队提醒 取餐号 0147 微信支付已扣款"),
            "带取餐号/排队的餐饮截图不应判为金融噪音"
        )
    }

    @Test
    @DisplayName("纯金融通知仍被金融闸门拦截（无取餐信号）")
    fun financial_gate_blocks_pure_finance() {
        assertTrue(
            CodeExtractor.isFinancialNoise("您的信用卡还款 5000 元已到账"),
            "无取餐信号的金融通知仍应拦截"
        )
    }

    @Test
    @DisplayName("团购券截图带『到店消费』不被金融闸门误杀（券号信号放行）")
    fun financial_gate_keeps_coupon_context() {
        assertFalse(
            CodeExtractor.isFinancialNoise("请在2026.10.04前到店消费 本单有惊喜 还可获得5元无门槛券 券号1246 1046 4170 008 复制"),
            "带券号的团购券截图不应判为金融噪音"
        )
        assertTrue(
            CodeExtractor.isFinancialNoise("周三到店消费享5折，信用卡支付满减"),
            "无券码信号的到店消费营销文本仍应拦截"
        )
    }

    @Test
    @DisplayName("券号长数字（OCR 空格分隔）提取为券码，且不残留 parcel 部分码")
    fun extract_coupon_number_spaced() {
        val r = CodeExtractor.extract(listOf(
            line("请在2026.10.04前到店消费 券号1246 1046 4170 008·复制"),
            line("查看订单 购买成功")
        ))
        val coupon = r.filter { it.type == CodeExtractor.CodeType.coupon }
        assertEquals(listOf("124610464170008"), coupon.map { it.code }, "应还原完整券号")
        assertTrue(
            r.none { it.type != CodeExtractor.CodeType.coupon },
            "不应残留其他类型的部分码（如 parcel 1242）: ${r.map { it.code }}"
        )
    }

    @Test
    @DisplayName("短券号（券号 123456 到店使用）提取为券码")
    fun extract_coupon_number_short() {
        val r = CodeExtractor.extract(listOf(line("券号 123456 到店使用")))
        assertEquals(listOf("123456"), r.filter { it.type == CodeExtractor.CodeType.coupon }.map { it.code })
    }

    @Test
    @DisplayName("券号噪声（全0/4连重复）不提取")
    fun extract_coupon_number_noise() {
        val r = CodeExtractor.extract(listOf(line("券号 000000 到店使用")))
        assertTrue(r.isEmpty(), "全0券号应被内容噪声检查拒绝: ${r.map { it.code }}")
    }

    // ── 金融闸门：单字「柜」曾把银行验证码放行成取件码 ──

    @Test
    @DisplayName("银行验证码短信里的「柜台」不得被当成快递信号")
    fun finance_bankCounterNotExpressSignal() {
        val sms = "【某银行】储蓄卡消费验证码 618008，请勿告知他人，柜台业务请咨询"
        // 闸门本体：命中金融词 + 无有效快递信号 → 判为噪音。
        // 三个识别入口都靠它拦截（PickupCodeAccessibilityService:500 / ShareReceiver:343 / SmsReceiver:77），
        // 所以这里断言 isFinancialNoise 就是断言"识别不会发生"。
        assertTrue(CodeExtractor.isFinancialNoise(sms), "含金融词且只有「柜台」→ 应判为金融噪音")
        // 修复前：EXPRESS_SIGNAL_KEYWORDS 含裸字「柜」，这句会被放行，长数字 618008 以取件码入库
    }

    @Test
    @DisplayName("带快递信号的银行/支付混合文案仍放行（闸门不能修成误杀）")
    fun finance_withRealExpressSignalPasses() {
        assertFalse(CodeExtractor.isFinancialNoise("【菜鸟驿站】您的取件码 3-7-4162，微信支付已扣款"))
        assertFalse(CodeExtractor.isFinancialNoise("包裹已到快递柜，支付宝到账提醒已关闭"))
    }

    @Test
    @DisplayName("「快递柜/智能柜」等复合词仍是有效快递信号")
    fun finance_compoundCabinetStillExpress() {
        assertFalse(CodeExtractor.isFinancialNoise("您的包裹已放入快递柜，取件码 1-2-3456"))
        assertFalse(CodeExtractor.isFinancialNoise("包裹已存入丰巢智能柜，微信支付已完成"))
        assertFalse(CodeExtractor.isFinancialNoise("取件柜 3 号，凭取件码 8-1-2233"))
    }

    // ── 阈值基准：不能拿"最长候选"的分数当 top ──

    @Test
    @DisplayName("同屏有低分长数字时，不得因此放行更弱的噪声（top 必须取最高分）")
    fun threshold_usesMaxScoreNotLongest() {
        // 场景：一张取餐码截图里混进两个无关数字。
        // 修复前 keptCands 按码长排序，top 取到"最长候选(87654321)"的分数 → 阈值被拉低 → 三个全放行；
        // 修复后 top 取最高分 → 最弱的 8 位长数字被 top×0.75 淘汰。
        val r = CodeExtractor.extract(listOf(
            line("取餐码 12345"),
            line("618008"),
            line("87654321")
        ))
        val codes = r.map { it.code }
        assertTrue(codes.contains("12345"), "高分取餐码必须保留: $codes")
        assertTrue(
            codes.none { it == "87654321" },
            "最弱的 8 位长数字应被阈值淘汰，而不是因为'最长'把阈值拉低后放行: $codes"
        )
    }

    // ── 2026-09-16 真实语料回归（58 张真机相册截图）暴露的三个问题 ──

    private fun boxed(text: String, left: Int, top: Int, w: Int, h: Int) =
        OCREngine.TextLine(text, OCREngine.LineBox(left, top, left + w, top + h), 0.7f)

    @Test
    @DisplayName("真实语料·美团外卖地图页：不许把高速编号 S26 当取餐码，要认出标签正下方的 QTP07")
    fun real_meituanMap_codeBelowLabel() {
        // 坐标为真机 OCR 原值（1260×2800）。「取餐号」在 y=803，真实码 QTP07 在 y=843（大字号 44px），
        // 而地图元素 s26（沪常高速）在 y=406 —— 旧实现因"全屏出现取餐"就把 s26 当取餐码。
        val r = CodeExtractor.extract(
            listOf(
                boxed("s26", 37, 406, 39, 22),
                boxed("蟠龙古镇", 100, 602, 88, 22),
                boxed("餐厅己接单", 319, 261, 117, 22),
                boxed("上海美的全", 100, 720, 100, 22),
                boxed("球创新园区」", 100, 740, 112, 26),
                boxed("取餐号", 282, 803, 63, 21),
                boxed("QTP07", 221, 843, 184, 44),
                boxed("餐厅己接单,预计 18:33 送达", 132, 926, 358, 28)
            ),
            screenHeight = 2800
        )
        val codes = r.map { it.code }
        assertTrue(codes.none { it.equals("s26", ignoreCase = true) }, "高速编号 s26 不是取餐码: $codes")
        assertTrue(codes.contains("QTP07"), "标签正下方的真实取餐号必须识别出来: $codes")
        assertEquals(CodeExtractor.CodeType.pickup_food, r.first { it.code == "QTP07" }.type)
    }

    @Test
    @DisplayName("真实语料·美团券页：价格行「H 10.8 *6」不得被当成取餐码")
    fun real_couponPriceLine_notFoodCode() {
        val r = CodeExtractor.extract(listOf(
            line("券号1246 1046 4170 008·复制"),
            line("取餐号"),
            line("H 10.8 *6")
        ))
        val codes = r.map { it.code }
        assertTrue(codes.none { it.contains(" ") }, "码值不得含空格（旧实现捕出过 \"H 10\"）: $codes")
        assertTrue(codes.none { it.equals("H10", ignoreCase = true) }, "价格行不是取餐码: $codes")
        assertTrue(codes.contains("124610464170008"), "同屏的团购券号仍应识别: $codes")
    }

    @Test
    @DisplayName("字母+数字取餐码不允许内部空格（A 12 不再被当成 A 12 入码）")
    fun foodLetterNum_noInnerSpace() {
        val r = CodeExtractor.extract(listOf(line("瑞幸咖啡"), line("取餐码 A 12")))
        assertTrue(r.none { it.code.any { c -> c == ' ' } }, "码值不得含空格: ${r.map { it.code }}")
    }
}
