package com.pickupcode.app.extractor

import com.pickupcode.app.ocr.OCREngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression: 取件码窗口内出现「快递运单号行」（如 中通快递:79152640318774）时，
 * 不得把它当成本码的取件地址——此前 extractAddressForCode 的"窗口最长地址行"兜底
 * 会误抓该行导致入库地址错误（v1.0.x bug, 2026-08-17 复现于免喜扫码件）。
 */
class CourierTrackingLineAddrTest {

    // 日志原始 OCR 行（index, text, x,y,w,h）
    private val raw = listOf(
        0 to "该每单免用后付0元下单:宗36%", 1 to "微信支付", 2 to "也AH", 3 to "已扣费¥19.99",
        4 to "中华人民天利E", 5 to "发新阳市", 6 to "取件码6-1904",
        7 to "免喜快递超市代收点|长兴路长兴路", 8 to "长兴路北段老李超市旁边",
        9 to "中通快递:79152640318774", 10 to "快递员:刘明义", 11 to "物流服务",
        12 to "待取件 2026-08-15 11:22:11", 13 to "订单蝙号: 260813-71204563..9081",
        14 to "9PUPp", 15 to "快件己送达 [免喜生活-新阳安", 16 to "平长兴路北段店,电话..",
        17 to "展开ン", 18 to "现在", 19 to "复制", 20 to "拨打电话",
        21 to "收货地址: 河南省新阳市安平县新新北 展开v", 22 to "复制", 23 to "拔打电话",
        24 to "复制", 25 to "订阅提醒"
    )
    private val boxes = mapOf(
        0 to "25,18,528,34", 1 to "134,103,96,22", 2 to "250,0,49,11", 3 to "136,140,133,21",
        4 to "45,227,197,29", 5 to "233,243,120,24", 6 to "23,440,259,33", 7 to "23,493,417,28",
        8 to "23,529,314,24", 9 to "77,620,343,29", 10 to "27,714,243,32", 11 to "419,101,97,24",
        12 to "89,923,297,23", 13 to "89,804,421,24", 14 to "166,1141,36,10", 15 to "89,959,353,25",
        16 to "89,996,283,25", 17 to "272,1039,85,23", 18 to "544,105,33,15", 19 to "547,445,42,21",
        20 to "494,508,102,24", 21 to "90,846,524,28", 22 to "547,623,41,21", 23 to "494,719,102,24",
        24 to "546,806,41,21", 25 to "494,935,101,29"
    )

    private fun lines(): List<OCREngine.TextLine> = raw.map { (i, t) ->
        val (x, y, w, h) = boxes[i]!!.split(",").map { it.trim().toInt() }
        OCREngine.TextLine(t, OCREngine.LineBox(x, y, x + w, y + h), 0.7f)
    }

    @Test
    fun `窗口地址不得被运单号行污染`() {
        val ls = lines()
        val allText = ls.joinToString(" ") { it.text }
        val loc = AddressExtractor.extractLocation(ls, allText)
        // 全屏地址保持正确
        assertEquals("河南省新阳市安平县新新北", loc.fullAddress)
        // 窗口地址（入库实际生效）落到站点+街道，而不是运单号行
        val per = AddressExtractor.extractAddressForCode(ls, "6-1904")
        val eff = AddressExtractor.resolveAddress(ls, allText, per, loc.fullAddress)
        assertEquals("免喜快递超市代收点|长兴路长兴路", eff)
        // 运单号行本身绝不能当成地址
        val m = AddressExtractor::class.java
            .getDeclaredMethod("isAddressLike", String::class.java)
            .apply { isAccessible = true }
        assertEquals(false, m.invoke(AddressExtractor, "中通快递:79152640318774") as Boolean)
    }
}

