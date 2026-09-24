package com.pickupcode.app.extractor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/** ExpiryExtractor 到期时间提取单测（2026-08-13 v6 功能）。 */
class ExpiryExtractorTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-08-13 12:00（北京时间）——固定基准时间避免测试随运行日期漂移。 */
    private val baseMs = Instant.parse("2026-08-13T04:00:00Z").toEpochMilli()

    // ── 第一层：文本提取 ──

    @Test
    fun `extractExpiryText 提取请于句式`() {
        assertEquals("8月15日前取件", ExpiryExtractor.extractExpiryText("您的包裹已到驿站，请于8月15日前取件"))
    }

    @Test
    fun `extractExpiryText 提取截止与最晚变体`() {
        assertEquals("8月16日", ExpiryExtractor.extractExpiryText("取件截止8月16日，逾期退回"))
        // 无标点时限取到行尾（解析层命中"今天"即正确）
        assertEquals("今天21点前取件", ExpiryExtractor.extractExpiryText("最晚今天21点前取件"))
    }

    @Test
    fun `extractExpiryText 提取完整年月日句式`() {
        assertEquals("2026年8月15日前取件", ExpiryExtractor.extractExpiryText("您的包裹已到驿站，请于2026年8月15日前取件"))
    }

    @Test
    fun `extractExpiryText 无时限返回 null`() {
        assertNull(ExpiryExtractor.extractExpiryText("凭3-7-4162到驿站取件"))
        assertNull(ExpiryExtractor.extractExpiryText(""))
    }

    // ── 第二层：解析 ──

    @Test
    fun `parseDeadline 今日返回当天20点`() {
        val r = ExpiryExtractor.parseDeadline("今日取件", baseMs, zone)
        assertNotNull(r)
        // 2026-08-13 20:00 北京时间 = 12:00 UTC
        assertEquals(Instant.parse("2026-08-13T12:00:00Z").toEpochMilli(), r)
    }

    @Test
    fun `parseDeadline 明日返回次日20点`() {
        val r = ExpiryExtractor.parseDeadline("明天前取件", baseMs, zone)
        assertEquals(Instant.parse("2026-08-14T12:00:00Z").toEpochMilli(), r)
    }

    @Test
    fun `parseDeadline 月日解析为当年日期`() {
        val r = ExpiryExtractor.parseDeadline("8月15日前取件", baseMs, zone)
        assertEquals(Instant.parse("2026-08-15T12:00:00Z").toEpochMilli(), r)
    }

    @Test
    fun `parseDeadline 跨年自动加一年`() {
        // 12 月 30 日收到 "1月2日" —— 应是次年 1 月 2 日，不是当年已过的日期
        val decMs = Instant.parse("2026-12-30T04:00:00Z").toEpochMilli()
        val r = ExpiryExtractor.parseDeadline("请于1月2日前取件", decMs, zone)
        assertEquals(Instant.parse("2027-01-02T12:00:00Z").toEpochMilli(), r)
    }

    @Test
    fun `parseDeadline 无法解析返回 null`() {
        assertNull(ExpiryExtractor.parseDeadline("请尽快取件", baseMs, zone))
    }

    // ── 非法日期防御（H1 崩溃修复回归测试）──

    @Test
    fun `parseDeadline 完整年月日横杠格式解析`() {
        // 回归：此前 "2026-08-15" 被 MONTH_DAY_REGEX 错配成"月=26"而静默回退默认时长
        val r = ExpiryExtractor.parseDeadline("有效期至2026-08-15", baseMs, zone)
        assertEquals(Instant.parse("2026-08-15T12:00:00Z").toEpochMilli(), r)
    }

    @Test
    fun `parseDeadline 完整年月日中文格式解析`() {
        val r = ExpiryExtractor.parseDeadline("请于2026年8月15日前取件", baseMs, zone)
        assertEquals(Instant.parse("2026-08-15T12:00:00Z").toEpochMilli(), r)
    }

    @Test
    fun `parseDeadline 完整年月日斜杠与点格式解析`() {
        assertEquals(Instant.parse("2026-08-15T12:00:00Z").toEpochMilli(),
            ExpiryExtractor.parseDeadline("2026/8/15前取件", baseMs, zone))
        assertEquals(Instant.parse("2026-08-15T12:00:00Z").toEpochMilli(),
            ExpiryExtractor.parseDeadline("2026.8.15前取件", baseMs, zone))
    }

    @Test
    fun `parseDeadline 显式年份当日到期有效`() {
        // 入库当天到期的完整日期：显式年份不做跨年处理
        val r = ExpiryExtractor.parseDeadline("请于2026-08-13前取件", baseMs, zone)
        assertEquals(Instant.parse("2026-08-13T12:00:00Z").toEpochMilli(), r)
    }

    @Test
    fun `parseDeadline 显式年份日期已过期返回 null`() {
        // 年份早于入库日（旧短信/OCR 残留）：视为不可用，回退默认时长
        assertNull(ExpiryExtractor.parseDeadline("有效期至2025-08-15", baseMs, zone))
    }

    @Test
    fun `parseDeadline 非法日不崩溃并返回 null`() {
        assertNull(ExpiryExtractor.parseDeadline("8月32日前取件", baseMs, zone))
    }

    @Test
    fun `parseDeadline 非闰年2月29日不崩溃并返回 null`() {
        // 2026 年不是闰年
        assertNull(ExpiryExtractor.parseDeadline("2月29日前取件", baseMs, zone))
    }

    @Test
    fun `parseDeadline 非法日期触发默认兜底而非崩溃`() {
        // 端到端：非法日期应回退默认 72h 时长，而不是抛异常
        val r = ExpiryExtractor.expiryTimeFor("有效期至8月32日", CodeExtractor.CodeType.pickup_parcel, baseMs)
        assertEquals(baseMs + ExpiryExtractor.DEFAULT_PARCEL_LIFETIME_MS, r)
    }

    // ── 第三层：类别兜底 ──

    @Test
    fun `expiryTimeFor 取餐码不提醒`() {
        assertNull(ExpiryExtractor.expiryTimeFor("取餐码 A12", CodeExtractor.CodeType.pickup_food, baseMs))
    }

    @Test
    fun `expiryTimeFor 券码不提醒`() {
        assertNull(ExpiryExtractor.expiryTimeFor("券号 123456", CodeExtractor.CodeType.coupon, baseMs))
    }

    @Test
    fun `expiryTimeFor 快递无文本时限回退默认72小时`() {
        val r = ExpiryExtractor.expiryTimeFor("凭3-7-4162到驿站取件", CodeExtractor.CodeType.pickup_parcel, baseMs)
        assertEquals(baseMs + ExpiryExtractor.DEFAULT_PARCEL_LIFETIME_MS, r)
    }

    @Test
    fun `expiryTimeFor 快递有文本时限信文本`() {
        // 显式传 Asia/Shanghai：避免 CI（UTC 时区）与本地（上海时区）结果漂移
        val r = ExpiryExtractor.expiryTimeFor("请于8月15日前取件", CodeExtractor.CodeType.pickup_parcel, baseMs, zone)
        assertEquals(Instant.parse("2026-08-15T12:00:00Z").toEpochMilli(), r)
    }

    @Test
    fun `expiryTimeFor 快递完整年月日时限信文本`() {
        val r = ExpiryExtractor.expiryTimeFor("请于2026-08-15前取件", CodeExtractor.CodeType.pickup_parcel, baseMs, zone)
        assertEquals(Instant.parse("2026-08-15T12:00:00Z").toEpochMilli(), r)
    }

    @Test
    fun `expiryTimeFor 快递文本不可解析回退默认时长`() {
        val r = ExpiryExtractor.expiryTimeFor("请尽快取件", CodeExtractor.CodeType.pickup_parcel, baseMs)
        assertEquals(baseMs + ExpiryExtractor.DEFAULT_PARCEL_LIFETIME_MS, r)
    }
}
