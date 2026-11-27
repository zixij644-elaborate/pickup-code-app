package com.pickupcode.app.extractor

import com.pickupcode.app.learner.SavedAddressStore
import com.pickupcode.app.ocr.OCREngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 多码同框卡片边界切分与地址/柜号/预存地址逐卡归属回归测试。
 * 覆盖真实取件码样本库中的 4 种多码版式（A 驿站分组、B 每单独立头、C 通知栏多卡、D 订单列表）。
 */
class MultiCodeAddressTest {

    private fun box(x: Int, y: Int, w: Int = 360, h: Int = 24) =
        OCREngine.LineBox(x, y, x + w, y + h)

    private fun line(text: String, y: Int, x: Int = 40, w: Int = 360, h: Int = 24) =
        OCREngine.TextLine(text = text, boundingBox = box(x, y, w, h), confidence = 0.9f)

    @Test
    @DisplayName("版式B：每单独立带头（4卡同屏含跨行尾号凭证），邻卡更长地址不抢占本卡")
    fun patternB_fourCardsWithSplitTrackingCredential() {
        val lines = listOf(
            // 卡1
            line("极兔速递", 200, 40, 120),
            line("电话", 200, 520, 50),
            line("科技学院洗浴中心楼后背靠背", 232),
            line("取件码 86-4-2041", 310),
            line("极兔速递 JT5550520520975", 345),
            // 卡2（地址很长）
            line("三餐申通多多官方服务点", 420, 40, 260),
            line("电话", 420, 520, 50),
            line("河南科技学院三餐厅后，8A宿舍楼门口，老杜修车旁", 452, 40, 480),
            line("取件码 4-4-2013", 530),
            line("申通快递 777465822288831", 565),
            // 卡3（跨行快递单号后五位）
            line("菜鸟驿站", 640, 40, 120),
            line("电话", 640, 520, 50),
            line("科院悠学里三楼取一楼寄中通快递", 672),
            line("取件出示快递单号后五位", 745),
            line("53762", 785, 160, 120, 32),
            line("中通快递 79162247253762", 825),
            // 卡4
            line("科院原大壮后快递圆通韵达快递店", 900, 40, 340),
            line("电话", 900, 520, 50),
            line("河科院7号宿舍楼东圆通韵达快递店", 932),
            line("取件码 80-5-2036", 1010),
            line("圆通快递 YT7658204063020", 1045)
        )

        val extracted = CodeExtractor.extract(lines, screenHeight = 1400)
        val codes = extracted.map { it.code }
        assertTrue("86-4-2041" in codes)
        assertTrue("4-4-2013" in codes)
        assertTrue("53762" in codes, "跨行快递单号后五位 53762 应被识别，实际=$codes")
        assertTrue("80-5-2036" in codes)

        assertEquals("科技学院洗浴中心楼后背靠背", AddressExtractor.extractAddressForCode(lines, "86-4-2041", codes))
        assertEquals("河南科技学院三餐厅后，8A宿舍楼门口，老杜修车旁", AddressExtractor.extractAddressForCode(lines, "4-4-2013", codes))
        assertEquals("科院悠学里三楼取一楼寄中通快递", AddressExtractor.extractAddressForCode(lines, "53762", codes))
        assertEquals("河科院7号宿舍楼东圆通韵达快递店", AddressExtractor.extractAddressForCode(lines, "80-5-2036", codes))
    }

    @Test
    @DisplayName("版式A：驿站分组一头挂多卡（前组多卡不向下偷下一组地址）")
    fun patternA_groupedHeadersInheritCorrectly() {
        val lines = listOf(
            // 分组1头
            line("韩梅诊所对面菜鸟", 180, 40, 220),
            line("导航", 180, 500, 50),
            line("韩梅诊所对面菜鸟", 210, 40, 220),
            // 子卡1..3
            line("取件码 3-6-4035", 260),
            line("申通快递: 777404245710736", 292),
            line("查看订单详情>", 325),
            line("取件码 3-1-4033", 380),
            line("圆通快递: YT8863561651556", 412),
            line("查看订单详情>", 445),
            line("取件码 1-3-4004", 500),
            line("申通快递: 777404279027161", 532),
            line("查看订单详情>", 565),
            // 分组2头
            line("兔喜快递超市代收点", 620, 40, 240),
            line("导航", 620, 500, 50),
            line("育新路育新路 育新路北段爱玛电动车旁边", 650, 40, 420),
            // 子卡4..5
            line("取件码 3-4-4352", 700),
            line("中通快递: 79100326535714", 732),
            line("查看订单详情>", 765),
            line("取件码 3-2-8519", 820),
            line("中通快递: 79100137525564", 852),
            line("查看订单详情>", 885)
        )
        val allCodes = listOf("3-6-4035", "3-1-4033", "1-3-4004", "3-4-4352", "3-2-8519")

        assertEquals("韩梅诊所对面菜鸟", AddressExtractor.extractAddressForCode(lines, "3-6-4035", allCodes))
        assertEquals("韩梅诊所对面菜鸟", AddressExtractor.extractAddressForCode(lines, "3-1-4033", allCodes))
        assertEquals("韩梅诊所对面菜鸟", AddressExtractor.extractAddressForCode(lines, "1-3-4004", allCodes))
        assertTrue(AddressExtractor.extractAddressForCode(lines, "3-4-4352", allCodes).contains("育新路北段爱玛电动车旁边"))
        assertTrue(AddressExtractor.extractAddressForCode(lines, "3-2-8519", allCodes).contains("育新路北段爱玛电动车旁边"))

        // 逐卡预存地址匹配：组1命中韩梅预存，组2命中兔喜预存，互不串台
        val saved = listOf(
            SavedAddressStore.MatcherView("菜鸟(韩梅诊所店)", listOf("韩梅诊所")),
            SavedAddressStore.MatcherView("兔喜(育新路店)", listOf("兔喜"))
        )
        val card1004 = AddressExtractor.cardLinesForCode(lines, "1-3-4004", allCodes)
        val card8519 = AddressExtractor.cardLinesForCode(lines, "3-2-8519", allCodes)
        assertEquals("菜鸟(韩梅诊所店)", SavedAddressMatcher.match(card1004, saved)?.fullName)
        assertEquals("兔喜(育新路店)", SavedAddressMatcher.match(card8519, saved)?.fullName)
    }

    @Test
    @DisplayName("版式C：通知栏多条独立气泡（已存放至/已到句式 + 各自独立柜号）")
    fun patternC_notificationCardsAndSeparateCabinets() {
        val lines = listOf(
            line("商品待取件提醒", 120, 120, 200),
            line("昨天17:10", 120, 480, 100),
            line("您的商品已存放至欢猫智柜快递柜，请凭取件", 152, 120, 420),
            line("码(539802)及时取件，今日共3个快…", 184, 120, 420),

            line("商品待取件提醒", 260, 120, 200),
            line("昨天17:01", 260, 480, 100),
            line("您的商品已存放至欢猫智柜1号柜，请凭取件", 292, 120, 420),
            line("码(235674)及时取件，今日共2个快递…", 324, 120, 420),

            line("妈妈驿站", 400, 120, 140),
            line("昨天16:41", 400, 480, 100),
            line("【妈妈驿站】取货码A3-8188，您有圆通快递", 432, 120, 420),
            line("包裹，已到二高北丁字路口美宜佳超市旁2号柜", 464, 120, 420)
        )
        val codes = listOf("539802", "235674", "A3-8188")

        assertEquals("欢猫智柜快递柜", AddressExtractor.extractAddressForCode(lines, "539802", codes))
        assertEquals("欢猫智柜1号柜", AddressExtractor.extractAddressForCode(lines, "235674", codes))
        assertEquals("二高北丁字路口美宜佳超市旁2号柜", AddressExtractor.extractAddressForCode(lines, "A3-8188", codes))

        val cab2 = AddressExtractor.cardLinesForCode(lines, "235674", codes)
        val cab3 = AddressExtractor.cardLinesForCode(lines, "A3-8188", codes)
        assertEquals("1号柜", AddressExtractor.extractCabinetNumber(cab2, cab2.joinToString(" ") { it.text }))
        assertEquals("2号柜", AddressExtractor.extractCabinetNumber(cab3, cab3.joinToString(" ") { it.text }))
    }

    @Test
    @DisplayName("版式D：电商待收货列表底部取件条，卖家网店名不得被误当取件地址")
    fun patternD_orderListBlocksShopNameLeakage() {
        val lines = listOf(
            line("蒋小俊的铺子 >", 180),
            line("笔记本记事本", 220),
            line("实付: ¥7.9(免运费)", 260),
            line("申请退款 查看物流 确认收货", 300),
            line("待取件 取件码2-3-7972 | 申通快递: 【驿站】您…", 345),

            line("我的宝儿文具店 >", 420),
            line("活页笔记本", 460),
            line("实付: ¥12.8(免运费)", 500),
            line("申请退款 查看物流 确认收货", 540),
            line("待取件 取件码C4-148 | 圆通快递: 您的快件已到…", 585)
        )
        val codes = listOf("2-3-7972", "C4-148")
        assertEquals("", AddressExtractor.extractAddressForCode(lines, "2-3-7972", codes))
        assertEquals("", AddressExtractor.extractAddressForCode(lines, "C4-148", codes))
    }

    @Test
    @DisplayName("ReadingOrder：乱序 textBlocks 重排后卡片归属依然准确")
    fun readingOrder_shuffledBlocksStillMatchCorrectCards() {
        val sorted = listOf(
            line("三餐后申通快递", 308, 99, 169, 23),
            line("导航", 310, 519, 42, 20),
            line("河南阳光学院三餐厅后,8A宿舍楼门口", 344, 98, 420, 21),
            line("取件码4-5-6017>", 406, 169, 214, 25),
            line("查看订单详情>", 510, 168, 135, 24),
            line("河南阳光学院7号宿舍楼东圆通快递店", 617, 87, 344, 25),
            line("取件码 66-5-7013>", 681, 159, 246, 27),
            line("查看订单详情>", 786, 168, 135, 24)
        )
        // 故意按 ML Kit 乱序打乱
        val shuffled = listOf(sorted[3], sorted[6], sorted[0], sorted[5], sorted[2], sorted[7], sorted[4], sorted[1])
        val codes = listOf("4-5-6017", "66-5-7013")

        assertEquals("河南阳光学院三餐厅后,8A宿舍楼门口", AddressExtractor.extractAddressForCode(shuffled, "4-5-6017", codes))
        assertEquals("河南阳光学院7号宿舍楼东圆通快递店", AddressExtractor.extractAddressForCode(shuffled, "66-5-7013", codes))
    }

    @Test
    @DisplayName("版式A含送达时间行：同地址框下第二个码不被「今天15:07已按预计时间送达」阻断继承")
    fun patternA_withDeliveryTimestampLineStillInheritsGroupHeader() {
        val lines = listOf(
            line("河科院三餐申通菜鸟驿站", 160),
            line("取件出示单号后五位 55637 >", 210),
            line("申通快递: 777467057355637", 242),
            line("今天09:56已按预计时间送达", 274),
            line("查看订单详情>", 306),

            line("科技学院中国邮政", 360, 40, 200),
            line("导航", 360, 500, 50),
            line("科技学院一餐厅东侧", 390),
            line("取件码 17-8-5807 >", 440),
            line("邮政快递包裹: 9841372623431", 472),
            line("今天15:07已按预计时间送达", 504),
            line("查看订单详情>", 536),
            line("取件码 18-4-5424 >", 590),
            line("邮政快递包裹: 9841382524801", 622),
            line("今天09:38已按预计时间送达", 654),
            line("查看订单详情>", 686)
        )
        val codes = CodeExtractor.extract(lines, 1400).map { it.code }
        assertTrue("55637" in codes)
        assertTrue("17-8-5807" in codes)
        assertTrue("18-4-5424" in codes)

        assertEquals("河科院三餐申通菜鸟驿站", AddressExtractor.extractAddressForCode(lines, "55637", codes))
        assertEquals("科技学院一餐厅东侧", AddressExtractor.extractAddressForCode(lines, "17-8-5807", codes))
        assertEquals("科技学院一餐厅东侧", AddressExtractor.extractAddressForCode(lines, "18-4-5424", codes))
    }

    @Test
    @DisplayName("真机回归：OCR把「导航」误读成「早航」时，组2第2个码 3-2-8519 仍继承兔喜地址")
    fun patternA_ocrMisreadNavAsZaoHang_stillSeparatesGroup2() {
        val lines = listOf(
            line("韩梅诊所对面菜鸟", 185, 45, 85, 16),
            line("导航", 190, 255, 19, 10),
            line("韩梅诊所对面菜鸟", 205, 44, 76, 11),
            line("取件码1-3-4004", 693, 10, 157, 13),
            line("申通快递:777404279027161", 709, 77, 120, 11),
            line("立看订单详情>", 737, 8, 130, 14),
            line("免喜快递超市代收点", 775, 10, 100, 11),
            line("早航", 776, 256, 18, 8),
            line("育新路育新路 育新路北段爱玛电动车旁边", 792, 10, 177, 9),
            line("取件码3-4-4352", 818, 76, 90, 15),
            line("中通快递: 79100326535714", 838, 77, 119, 9),
            line("查看订单详情>", 869, 77, 63, 9),
            line("|取件码3-2-8519", 891, 77, 87, 18),
            line("At中通快递: 79100137525564", 911, 4, 194, 15),
            line("查看订单详情>", 945, 81, 60, 9)
        )
        val codes = listOf("1-3-4004", "3-4-4352", "3-2-8519")
        assertEquals("韩梅诊所对面菜鸟", AddressExtractor.extractAddressForCode(lines, "1-3-4004", codes))
        assertTrue(AddressExtractor.extractAddressForCode(lines, "3-4-4352", codes).contains("育新路北段爱玛电动车旁边"))
        assertTrue(AddressExtractor.extractAddressForCode(lines, "3-2-8519", codes).contains("育新路北段爱玛电动车旁边"))
    }

    @Test
    @DisplayName("真机回归：OCR把「中通快递」误读为「中通块递79162247253762」时，不得当成53762的地址")
    fun patternB_ocrMisreadTrackingLine_notPickedAsAddress() {
        val lines = listOf(
            line("菜鸟驿站", 672, 24, 92, 22),
            line("料院悠学里三楼取一楼奇中通快递", 708, 24, 304, 19),
            line("电话", 707, 524, 37, 18),
            line("取件出示快递单号后五位", 776, 117, 326, 27),
            line("53762", 816, 117, 85, 28),
            line("中通块递79162247253762", 859, 118, 283, 22)
        )
        assertEquals("料院悠学里三楼取一楼奇中通快递", AddressExtractor.extractAddressForCode(lines, "53762", listOf("53762")))
    }
}
