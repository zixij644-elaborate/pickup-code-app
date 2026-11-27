package com.pickupcode.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 异步更新检测器。
 *
 * 供两处复用：
 * 1. 开屏静默检测（MainActivity 启动时异步执行，失败不打扰用户，有新版时弹窗告知）；
 * 2. 设置页「检查更新」按钮（手动触发，展示具体结果或弹出更新对话框）。
 */
object UpdateChecker {

    const val REPO_URL = "https://github.com/zixij644-elaborate/pickup-code-app"
    const val RELEASES_PAGE_URL = "$REPO_URL/releases/latest"
    private const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/zixij644-elaborate/pickup-code-app/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    data class ReleaseInfo(
        val versionName: String,
        val title: String,
        val notes: String,
        val htmlUrl: String
    )

    sealed class CheckResult {
        data class Available(val release: ReleaseInfo) : CheckResult()
        data object UpToDate : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    /**
     * 在 IO 线程请求 GitHub latest release 并与 [currentVersion] 做语义化版本比对。
     * 捕获所有异常并返回 [CheckResult.Error]，不会向外抛出影响主流程。
     */
    suspend fun checkLatest(currentVersion: String): CheckResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(LATEST_RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "pickup-code-app-checkupdate")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return@withContext CheckResult.Error("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            evaluateReleaseJson(body, currentVersion)
        } catch (e: Exception) {
            CheckResult.Error(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /** 解析 GitHub Release JSON 并与 [currentVersion] 比较（纯函数，便于单测）。 */
    internal fun evaluateReleaseJson(jsonText: String, currentVersion: String): CheckResult {
        return try {
            val obj = JSONObject(jsonText)
            if (obj.optBoolean("draft", false) || obj.optBoolean("prerelease", false)) {
                return CheckResult.UpToDate
            }
            val rawTag = obj.optString("tag_name", "").trim()
            val latestVersion = normalizeVersion(rawTag)
            if (latestVersion.isEmpty()) {
                return CheckResult.Error("版本信息为空")
            }
            if (!isNewerVersion(latestVersion, currentVersion)) {
                return CheckResult.UpToDate
            }
            val title = obj.optString("name", "").trim().ifEmpty { "v$latestVersion" }
            val notes = obj.optString("body", "").trim()
            val rawUrl = obj.optString("html_url", "").trim()
            val safeUrl = if (rawUrl.startsWith("https://") || rawUrl.startsWith("http://")) {
                rawUrl
            } else {
                RELEASES_PAGE_URL
            }
            CheckResult.Available(
                ReleaseInfo(
                    versionName = latestVersion,
                    title = title,
                    notes = notes,
                    htmlUrl = safeUrl
                )
            )
        } catch (e: Exception) {
            CheckResult.Error(e.message?.takeIf { it.isNotBlank() } ?: "解析失败")
        }
    }

    /** 去掉前导 v/V 与首尾空白。 */
    internal fun normalizeVersion(version: String): String =
        version.trim().removePrefix("v").removePrefix("V").trim()

    /**
     * 判断 [candidate] 是否严格高于 [current]。
     *
     * 规则：
     * - 去掉前导 `v`/`V`，分离 `-prerelease` 后缀；
     * - 主版本按 `.` 拆分逐段数值比较（`1.1.10 > 1.1.2`，缺失段按 0 补齐）；
     * - 主版本相等时：正式版（无 `-` 后缀）高于预发布版（有 `-` 后缀）。
     */
    internal fun isNewerVersion(candidate: String, current: String): Boolean {
        val cand = parseVersion(candidate) ?: return false
        val curr = parseVersion(current) ?: return false
        val maxLen = maxOf(cand.numbers.size, curr.numbers.size)
        for (i in 0 until maxLen) {
            val a = cand.numbers.getOrElse(i) { 0 }
            val b = curr.numbers.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        // 主版本号完全相同：正式版 > 预发布版
        if (cand.prerelease == null && curr.prerelease != null) return true
        if (cand.prerelease != null && curr.prerelease == null) return false
        if (cand.prerelease != null && curr.prerelease != null) {
            return cand.prerelease > curr.prerelease
        }
        return false
    }

    private data class ParsedVersion(val numbers: List<Int>, val prerelease: String?)

    private fun parseVersion(raw: String): ParsedVersion? {
        val cleaned = normalizeVersion(raw).substringBefore('+')
        if (cleaned.isEmpty()) return null
        val dashIdx = cleaned.indexOf('-')
        val corePart = if (dashIdx >= 0) cleaned.substring(0, dashIdx) else cleaned
        val prerelease = if (dashIdx >= 0) cleaned.substring(dashIdx + 1).ifBlank { null } else null
        val segments = corePart.split('.')
        if (segments.isEmpty()) return null
        val nums = segments.map { seg -> seg.toIntOrNull() ?: return null }
        return ParsedVersion(nums, prerelease)
    }
}
