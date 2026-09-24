package com.pickupcode.app.extractor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 运单号提取：Android(ICU) 下中文是"词字符"，`\b` 在中文与数字之间**不成立**
 * （项目自己在 CodeValidator / PatternLearner 里记录过这个坑）。
 * 真实通知里运单号几乎总紧贴中文，历史实现会导致品牌判定退化、快递100 反查链路静默失效。
 */
class BrandResolverTest {

    @Test
    @DisplayName("运单号紧贴中文：必须能抓到（\\b 版本会漏）")
    fun orderNumberAdjacentToChinese() {
        assertEquals("435316307300011", BrandResolver.findOrderNumber("韵达快递435316307300011"))
        assertEquals(
            "435316307300011",
            BrandResolver.findOrderNumber("您的包裹已到，韵达快递435316307300011，请及时取件")
        )
    }

    @Test
    @DisplayName("运单号被中文包裹在中间：也要能抓到")
    fun orderNumberSurroundedByChinese() {
        assertEquals("79152640318774", BrandResolver.findOrderNumber("【中通快递】单号79152640318774已到驿站"))
    }

    @Test
    @DisplayName("英文前缀单号（含 CN 尾缀）照常命中")
    fun prefixedOrderNumber() {
        assertEquals("RA123456789CN", BrandResolver.findOrderNumber("RA123456789CN"))
        assertEquals("SF1234567890123", BrandResolver.findOrderNumber("顺丰SF1234567890123"))
    }

    @Test
    @DisplayName("超长数字串不得被截出子串（环视边界的作用）")
    fun doesNotMatchSubstringOfLongerDigitRun() {
        // 16 位：超出 \d{13,15}，整串都不该命中，更不能截出 15 位子串
        assertNull(BrandResolver.findOrderNumber("1234567890123456"))
    }
}
