package com.pickupcode.app.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateCheckerTest {

    @Test
    fun `isNewerVersion compares semantic version segments numerically`() {
        assertTrue(UpdateChecker.isNewerVersion("1.1.3", "1.1.2"))
        assertTrue(UpdateChecker.isNewerVersion("1.2.0", "1.1.2"))
        assertTrue(UpdateChecker.isNewerVersion("2.0.0", "1.1.2"))
        assertTrue(UpdateChecker.isNewerVersion("1.1.10", "1.1.2"))
        assertTrue(UpdateChecker.isNewerVersion("v1.1.3", "1.1.2"))
        assertTrue(UpdateChecker.isNewerVersion("1.1.2.1", "1.1.2"))

        assertFalse(UpdateChecker.isNewerVersion("1.1.2", "1.1.2"))
        assertFalse(UpdateChecker.isNewerVersion("v1.1.2", "1.1.2"))
        assertFalse(UpdateChecker.isNewerVersion("1.1.1", "1.1.2"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.99", "1.1.2"))
        assertFalse(UpdateChecker.isNewerVersion("1.1.2.0", "1.1.2"))
    }

    @Test
    fun `isNewerVersion handles prerelease and invalid strings`() {
        assertFalse(UpdateChecker.isNewerVersion("1.1.2-beta.1", "1.1.2"))
        assertTrue(UpdateChecker.isNewerVersion("1.1.2", "1.1.2-beta.1"))
        assertTrue(UpdateChecker.isNewerVersion("1.1.3-rc.1", "1.1.2"))
        assertFalse(UpdateChecker.isNewerVersion("", "1.1.2"))
        assertFalse(UpdateChecker.isNewerVersion("invalid", "1.1.2"))
    }

    @Test
    fun `evaluateReleaseJson returns Available for newer release`() {
        val json = """
            {
              "tag_name": "v1.1.3",
              "name": "v1.1.3 更新",
              "body": "- 修复若干问题\n- 新增开屏更新检测",
              "html_url": "https://github.com/zixij644-elaborate/pickup-code-app/releases/tag/v1.1.3",
              "draft": false,
              "prerelease": false
            }
        """.trimIndent()

        val result = UpdateChecker.evaluateReleaseJson(json, "1.1.2")
        assertTrue(result is UpdateChecker.CheckResult.Available)
        val release = (result as UpdateChecker.CheckResult.Available).release
        assertEquals("1.1.3", release.versionName)
        assertEquals("v1.1.3 更新", release.title)
        assertEquals("- 修复若干问题\n- 新增开屏更新检测", release.notes)
        assertEquals("https://github.com/zixij644-elaborate/pickup-code-app/releases/tag/v1.1.3", release.htmlUrl)
    }

    @Test
    fun `evaluateReleaseJson returns UpToDate for same, older, draft, or prerelease`() {
        val same = """{"tag_name":"v1.1.2","draft":false,"prerelease":false}"""
        val older = """{"tag_name":"v1.1.1","draft":false,"prerelease":false}"""
        val draft = """{"tag_name":"v1.2.0","draft":true,"prerelease":false}"""
        val prerelease = """{"tag_name":"v1.2.0","draft":false,"prerelease":true}"""

        assertEquals(UpdateChecker.CheckResult.UpToDate, UpdateChecker.evaluateReleaseJson(same, "1.1.2"))
        assertEquals(UpdateChecker.CheckResult.UpToDate, UpdateChecker.evaluateReleaseJson(older, "1.1.2"))
        assertEquals(UpdateChecker.CheckResult.UpToDate, UpdateChecker.evaluateReleaseJson(draft, "1.1.2"))
        assertEquals(UpdateChecker.CheckResult.UpToDate, UpdateChecker.evaluateReleaseJson(prerelease, "1.1.2"))
    }

    @Test
    fun `evaluateReleaseJson falls back to safe releases url when html_url is invalid`() {
        val json = """
            {
              "tag_name": "v1.2.0",
              "html_url": "javascript:alert(1)"
            }
        """.trimIndent()

        val result = UpdateChecker.evaluateReleaseJson(json, "1.1.2") as UpdateChecker.CheckResult.Available
        assertEquals(UpdateChecker.RELEASES_PAGE_URL, result.release.htmlUrl)
    }

    @Test
    fun `evaluateReleaseJson returns Error for malformed json`() {
        val result = UpdateChecker.evaluateReleaseJson("{not-json", "1.1.2")
        assertTrue(result is UpdateChecker.CheckResult.Error)
    }
}
