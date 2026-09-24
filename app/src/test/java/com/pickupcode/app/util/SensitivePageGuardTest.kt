package com.pickupcode.app.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 身份码 / 出库码页面拦截（隐私红线）。
 * 这条判断决定"会不会把取件凭证截图并入库"，必须锁住语义。
 */
class SensitivePageGuardTest {

    @Test
    @DisplayName("身份码/出库码页面：识别为敏感（不读不截不入库）")
    fun detectsIdentityPages() {
        assertTrue(SensitivePageGuard.isIdentityCodePage("我的身份码"))
        assertTrue(SensitivePageGuard.isIdentityCodePage("菜鸟出库码"))
        assertTrue(SensitivePageGuard.isIdentityCodePage("请向驿站出示身份条形码"))
        assertTrue(SensitivePageGuard.isIdentityCodePage("身份码 7-1-2233 请勿外传"))
    }

    @Test
    @DisplayName("普通取件码/取餐码文本不得被误拦（不能把主功能砍掉）")
    fun doesNotBlockNormalPickupText() {
        assertFalse(SensitivePageGuard.isIdentityCodePage("【菜鸟驿站】您的取件码 3-7-4162 已到"))
        assertFalse(SensitivePageGuard.isIdentityCodePage("您的取餐码 A-3-315 请取餐"))
        assertFalse(SensitivePageGuard.isIdentityCodePage("包裹已放入快递柜，凭取件码 8-1-2233 取件"))
        assertFalse(SensitivePageGuard.isIdentityCodePage("验证码 618008，请勿告知他人"))
    }

    @Test
    @DisplayName("空文本安全")
    fun blankIsSafe() {
        assertFalse(SensitivePageGuard.isIdentityCodePage(""))
        assertFalse(SensitivePageGuard.isIdentityCodePage("   "))
    }
}
