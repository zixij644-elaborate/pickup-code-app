package com.pickupcode.app.extractor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

class CodeValidatorTest {

    @Test
    @DisplayName("valid three-segment (1-6-5020)")
    fun valid_threeSegment() {
        assertTrue(CodeValidator.isValidPickupCode("1-6-5020"))
        assertTrue(CodeValidator.isValidPickupCode("99-12-123456"))
    }

    @Test
    @DisplayName("valid letter-prefix (A-3-315)")
    fun valid_letterTwoSegment() {
        assertTrue(CodeValidator.isValidPickupCode("A-3-315"))
    }

    @Test
    @DisplayName("valid 兔喜式单段码 (5-3858 / 12-3456)")
    fun valid_digitDashSegment() {
        assertTrue(CodeValidator.isValidPickupCode("5-3858"))
        assertTrue(CodeValidator.isValidPickupCode("12-3456"))
        assertTrue(CodeValidator.isValidPickupCode("8-2014"))
    }

    @Test
    @DisplayName("valid long number")
    fun valid_longNumber() {
        assertTrue(CodeValidator.isValidPickupCode("281849"))
    }

    @Test
    @DisplayName("valid food code (A23)")
    fun valid_foodCode() {
        assertTrue(CodeValidator.isValidPickupCode("A23"))
    }

    @Test
    @DisplayName("rejects blank")
    fun invalid_blank() {
        assertFalse(CodeValidator.isValidPickupCode(""))
    }

    @Test
    @DisplayName("rejects too-long number")
    fun invalid_tooLong() {
        assertFalse(CodeValidator.isValidPickupCode("123456789012345"))
    }

    @Test
    @DisplayName("rejects bare 2-3 digit numbers (catch-all 收紧回归)")
    fun invalid_bareShortDigits() {
        assertFalse(CodeValidator.isValidPickupCode("12"))
        assertFalse(CodeValidator.isValidPickupCode("42"))
        assertFalse(CodeValidator.isValidPickupCode("123"))
    }

    @Test
    @DisplayName("isContentNoise：短码内容噪声检查可复用（123 非噪声，000/1234 递增是噪声）")
    fun contentNoise() {
        assertFalse(CodeValidator.isContentNoise("123"))
        assertTrue(CodeValidator.isContentNoise("000"))
        assertTrue(CodeValidator.isContentNoise("0000"))
        assertTrue(CodeValidator.isContentNoise("1234"))
        assertTrue(CodeValidator.isContentNoise("1111"))
    }

    @Test
    @DisplayName("手动录入：取餐码允许 2-3 位纯数字（与 AI 路径对齐）")
    fun manualFoodShortDigits() {
        assertTrue(CodeValidator.isValidManualCode("123", "pickup_food"))
        assertTrue(CodeValidator.isValidManualCode("42", "pickup_food"))
        assertTrue(CodeValidator.isValidManualCode(" 229 ", "pickup_food"))
        // 内容噪声仍拦截
        assertFalse(CodeValidator.isValidManualCode("000", "pickup_food"))
        assertFalse(CodeValidator.isValidManualCode("1111", "pickup_food"))
    }

    @Test
    @DisplayName("手动录入：取件码仍拒绝 2-3 位裸数字（噪声）")
    fun manualParcelShortDigits() {
        assertFalse(CodeValidator.isValidManualCode("123", "pickup_parcel"))
        assertFalse(CodeValidator.isValidManualCode("42", "pickup_parcel"))
        assertTrue(CodeValidator.isValidManualCode("5-3858", "pickup_parcel"))
        assertTrue(CodeValidator.isValidManualCode("10-2-7507", "pickup_parcel"))
    }

    @Test
    @DisplayName("accepts 4-5 digit pure number as food code")
    fun valid_pureNumberFood() {
        assertTrue(CodeValidator.isValidPickupCode("2024"))
        assertTrue(CodeValidator.isValidPickupCode("12345"))
    }

    @Test
    @DisplayName("accepts prefixed alphanumeric code (AB12)")
    fun valid_prefixedAlphanumeric() {
        assertTrue(CodeValidator.isValidPickupCode("AB12"))
        assertTrue(CodeValidator.isValidPickupCode("A1-2-3456"))
    }

    @Test
    @DisplayName("rejects pure-letter string (ABC)")
    fun invalid_pureLetter() {
        assertFalse(CodeValidator.isValidPickupCode("ABC"))
    }

    @Test
    @DisplayName("classifyFormat returns stable IDs")
    fun format_stable() {
        assertEquals("THREE_SEGMENT_PARCEL", CodeValidator.getPatternId("1-6-5020"))
        assertEquals("LONG_NUMBER_PARCEL", CodeValidator.getPatternId("281849"))
        assertEquals("PREFIXED_CODE", CodeValidator.getPatternId("ABC"))
        assertEquals("DIGIT_DASH_PARCEL", CodeValidator.getPatternId("5-3858"))
    }
}
