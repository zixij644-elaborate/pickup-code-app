package com.pickupcode.app.extractor

import com.pickupcode.app.learner.SavedAddressStore
import com.pickupcode.app.ocr.OCREngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 预存地址匹配：**完整名称 + 一个至多个关键词**，命中关键词 → 用完整名称。
 * 纯函数，桌面 JVM 可测（这也是把它做成纯函数的原因）。
 */
class SavedAddressMatcherTest {

    private fun line(text: String) = OCREngine.TextLine(text = text, boundingBox = null, confidence = null)
    private fun view(fullName: String, vararg keywords: String) =
        SavedAddressStore.MatcherView(fullName = fullName, keywords = keywords.toList())

    @Test
    @DisplayName("命中关键词 → 返回完整名称（而不是 OCR 原文）")
    fun keywordHitReturnsFullName() {
        val saved = listOf(view("长兴路北段菜鸟驿站", "北段驿站", "长兴路"))
        val r = SavedAddressMatcher.match(
            listOf(line("【通知】北段驿站 您的包裹已到 取件码 1-2-3456 请尽快取件")),
            saved
        )
        assertEquals("长兴路北段菜鸟驿站", r?.fullName, "主页显示的应是完整名称")
        assertEquals("北段驿站", r?.key)
    }

    @Test
    @DisplayName("关键词留空 → 用完整名称兜底匹配")
    fun emptyKeywordsFallBackToFullName() {
        val saved = listOf(view("妈妈驿站"))
        val r = SavedAddressMatcher.match(listOf(line("妈妈驿站 取件码 9-9-9001")), saved)
        assertEquals("妈妈驿站", r?.fullName)
        assertEquals("妈妈驿站", r?.key)
    }

    @Test
    @DisplayName("有关键词时，完整名称本身不再参与匹配（按用户模型：关键词才是依据）")
    fun fullNameNotUsedAsKeyWhenKeywordsPresent() {
        val saved = listOf(view("长兴路北段菜鸟驿站", "北段驿站"))
        // 文本里只有完整名称、没有关键词 → 不命中
        assertNull(SavedAddressMatcher.match(listOf(line("长兴路北段菜鸟驿站 取件码 1-1-1111")), saved))
        // 出现关键词 → 命中
        assertEquals(
            "长兴路北段菜鸟驿站",
            SavedAddressMatcher.match(listOf(line("北段驿站 取件码 1-1-1111")), saved)?.fullName
        )
    }

    @Test
    @DisplayName("最长关键词优先：具体词不被短词抢走")
    fun longestKeywordWins() {
        val saved = listOf(
            view("丰巢（短名）", "丰巢"),
            view("丰巢快递柜(XX路店)", "丰巢快递柜(XX路店)")
        )
        val r = SavedAddressMatcher.match(listOf(line("包裹已存入丰巢快递柜(XX路店)")), saved)
        assertEquals("丰巢快递柜(XX路店)", r?.fullName)
    }

    @Test
    @DisplayName("单字关键词不参与匹配（防误命中）")
    fun singleCharKeywordIgnored() {
        assertNull(SavedAddressMatcher.match(listOf(line("2号柜")), listOf(view("某站", "柜"))))
    }

    @Test
    @DisplayName("多行命中取行号最小的那一行")
    fun firstMatchingLineWins() {
        val saved = listOf(view("菜鸟驿站", "驿站"))
        val r = SavedAddressMatcher.match(
            listOf(line("无关行"), line("北段驿站 取件码 1-1-1111"), line("驿站 另一处")),
            saved
        )
        assertEquals(1, r?.lineIndex)
    }

    @Test
    @DisplayName("英文关键词大小写不敏感；未命中返回 null；空输入安全")
    fun edgeCases() {
        val saved = listOf(view("顺丰站点", "sfexpress"))
        assertEquals("顺丰站点", SavedAddressMatcher.match(listOf(line("SFExpress 到件")), saved)?.fullName)
        assertNull(SavedAddressMatcher.match(listOf(line("完全不相关的文本")), saved))
        assertNull(SavedAddressMatcher.match(emptyList(), saved))
        assertNull(SavedAddressMatcher.match(listOf(line("菜鸟驿站")), emptyList()))
    }

    @Test
    @DisplayName("名称为空的条目不参与（避免用空名称覆盖识别结果）")
    fun blankFullNameIgnored() {
        assertNull(SavedAddressMatcher.match(listOf(line("菜鸟驿站 取件码 1-1-1111")), listOf(view("   "))))
    }
}
