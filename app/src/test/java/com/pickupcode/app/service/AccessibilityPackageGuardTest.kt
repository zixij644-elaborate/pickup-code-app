package com.pickupcode.app.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 无障碍读屏/截图的包名护栏（代码检查 P0-4）。
 * 这两条判断决定"会自动扫描谁"和"拒绝读取谁"，写错一个包名就会静默失效，
 * 所以用单测把语义锁住（纯 JVM，不需要真机）。
 */
class AccessibilityPackageGuardTest {

    @Test
    @DisplayName("自动扫描白名单：真实包名与子包命中")
    fun autoScanMatchesRealPackages() {
        // 本机实测装到的真实包名（不可再写成 com.pinduoduo / com.eg.android 这种半截前缀）
        assertTrue(PickupCodeAccessibilityService.isAutoScanPackage("com.xunmeng.pinduoduo"), "拼多多")
        assertTrue(PickupCodeAccessibilityService.isAutoScanPackage("com.taobao.taobao"), "淘宝")
        assertTrue(PickupCodeAccessibilityService.isAutoScanPackage("com.jingdong.app.mall"), "京东")
        assertTrue(PickupCodeAccessibilityService.isAutoScanPackage("com.cainiao.wireless"), "菜鸟")
        assertTrue(PickupCodeAccessibilityService.isAutoScanPackage("com.sankuai.meituan"), "美团")
        assertTrue(PickupCodeAccessibilityService.isAutoScanPackage("me.ele"), "饿了么")
        // 白名单应用的真实子包也应覆盖（语义验证；注意 AccessibilityEvent.packageName 不带 ":进程名" 后缀）
        assertTrue(PickupCodeAccessibilityService.isAutoScanPackage("com.taobao.taobao.submodule"))
    }

    @Test
    @DisplayName("自动扫描白名单：同前缀的无关包不得命中（旧裸 startsWith 的漏洞）")
    fun autoScanRejectsPrefixLookalikes() {
        assertFalse(PickupCodeAccessibilityService.isAutoScanPackage("com.eg.android.EvilApp"))
        assertFalse(PickupCodeAccessibilityService.isAutoScanPackage("com.taobao.taobao2"))
        assertFalse(PickupCodeAccessibilityService.isAutoScanPackage("com.cainiao.wirelessevil"))
        assertFalse(PickupCodeAccessibilityService.isAutoScanPackage("com.eg.android.AlipayGphone"), "支付宝不得自动截图")
        assertFalse(PickupCodeAccessibilityService.isAutoScanPackage("com.example.app"))
    }

    @Test
    @DisplayName("敏感应用拒采：银行/支付/验证器/密码管理器命中")
    fun sensitivePackagesAreRefused() {
        assertTrue(PickupCodeAccessibilityService.isSensitivePackage("cmb.pb"), "招商银行")
        assertTrue(PickupCodeAccessibilityService.isSensitivePackage("com.android.bankabc"), "农业银行")
        assertTrue(PickupCodeAccessibilityService.isSensitivePackage("com.unionpay"), "银联")
        assertTrue(PickupCodeAccessibilityService.isSensitivePackage("com.eg.android.AlipayGphone"), "支付宝")
        assertTrue(PickupCodeAccessibilityService.isSensitivePackage("com.eg.android.AlipayGphone.wallet"), "支付宝子包")
        assertTrue(
            PickupCodeAccessibilityService.isSensitivePackage("com.google.android.apps.authenticator2"),
            "身份验证器"
        )
        assertTrue(PickupCodeAccessibilityService.isSensitivePackage("com.x8bit.bitwarden"), "密码管理器")
        assertTrue(PickupCodeAccessibilityService.isSensitivePackage("com.cmbchina.ccd.pluto.cmbActivity"), "招行子包")
    }

    @Test
    @DisplayName("敏感清单不能误伤取件码的正常来源（微信/淘宝/拼多多/菜鸟）")
    fun pickupSourcesAreNotSensitive() {
        assertFalse(PickupCodeAccessibilityService.isSensitivePackage("com.tencent.mm"), "微信是取件码正当来源")
        assertFalse(PickupCodeAccessibilityService.isSensitivePackage("com.tencent.mobileqq"))
        assertFalse(PickupCodeAccessibilityService.isSensitivePackage("com.taobao.taobao"))
        assertFalse(PickupCodeAccessibilityService.isSensitivePackage("com.xunmeng.pinduoduo"))
        assertFalse(PickupCodeAccessibilityService.isSensitivePackage("com.cainiao.wireless"))
    }
}
