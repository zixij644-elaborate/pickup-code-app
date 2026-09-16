package com.pickupcode.app.ui.screens.home

import com.pickupcode.app.data.CodeHistory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class HomeGroupingTest {

    private fun item(id: Long, addr: String) = CodeHistory(
        id = id,
        code = "C$id",
        type = "pickup_parcel",
        source = "韵达",
        rawTextSnippet = "snippet",
        pickupAddress = addr
    )

    @Test
    @DisplayName("按地址聚合：码多的地址排前，空地址归末尾")
    fun group_by_address_order() {
        val items = listOf(
            item(1, "长兴路3号柜"),
            item(2, ""),                  // 空地址
            item(3, "长青街快递柜"),
            item(4, "长兴路3号柜"),
            item(5, "长青街快递柜"),
            item(6, "长青街快递柜")
        )
        val groups = HomeGrouping.byAddress(items)
        // 长青街快递柜(3) > 长兴路3号柜(2) > ""(1)
        assertEquals(listOf("长青街快递柜", "长兴路3号柜", ""), groups.map { it.first })
        assertEquals(3, groups[0].second.size)
        assertEquals(2, groups[1].second.size)
        assertEquals(1, groups[2].second.size)
        // 组内保持原顺序
        assertEquals(listOf(3L, 5L, 6L), groups[0].second.map { it.id })
    }

    @Test
    @DisplayName("全空地址 → 全部归入一组")
    fun group_all_blank() {
        val items = listOf(item(1, "  "), item(2, ""))
        val groups = HomeGrouping.byAddress(items)
        assertEquals(1, groups.size)
        assertEquals("", groups[0].first)   // UI 显示为「未填地址」
        assertEquals(2, groups[0].second.size)
    }

    @Test
    @DisplayName("空列表 → 空分组")
    fun group_empty() {
        assertEquals(0, HomeGrouping.byAddress(emptyList()).size)
    }
}
