package com.pickupcode.app.learner

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object PatternLearner {

    private const val TAG = "PatternLearner"

    private const val PREFS = "pattern_learner"
    private const val KEY_TOTAL = "total_scans"
    private const val KEY_ATTEMPTS = "attempts"
    private const val KEY_MISSES = "misses"
    private const val KEY_VERIFIED = "verified"
    private const val KEY_PAT_PREFIX = "pat_"
    private const val MAX_UNMATCHED = 100
    private const val MIN_SUGGEST = 3

    // 候选码段提取 — 从乱文本中抠出可能是码的孤立数字/字母数字+连字符段。
    // 注意：原始字符串里用单反斜杠(\d/\b)，双反斜杠会匹配不到（正则双重转义 bug）。
    // 不使用 \b 边界（对中文/OCR 混排文本不可靠），改用结构正则 + 前后否定断言。
    private val SEG_CANDIDATE = Regex(
        """[A-Za-z]?\d{1,2}-\d{1,2}-\d{3,6}|\d{3,6}-\d{3,6}|[A-Za-z]-\d{4,6}|\d{1,2}-\d{3,5}""",
        RegexOption.IGNORE_CASE
    )
    // 纯数字候选：3-6 位，且前后不能是数字/字母（避免截断长订单号、电话等）
    private val PURE_CANDIDATE = Regex("""(?<![\dA-Za-z])(\d{3,6})(?![\dA-Za-z])""")

    // 候选排除上下文 — 避免价格/数量/时长/楼层/度量等干扰片段被喂入学习池
    // ⚠️ 末尾的 x\d{1,2} 不能用 \b（Android/ICU 下中文邻接时 \b 不成立），改环视边界
    private val CANDIDATE_EXCLUDE_CTX = Regex(
        """(?:\d+[元块]|\d+[份件个杯]|\d+[分钟]|\d+[号号楼栋室层]|""" +
        """\d+[折]|\d+[毫升升]|x\d{1,2}(?![\dA-Za-z])|\d{8,})""",
        RegexOption.IGNORE_CASE
    )

    /** 从一段 OCR 文本中提取候选码段。先找带连字符的完整码段，再退而求其次找孤立纯数字。 */
    private fun extractCodeCandidates(text: String): List<String> {
        val seg = SEG_CANDIDATE.find(text)
        if (seg != null) {
            val c = seg.value
            if (c.length in 5..12) return listOf(c)
        }
        return PURE_CANDIDATE.findAll(text).map { it.groupValues[1] }.toList()
    }

    data class PatternStats(
        val totalScans: Int,
        val attempts: Int,
        val misses: Int,
        val verified: Int,
        val perPattern: Map<String, Int>
    )

    data class PatternSuggestion(
        val tokenPattern: String,
        val label: String,
        val sampleCodes: List<String>,
        val count: Int,
        val confidence: Float,
        val proposedRegex: String
    )

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /** Record that the extractor matched a code using this pattern.
     *  This is NOT a correctness signal — just pattern usage tracking.
     *  H7: 计数器 read-modify-write 加 @Synchronized（与 M10/B13 同模式），防并发 getInt+putInt 丢计数。 */
    @Synchronized
    fun recordAttempt(context: Context, patternId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_TOTAL, prefs.getInt(KEY_TOTAL, 0) + 1)
            .putInt(KEY_ATTEMPTS, prefs.getInt(KEY_ATTEMPTS, 0) + 1)
            .putInt(KEY_PAT_PREFIX + patternId, prefs.getInt(KEY_PAT_PREFIX + patternId, 0) + 1)
            .apply()
        DailyStats.recordDay(context, isHit = true, isMiss = false)
    }

    /** Record that the extractor found nothing in the OCR output.
     *  仅轻量记录；autoApply（读文件+聚类+写规则）通过低频节流触发，避免每次 miss 都做重 IO。
     *  @param source B1 样本来源打标：share / sms / screen / manual / notify */
    fun recordMiss(context: Context, rawText: String, source: String = "unknown") {
        Log.d(TAG, "recordMiss: source=$source, 文本 ${rawText.length} 字符 → 记入未匹配样本池（自动学习 6h 节流触发）")
        synchronized(this) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(KEY_TOTAL, prefs.getInt(KEY_TOTAL, 0) + 1)
                .putInt(KEY_MISSES, prefs.getInt(KEY_MISSES, 0) + 1)
                .apply()
            DailyStats.recordDay(context, isHit = false, isMiss = true)
            appendUnmatched(context, rawText, source)
        }
        // 低频节流触发：放在 synchronized 块外，避免持锁期间做 IO
        autoApplyThrottled(context)
    }

    /** Record that a user confirmed an extracted code was correct.
     *  Call this from notification tap / manual verification UI. */
    @Synchronized
    fun recordVerified(context: Context, patternId: String) {
        Log.d(TAG, "recordVerified: patternId=$patternId")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_VERIFIED, prefs.getInt(KEY_VERIFIED, 0) + 1)
            .putInt(KEY_PAT_PREFIX + patternId + "_ok", prefs.getInt(KEY_PAT_PREFIX + patternId + "_ok", 0) + 1)
            .apply()
    }

    /** Record that a user marked an extracted code as incorrect. */
    @Synchronized
    fun recordCodeIncorrect(context: Context, patternId: String) {
        Log.d(TAG, "recordCodeIncorrect: patternId=$patternId")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_PAT_PREFIX + patternId + "_bad", prefs.getInt(KEY_PAT_PREFIX + patternId + "_bad", 0) + 1)
            .apply()
    }

    /** Record that a user confirmed an extracted source name (courier/restaurant) was correct. */
    @Synchronized
    fun recordSourceMatch(context: Context, sourceName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_PAT_PREFIX + "src_" + sourceName + "_ok", prefs.getInt(KEY_PAT_PREFIX + "src_" + sourceName + "_ok", 0) + 1)
            .apply()
    }

    /** Record that a user marked an extracted source name as incorrect. */
    @Synchronized
    fun recordSourceIncorrect(context: Context, sourceName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_PAT_PREFIX + "src_" + sourceName + "_bad", prefs.getInt(KEY_PAT_PREFIX + "src_" + sourceName + "_bad", 0) + 1)
            .apply()
    }

    fun getStats(context: Context): PatternStats {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val total = prefs.getInt(KEY_TOTAL, 0)
        val attempts = prefs.getInt(KEY_ATTEMPTS, 0)
        val misses = prefs.getInt(KEY_MISSES, 0)
        val verified = prefs.getInt(KEY_VERIFIED, 0)
        val per = mutableMapOf<String, Int>()
        for (key in prefs.all.keys) {
            // Only count raw pattern attempt counters, skip _ok/_bad/_verified/source sub-keys
            if (key.startsWith(KEY_PAT_PREFIX) &&
                !key.endsWith("_verified") && !key.endsWith("_ok") && !key.endsWith("_bad") &&
                !key.contains("_src_")
            ) {
                per[key.removePrefix(KEY_PAT_PREFIX)] = prefs.getInt(key, 0)
            }
        }
        return PatternStats(total, attempts, misses, verified, per)
    }

    fun getSuggestions(context: Context): List<PatternSuggestion> {
        val samples = loadUnmatched(context)
        if (samples.size < MIN_SUGGEST) return emptyList()

        val clustered = mutableMapOf<String, MutableList<String>>()
        for (s in samples) {
            val text = s.optString("text", "")
            // 排除上下文干扰必须在原始 text 上判断：候选码段只含数字/连字符、永不含中文单位字，
            // 在 cand 上匹配永远不命中（此前是死代码，导致 "300毫升"→300、"502室"→502 等噪声照样进学习池）。
            if (CANDIDATE_EXCLUDE_CTX.containsMatchIn(text)) continue
            // 先抠候选码段，再对每个码段 tokenize 聚类 —— 不再对整句脏文本 tokenize
            val candidates = extractCodeCandidates(text)
            for (cand in candidates) {
                val tok = tokenize(cand)
                if (tok.length >= 1) {
                    clustered.getOrPut(tok) { mutableListOf() }.add(cand)
                }
            }
        }

        val maxCount = clustered.values.maxOfOrNull { it.size } ?: return emptyList()
        return clustered
            .filter { it.value.size >= MIN_SUGGEST }
            .map { (tok, codes) ->
                PatternSuggestion(
                    tokenPattern = tok,
                    label = humanLabel(tok),
                    sampleCodes = codes.distinct().take(5),
                    count = codes.size,
                    confidence = (codes.size.toFloat() / maxCount).coerceAtMost(1f),
                    proposedRegex = tokenToRegex(tok)
                )
            }
            .sortedByDescending { it.count }
    }

    fun clearUnmatched(context: Context) {
        // H6: 与 appendUnmatched 共用同一把锁，避免并发清空/追加 writeText 相互覆盖（丢样本/留脏数据）
        synchronized(unmatchedLock) {
            val file = File(context.filesDir, "unmatched_samples.json")
            file.writeText("[]")
        }
    }

    // ---------------------------------------------------------------
    // A3: 可学习排除词（用户标记"不是取件码"的片段 → 学习池，之后识别剔除）
    // ---------------------------------------------------------------

    private const val KEY_EXCLUDES = "learned_excludes"
    private const val MAX_EXCLUDES = 100

    /** 把用户标记"不是取件码"的码值/片段加入可学习排除列表。 */
    @Synchronized
    fun addExclude(context: Context, token: String) {
        if (token.isBlank()) return
        val excludes = getLearnedExcludes(context).toMutableSet()
        excludes.add(token.trim().take(20))
        // 保底保留刚加入的词：Set 为插入序，超限时应丢弃最旧的，而非 take 前 100 把新词丢掉
        val kept = if (excludes.size > MAX_EXCLUDES) {
            excludes.drop(excludes.size - MAX_EXCLUDES).toSet()
        } else excludes
        val arr = JSONArray()
        for (e in kept) arr.put(e)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_EXCLUDES, arr.toString()).apply()
        excludeCache = kept   // 立即刷新进程内缓存（与落盘保持一致）
        excludeCacheAt = System.currentTimeMillis()
        // 仅 Debug 打印：token 就是**码值**（隐私数据），release 包里不允许进 logcat
        if (com.pickupcode.app.BuildConfig.DEBUG) {
            Log.d(TAG, "新增排除词「$token」（当前共 ${kept.size} 条，上限 $MAX_EXCLUDES）")
        }
    }

    /** 当前可学习的排除片段。 */
    fun getLearnedExcludes(context: Context): Set<String> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EXCLUDES, null)
            ?: return emptySet()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    // 进程内缓存，避免识别热循环里每次候选码都重复 read+parse JSON
    @Volatile private var excludeCache: Set<String>? = null
    @Volatile private var excludeCacheAt = 0L
    private const val EXCLUDE_CACHE_MS = 2000L

    private fun cachedLearnedExcludes(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        val cached = excludeCache
        if (cached != null && now - excludeCacheAt < EXCLUDE_CACHE_MS) return cached
        val fresh = getLearnedExcludes(context)
        excludeCache = fresh
        excludeCacheAt = now
        return fresh
    }

    /** 判断某码值是否命中已学习的排除项（供 CodeExtractor 识别时剔除）。
     * 用完整值匹配而非 contains 子串：排除 "42" 不应误杀 "9421"/"421" 这类合法码。 */
    fun isLearnedExcluded(code: String, context: Context?): Boolean {
        if (context == null) return false
        val excludes = cachedLearnedExcludes(context)
        if (excludes.isEmpty()) return false
        return excludes.any { ex -> code.equals(ex, ignoreCase = true) }
    }

    // ---------------------------------------------------------------
    // Tokenize: string -> character-class pattern
    // ---------------------------------------------------------------

    private fun tokenize(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() -> sb.append('d')
                c.isLetter() -> sb.append('L')
                c == '-' -> sb.append('-')
                c == '_' -> sb.append('_')
                c == ' ' -> sb.append(' ')
                c == '.' -> sb.append('.')
                else -> sb.append('X')
            }
            i++
        }
        // Collapse consecutive same tokens
        val collapsed = StringBuilder()
        var last = '\u0000'
        var lastRun = 1
        for (j in 0 until sb.length) {
            val t = sb[j]
            if (t == last) {
                lastRun++
            } else {
                if (last != '\u0000') {
                    collapsed.append(last)
                    if (lastRun > 1) collapsed.append(lastRun)
                }
                last = t
                lastRun = 1
            }
        }
        if (last != '\u0000') {
            collapsed.append(last)
            if (lastRun > 1) collapsed.append(lastRun)
        }
        return collapsed.toString()
    }

    // ---------------------------------------------------------------
    // Human-readable label for a token pattern
    // ---------------------------------------------------------------

    private fun humanLabel(tok: String): String {
        return when (tok) {
            "d6" -> "6-digit number"
            "d7" -> "7-digit number"
            "d8" -> "8-digit number"
            "d1-d1-d4" -> "rack-shelf-slot (A-B-CCCC)"
            "d1-d1-d5" -> "rack-shelf-slot (A-B-CCCCC)"
            "d2-d1-d4" -> "rack-shelf-slot (AA-B-CCCC)"
            "L1-d5" -> "letter-5digit (like D-06003)"
            "L1-d6" -> "letter-6digit"
            "L2-d5" -> "2letter-5digit"
            "d5" -> "5-digit code"
            "d4" -> "4-digit code"
            "d3" -> "3-digit code"
            "L1-d2-d3" -> "letter-digit-digit (A-1-234)"
            "L1-d1-d4" -> "letter-digit-4digit"
            "d-d-d" -> "digit-dash-dash (1-2-3)"
            "d-d-d4" -> "digit-dash-4digit (1-6-5020)"
            "d-d-d5" -> "digit-dash-5digit"
            "Ld-d-d4" -> "letter-digit-dash-4digit (A8-3-3315)"
            else -> tok
        }
    }

    // ---------------------------------------------------------------
    // Convert token pattern -> candidate regex
    // ---------------------------------------------------------------

    /**
     * 自动生成正则的边界。用显式环视而非 \b——Android(ICU) 的 \b 把中文当词字符，
     * 码值紧贴中文时（如 "749019复制"）边界失效导致漏抓；与 CodeValidator/CodeExtractor 同一约定。
     */
    private const val BOUNDARY_LEFT = "(?<![\\dA-Za-z])"
    private const val BOUNDARY_RIGHT = "(?![\\dA-Za-z])"

    private fun tokenToRegex(tok: String): String {
        val parts = parseRuns(tok)
        val sb = StringBuilder(BOUNDARY_LEFT)
        for ((cls, count) in parts) {
            sb.append(when (cls) {
                'd' -> if (count == 1) "\\d" else "\\d{$count}"
                'L' -> if (count == 1) "[A-Za-z]" else "[A-Za-z]{$count}"
                '-' -> "-"
                '_' -> "_"
                ' ' -> "\\s*"
                '.' -> "\\."
                else -> "."
            })
        }
        sb.append(BOUNDARY_RIGHT)
        return sb.toString()
    }

    /**
     * 旧版本（<1.0.10）生成的学习规则用 \b 边界，在 Android 上对中文邻接的码失效。
     * 读取时把首尾的 \b 一次性改写为环视边界；只动首尾，规则中间的 \b 保持原样。
     */
    internal fun migrateBoundary(regex: String): String =
        regex
            .replace(Regex("^\\\\b")) { BOUNDARY_LEFT }
            .replace(Regex("\\\\b$")) { BOUNDARY_RIGHT }

    private data class Run(val cls: Char, val count: Int)

    private fun parseRuns(tok: String): List<Run> {
        val runs = mutableListOf<Run>()
        var i = 0
        while (i < tok.length) {
            val cls = tok[i]
            i++
            var cnt = 0
            while (i < tok.length && tok[i].isDigit()) {
                cnt = cnt * 10 + (tok[i] - '0')
                i++
            }
            runs.add(Run(cls, if (cnt > 0) cnt else 1))
        }
        return runs
    }

    // ---------------------------------------------------------------
    // Unmatched sample storage (JSON file, max 100 entries)
    // ---------------------------------------------------------------

    // 对 JSON 样本文件的写操作统一加锁，避免并发 read-modify-write 竞态导致丢失样本
    private val unmatchedLock = Any()
private val verifiedAddrLock = Any()

    private fun appendUnmatched(context: Context, rawText: String, source: String = "unknown") {
        if (rawText.isBlank()) return
        synchronized(unmatchedLock) {
            val file = File(context.filesDir, "unmatched_samples.json")
            val arr = if (file.exists()) {
                try { JSONArray(file.readText()) } catch (_: Exception) { JSONArray() }
            } else JSONArray()

            // Keep only recent + relevant text
            val snippet = rawText.take(300)

            // 样本去重：同 text 已存在则只刷新 ts，避免同一噪声反复扫描重复入池、
            // 凭空把簇计数顶到 MIN_SUGGEST 阈值（击穿"3 次独立样本"假设）。
            val existingIdx = (0 until arr.length()).firstOrNull { i ->
                arr.optJSONObject(i)?.optString("text") == snippet
            }
            if (existingIdx != null) {
                arr.getJSONObject(existingIdx).put("ts", System.currentTimeMillis() / 1000)
            } else {
                arr.put(JSONObject().apply {
                    put("text", snippet)
                    put("src", source)          // B1: 样本来源打标
                    put("ts", System.currentTimeMillis() / 1000)
                })
            }

            // Trim to max
            while (arr.length() > MAX_UNMATCHED) arr.remove(0)
            file.writeText(arr.toString())
        }
    }

    private fun loadUnmatched(context: Context): List<JSONObject> {
        val file = File(context.filesDir, "unmatched_samples.json")
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (_: Exception) { emptyList() }
    }

    // ---------------------------------------------------------------
    // Address verification tracking
    // ---------------------------------------------------------------

    @Synchronized
    fun recordAddressVerified(context: Context, address: String, confidence: Float) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val verified = prefs.getInt("addr_verified", 0)
        val total = prefs.getInt("addr_total", 0)
        prefs.edit()
            .putInt("addr_verified", verified + 1)
            .putInt("addr_total", total + 1)
            .apply()

        // M9: verified_addresses.json 写操作加锁（与 unmatched_samples.json 一致），避免并发 read-modify-write 丢样本
        synchronized(verifiedAddrLock) {
            val file = File(context.filesDir, "verified_addresses.json")
            val arr = if (file.exists()) {
                try { JSONArray(file.readText()) } catch (_: Exception) { JSONArray() }
            } else JSONArray()
            arr.put(JSONObject().apply {
                put("address", address)
                put("confidence", confidence.toDouble())
                put("ts", System.currentTimeMillis() / 1000)
            })
            while (arr.length() > 50) arr.remove(0)
            file.writeText(arr.toString())
        }
    }

    fun getAddressStats(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt("addr_verified", 0) to prefs.getInt("addr_total", 0)
    }

    /** Record that a user marked an extracted address as incorrect. */
    @Synchronized
    fun recordAddressIncorrect(context: Context, address: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val total = prefs.getInt("addr_total", 0)
        prefs.edit()
            .putInt("addr_total", total + 1)
            .putInt("addr_incorrect", prefs.getInt("addr_incorrect", 0) + 1)
            .apply()
    }

    // ---------------------------------------------------------------
    // Per-item confirmation state persistence (by history ID)
    // ---------------------------------------------------------------

    fun isCodeConfirmed(ctx: Context, id: Long) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ci_${id}_code", false)
    fun setCodeConfirmed(ctx: Context, id: Long, v: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ci_${id}_code", v).apply()
    fun isSourceConfirmed(ctx: Context, id: Long) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ci_${id}_src", false)
    fun setSourceConfirmed(ctx: Context, id: Long, v: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ci_${id}_src", v).apply()
    fun isAddrConfirmed(ctx: Context, id: Long) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ci_${id}_addr", false)
    fun setAddrConfirmed(ctx: Context, id: Long, v: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ci_${id}_addr", v).apply()
    fun isCodeIncorrect(ctx: Context, id: Long) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ci_${id}_code_bad", false)
    fun setCodeIncorrect(ctx: Context, id: Long, v: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ci_${id}_code_bad", v).apply()
    fun isSourceIncorrect(ctx: Context, id: Long) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ci_${id}_src_bad", false)
    fun setSourceIncorrect(ctx: Context, id: Long, v: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ci_${id}_src_bad", v).apply()
    fun isAddrIncorrect(ctx: Context, id: Long) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ci_${id}_addr_bad", false)
    fun setAddrIncorrect(ctx: Context, id: Long, v: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ci_${id}_addr_bad", v).apply()

    // ---------------------------------------------------------------
    // Auto-apply: check suggestions and persist high-confidence patterns
    // ---------------------------------------------------------------

    data class LearnedRule(
        val regex: String,
        val type: String,       // "pickup_parcel" / "pickup_food"
        val label: String,
        val count: Int,
        val enabled: Boolean = true,
        val confidence: Float = 0.5f,
        val sampleCount: Int = 0,
        val lastUsedAt: Long = 0L,
        val decayed: Boolean = false,  // B3: 长期未命中自动降级为「可选」而非强制应用
        val badCount: Int = 0,         // 用户标记不正确的次数，≥3 时自动停用
        // 规则来源：自动学习 / 用户手动添加。
        // 两者**共用同一份存储与同一条识别管线**（用户要求），这里只用于在 UI 上区分标注，
        // 不影响识别行为——手动加的规则和学来的规则在 CodeExtractor 里一视同仁。
        val source: String = SOURCE_LEARNED
    )

    private const val KEY_LEARNED = "learned_rules"
    private const val KEY_LAST_AUTOAPPLY = "last_autoapply"
    // learned_rules 的 read-modify-write 统一锁：setRuleEnabled/deleteRule/touchRule/markLearnedRuleBad/autoApply
    // 都做 get→改→save，并发下若不共用同一把锁会丢更新（如 touchRule 用旧快照覆盖刚写入的 badCount）。
    private val learnedRulesLock = Any()
    /** B3: 多少毫秒未使用视为"衰减"，自动降级为可选规则（默认 21 天）。 */
    private const val DECAY_MS = 21L * 24 * 60 * 60 * 1000
    private const val AUTO_APPLY_THROTTLE_MS = 6L * 60 * 60 * 1000 // 6h
    private const val TOUCH_THROTTLE_MS = 60L * 1000 // B3 touch 节流：1 分钟内不重复全量写盘

    /** A1: 停用/启用某条已学规则。 */
    fun setRuleEnabled(context: Context, regex: String, enabled: Boolean) {
        synchronized(learnedRulesLock) {
            val rules = getLearnedPatterns(context).map {
                if (it.regex == regex) it.copy(enabled = enabled) else it
            }
            saveLearnedPatterns(context, rules)
        }
    }

    /** A1: 删除某条已学规则。 */
    fun deleteRule(context: Context, regex: String) {
        synchronized(learnedRulesLock) {
            saveLearnedPatterns(context, getLearnedPatterns(context).filterNot { it.regex == regex })
        }
    }

    /** B3: 一条规则被识别命中时调用，更新 lastUsedAt 并解除衰减降级。
     *  节流：距上次 touch 该规则 < 阈值则跳过，避免识别主循环每次命中都全量重写 learned_rules。 */
    fun touchRule(context: Context, regex: String) {
        synchronized(learnedRulesLock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Low-2: 用规则自身字符串作节流 key（regex.hashCode() 有碰撞，会让不同规则互相错位节流）
            val stampKey = "touch_" + regex
            val now = System.currentTimeMillis()
            val last = prefs.getLong(stampKey, 0L)
            if (now - last < TOUCH_THROTTLE_MS) return
            prefs.edit().putLong(stampKey, now).apply()
            val rules = getLearnedPatterns(context).map {
                if (it.regex == regex) it.copy(lastUsedAt = now, decayed = false) else it
            }
            saveLearnedPatterns(context, rules)
        }
    }

    private fun saveLearnedPatterns(context: Context, rules: List<LearnedRule>) {
        val arr = JSONArray()
        val now = System.currentTimeMillis()
        for (r in rules) {
            // B3: 衰减判断——已启用（非用户手动停用）且超期未用 → 降级为可选
            val decayed = r.enabled && r.decayed || (r.enabled && r.lastUsedAt > 0 && now - r.lastUsedAt > DECAY_MS && r.count <= 3)
            arr.put(JSONObject().apply {
                put("regex", r.regex)
                put("type", r.type)
                put("label", r.label)
                put("count", r.count)
                put("enabled", r.enabled)
                put("confidence", r.confidence.toDouble())
                put("sampleCount", r.sampleCount)
                // Low-3: 保持原有 lastUsedAt（为 0 则写 0），不要把从未使用过的旧规则写成 now
                put("lastUsedAt", r.lastUsedAt)
                put("decayed", decayed)
                put("badCount", r.badCount)
                put("source", r.source)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LEARNED, arr.toString()).apply()
        // 写入即失效热路径缓存，保证识别立即看到新规则/衰减状态
        rulesCache = null
    }

    /** Check suggestions and auto-apply patterns with count ≥ minCount and confidence ≥ minConf. */
    fun autoApply(context: Context, minCount: Int = MIN_SUGGEST, minConfidence: Float = 0.5f): List<LearnedRule> {
        val suggestions = getSuggestions(context)
        // 锁定 read-modify-write：existing 读取→追加→落盘→清样本 必须原子，
        // 否则与 touchRule/markLearnedRuleBad 交错会丢更新（旧快照覆盖新写）。
        return synchronized(learnedRulesLock) {
            val existing = getLearnedPatterns(context).toMutableList()
            val existingRegexes = existing.map { it.regex }.toSet()

            val newRules = mutableListOf<LearnedRule>()
            for (s in suggestions) {
                if (s.count < minCount || s.confidence < minConfidence) continue
                if (s.proposedRegex in existingRegexes) continue

                // 加固：拒绝过度泛化的 token —— 含 'X'(任意字符) 的 token 会生成匹配任何文本的规则，
                // 极易误报（如 X-d4 会匹配 "A-1234" 也会匹配 "啊-1234"）。只采纳由明确字符类
                // （数字 d / 字母 L / 连字符 / 下划线 / 点 / 空格）构成的模式。
                if (s.tokenPattern.any { it != 'd' && it != 'L' && it != '-' && it != '_' && it != ' ' && it != '.' && !it.isDigit() }) {
                    continue
                }

                // Guess type: letter+digit combos are usually parcel codes
                val type = if (s.label.contains("letter") || s.tokenPattern.any { it == 'L' } || s.tokenPattern.contains('-'))
                    "pickup_parcel" else "pickup_food"

                val rule = LearnedRule(s.proposedRegex, type, s.label, s.count,
                    confidence = s.confidence, sampleCount = s.count, lastUsedAt = System.currentTimeMillis())
                newRules.add(rule)
                existing.add(rule)
            }

            if (newRules.isNotEmpty()) {
                saveLearnedPatterns(context, existing)
                Log.d(TAG, "自动学习新增 ${newRules.size} 条规则: " +
                    newRules.joinToString { "${it.label}=${it.regex}[${it.type}] conf=${it.confidence} count=${it.count}" })

                // Clear unmatched samples after successful learning
                clearUnmatched(context)
            } else {
                Log.d(TAG, "自动学习运行：无满足条件的新规则（样本<3 或置信度<0.5）")
            }
            newRules
        }
    }

    /** 节流版 autoApply：距上次自动学习不足阈值则跳过，避免高频 IO（读文件+聚类+写规则）。 */
    private fun autoApplyThrottled(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_AUTOAPPLY, 0)
        if (now - last < AUTO_APPLY_THROTTLE_MS) return
        prefs.edit().putLong(KEY_LAST_AUTOAPPLY, now).apply()
        autoApply(context)
    }

    // 识别热路径缓存：CodeExtractor 每次识别都会读全部已学规则，避免每次读盘 + 解析 JSON。
    // 任何写入都经 saveLearnedPatterns 失效缓存。
    @Volatile private var rulesCache: List<LearnedRule>? = null
    @Volatile private var rulesCacheAt = 0L
    private const val RULES_CACHE_MS = 2000L

    /** 识别热路径用：带 2s TTL 的已学规则缓存。UI/统计请用 [getLearnedPatterns]（总是最新）。 */
    fun cachedLearnedPatterns(context: Context): List<LearnedRule> {
        val now = System.currentTimeMillis()
        val cached = rulesCache
        if (cached != null && now - rulesCacheAt < RULES_CACHE_MS) return cached
        val fresh = getLearnedPatterns(context)
        rulesCache = fresh
        rulesCacheAt = now
        return fresh
    }

    fun getLearnedPatterns(context: Context): List<LearnedRule> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LEARNED, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                LearnedRule(
                    migrateBoundary(obj.getString("regex")),
                    obj.getString("type"),
                    obj.getString("label"),
                    obj.optInt("count", 0),
                    enabled = obj.optBoolean("enabled", true),
                    confidence = obj.optDouble("confidence", 0.5).toFloat(),
                    sampleCount = obj.optInt("sampleCount", 0),
                    lastUsedAt = obj.optLong("lastUsedAt", 0L),
                    decayed = obj.optBoolean("decayed", false),
                    badCount = obj.optInt("badCount", 0),
                    source = obj.optString("source", SOURCE_LEARNED)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** 用户标记某个码值不正确时，给匹配到该码的已学规则加一次 badCount。
     *  badCount ≥ 3 的规则会在下次加载时被 CodeExtractor 跳过（自动停用）。 */
    fun markLearnedRuleBad(context: Context, code: String) {
        if (code.isBlank()) return
        synchronized(learnedRulesLock) {
            val rules = getLearnedPatterns(context)
            var changed = false
            val updated = rules.map { r ->
                if (r.enabled && r.badCount < 3) {
                    try {
                        if (Regex(r.regex).matches(code)) {
                            changed = true
                            val nb = r.copy(badCount = r.badCount + 1)
                            // 仅 Debug 打印：含码值，release 不允许进 logcat
                            if (com.pickupcode.app.BuildConfig.DEBUG) {
                                Log.d(TAG, "已学规则 ${r.regex} 因码「$code」被标记不正确，badCount=${nb.badCount}" +
                                    if (nb.badCount >= 3) " → 达到 3 次自动停用" else "")
                            }
                            nb
                        } else r
                    } catch (_: Exception) { r }
                } else r
            }
            if (changed) saveLearnedPatterns(context, updated)
        }
    }

    // ===============================================================
    // 规则管理：用户手动添加 / 编辑 + 内置正则覆盖 + 一键还原
    //
    // 设计（按用户要求）：**手动添加的规则和自动学习的规则不冲突、共用同一条管线** ——
    // 两者都存在下面的 KEY_LEARNED 里（只靠 source 字段区分来源做标注），
    // CodeExtractor 以完全相同的方式加载它们，不存在两套机制。
    //
    // 内置正则**不进**这份存储，而是用「覆盖」管理：只记「哪些被停用」「哪条被改成了什么」，
    // 原始定义永远留在代码里（CodeExtractor.BUILTIN_RULES）。
    // 这样「一键还原」＝丢掉覆盖 + 清空自定义规则，天然可回退，改坏了也丢不掉出厂状态。
    // ===============================================================

    /** 规则来源标记。 */
    const val SOURCE_LEARNED = "learned"
    const val SOURCE_USER = "user"

    /** 来源：用户在详情页确认/亲手改了码值 → 按它建出来的规则（强证据，1 条即成规）。 */
    const val SOURCE_VERIFIED = "verified"

    /**
     * 把中文输入法常见的全角符号归一化成半角。
     *
     * 为什么必须做：正则语法只有 ASCII，但中文输入法会把用户打的 `[` `]` `(` `)` 自动变成全角
     * `【】（）`（真机实测：输入 `ZQ[0-9]` 存进去是 `ZQ【9】`），用户看到的是"像对的"、存的是错的。
     * 识别管线里 OCR 文本本来就先经过 [CodeExtractor] 的半角归一化，所以这里不会误伤"想匹配全角字符"的场景。
     */
    internal fun normalizeRegexInput(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            sb.append(
                when (c) {
                    '【', '［' -> '['
                    '】', '］' -> ']'
                    '（' -> '('
                    '）' -> ')'
                    '｛' -> '{'
                    '｝' -> '}'
                    '＼' -> '\\'
                    '－' -> '-'
                    '．' -> '.'
                    '？' -> '?'
                    '＋' -> '+'
                    '＊' -> '*'
                    '｜' -> '|'
                    '＾' -> '^'
                    '＄' -> '$'
                    '　' -> ' '
                    else -> {
                        when (c.code) {
                            in 0xFF10..0xFF19 -> ('0' + (c.code - 0xFF10))   // ０-９
                            in 0xFF21..0xFF3A -> ('A' + (c.code - 0xFF21))   // Ａ-Ｚ
                            in 0xFF41..0xFF5A -> ('a' + (c.code - 0xFF41))   // ａ-ｚ
                            else -> c
                        }
                    }
                }
            )
        }
        return sb.toString()
    }

    /** 正则校验结果：ok=false 拒绝保存；warning 非空表示能保存但有风险，UI 需提示。 */
    data class RegexCheck(val ok: Boolean, val warning: String? = null, val error: String? = null)

    /** 会匹配几乎所有文本的正则 —— 放进去等于"什么都当取件码"，直接拒绝。 */
    private val OVER_BROAD_PATTERNS = listOf(
        ".*", ".+", ".", "\\s*", "\\w*", "\\W*", "\\d*", "\\S*", "(.*)", "(.+)", "[\\s\\S]*"
    )

    /**
     * 校验用户输入的正则。分三档：拒绝（语法错/空/过宽）、可保存但警告（能匹配空串、缺边界）、通过。
     * 注意：用户规则和自动学习规则一样，**整段匹配值**就是候选码值，所以正则应当只匹配码值本身。
     */
    fun validateRegex(raw: String): RegexCheck {
        val r = normalizeRegexInput(raw).trim()
        if (r.isEmpty()) return RegexCheck(false, error = "正则不能为空")
        if (r.length > 200) return RegexCheck(false, error = "正则过长（上限 200 字符）")
        try {
            Regex(r)
        } catch (e: Exception) {
            return RegexCheck(false, error = "正则语法错误：${e.message?.take(80) ?: "无法编译"}")
        }
        if (r in OVER_BROAD_PATTERNS) {
            return RegexCheck(false, error = "这个正则能匹配几乎所有文本，会让识别失效，已被拒绝")
        }
        val warn = when {
            runCatching { Regex(r).matches("") }.getOrDefault(false) ->
                "该正则可以匹配空字符串，可能产生空候选"
            // 与 CodeValidator/CodeExtractor 同一约定：\b 对中文邻接不可靠，应该用显式环视边界
            !r.contains("(?<") && !r.contains("(?!") ->
                "建议加上边界 (?<![\\dA-Za-z]) 与 (?![\\dA-Za-z])，避免从更长的数字/字母串里截出一段"
            else -> null
        }
        return RegexCheck(true, warning = warn)
    }

    /** 新增一条用户手动规则。失败时 [Result.exceptionOrNull] 带可展示的文案。 */
    @Synchronized
    fun addUserRule(context: Context, regex: String, type: String, label: String): Result<LearnedRule> {
        val check = validateRegex(regex)
        if (!check.ok) return Result.failure(IllegalArgumentException(check.error ?: "正则不合法"))
        val r = normalizeRegexInput(regex).trim()
        val rules = getLearnedPatterns(context)
        if (rules.any { it.regex == r }) return Result.failure(IllegalArgumentException("这条规则已经存在"))
        val rule = LearnedRule(
            regex = r,
            type = normalizeType(type),
            label = label.trim().ifBlank { "手动规则" }.take(40),
            count = 0,
            confidence = 1f,
            sampleCount = 0,
            lastUsedAt = System.currentTimeMillis(),
            source = SOURCE_USER
        )
        saveLearnedPatterns(context, rules + rule)
        return Result.success(rule)
    }

    /** 编辑一条已有规则（自动学习/手动添加都能改）。oldRegex 用于定位。 */
    @Synchronized
    fun updateRule(context: Context, oldRegex: String, newRegex: String, type: String, label: String): Result<LearnedRule> {
        val check = validateRegex(newRegex)
        if (!check.ok) return Result.failure(IllegalArgumentException(check.error ?: "正则不合法"))
        val r = normalizeRegexInput(newRegex).trim()
        val rules = getLearnedPatterns(context)
        if (rules.none { it.regex == oldRegex }) return Result.failure(IllegalArgumentException("找不到要修改的规则"))
        if (rules.any { it.regex == r && it.regex != oldRegex }) {
            return Result.failure(IllegalArgumentException("这条规则已经存在"))
        }
        var updated: LearnedRule? = null
        val next = rules.map {
            if (it.regex == oldRegex) {
                updated = it.copy(
                    regex = r,
                    type = normalizeType(type),
                    label = label.trim().ifBlank { it.label }.take(40),
                    // 改过之后不再算"衰减"，并清掉自动停用计数，让用户的手工修改立即生效
                    decayed = false,
                    badCount = 0
                )
                updated!!
            } else it
        }
        saveLearnedPatterns(context, next)
        return Result.success(updated!!)
    }

    /** 只允许三种已知类型，其余一律兜底为取件码。 */
    private fun normalizeType(type: String): String = when (type) {
        "pickup_food" -> "pickup_food"
        "coupon" -> "coupon"
        else -> "pickup_parcel"
    }

    // ---------------------------------------------------------------
    // 用户确认通道：比"同形状出现 3 次"强得多的正面证据
    //
    // 背景（2026-09-24）：自动学习只在**完全没抓到码**时才触发，且要求"同一形状在 ≥3 条
    // **不同文本**里出现"才敢成规——阈值定这么高，是因为"误学"的代价远大于"漏学"
    // （误学会把噪声永久当取件码并主动弹通知）。
    // 但有一类样本本来就带着强证据：**用户亲自确认或亲手改写的码值**。
    // 它不需要 3 次、不需要聚类，1 条即可成规 —— 这就是本段实现的东西。
    // ---------------------------------------------------------------

    /** 用户确认/修正一个码值时的学习判定结果（纯逻辑，无 Android 依赖，可直接单测）。 */
    internal sealed interface VerifiedDecision {
        /** 不建规则：形状已被覆盖（学了也用不上），或这个值不适合拿来学。 */
        object Skip : VerifiedDecision
        /** 已有自定义规则同形：续命即可（清衰减），不重复建规则。 */
        data class Refresh(val regex: String) : VerifiedDecision
        /** 形状未被覆盖且内置抓不到：直接建规则。 */
        data class Add(val regex: String, val token: String, val label: String) : VerifiedDecision
    }

    /**
     * 判定"用户确认的这个码值"该怎么学。
     *
     * @param existingRegexes 现有自定义规则的 regex 集合
     * @param builtinCovers   这条码值是否**已经能被内置评分正则抓到**
     *
     * 顺序有讲究：先看已有自定义规则（同形 → 续命），再看内置（已覆盖 → 不学），最后才建新规则。
     */
    internal fun decideVerified(code: String, existingRegexes: Set<String>, builtinCovers: Boolean): VerifiedDecision {
        val c = code.trim()
        if (c.length !in 2..20) return VerifiedDecision.Skip
        val tok = tokenize(c)
        if (tok.isBlank()) return VerifiedDecision.Skip
        // 过宽守卫：与 autoApply 同一套 —— 含 X（任意字符）的 token 会匹配任何文本，绝不能成规
        if (tok.any { it != 'd' && it != 'L' && it != '-' && it != '_' && it != ' ' && it != '.' && !it.isDigit() }) {
            return VerifiedDecision.Skip
        }
        val regex = tokenToRegex(tok)
        if (regex in existingRegexes) return VerifiedDecision.Refresh(regex)
        if (builtinCovers) return VerifiedDecision.Skip
        return VerifiedDecision.Add(regex, tok, humanLabel(tok))
    }

    /**
     * 用户确认（或修正）了一个**真实取件码** → 立刻按它建一条规则（若这种形状我们原本抓不到）。
     *
     * 三条守卫（避免制造垃圾规则）：
     *  1. 内置正则本来就能抓到这种形状 → 不学：学了也不会被用上，只会堆一条永远命不中的规则；
     *  2. 已有自定义规则同形 → 只续命（刷 `lastUsedAt`、清衰减），不重复建；
     *  3. 形状新且内置抓不到 → 立刻建规则，来源标 [SOURCE_VERIFIED]（规则页显示"已确认"）。
     *
     * @return 新建的规则；无需新建时返回 null。
     */
    @Synchronized
    fun learnFromConfirmedCode(context: Context, code: String, type: String): LearnedRule? {
        val existing = getLearnedPatterns(context)
        val decision = decideVerified(
            code = code,
            existingRegexes = existing.map { it.regex }.toSet(),
            builtinCovers = com.pickupcode.app.extractor.CodeExtractor.builtinCovers(code, context)
        )
        return when (decision) {
            VerifiedDecision.Skip -> null
            is VerifiedDecision.Refresh -> {
                saveLearnedPatterns(context, existing.map {
                    if (it.regex == decision.regex) {
                        it.copy(lastUsedAt = System.currentTimeMillis(), decayed = false)
                    } else it
                })
                null
            }
            is VerifiedDecision.Add -> {
                val rule = LearnedRule(
                    regex = decision.regex,
                    type = normalizeType(type),
                    label = decision.label,
                    count = 1,
                    confidence = 1f,
                    sampleCount = 1,
                    lastUsedAt = System.currentTimeMillis(),
                    source = SOURCE_VERIFIED
                )
                saveLearnedPatterns(context, existing + rule)
                if (com.pickupcode.app.BuildConfig.DEBUG) {
                    Log.d(TAG, "用户确认通道：新增规则 ${rule.regex}[${rule.type}]")
                }
                rule
            }
        }
    }

    // ---------------------------------------------------------------
    // 内置正则的覆盖（停用 / 改写 / 单条还原）
    // ---------------------------------------------------------------

    private const val KEY_BUILTIN_DISABLED = "builtin_disabled"
    private const val KEY_BUILTIN_REGEX = "builtin_regex"

    /** 内置规则的用户覆盖：停用的 id 集合 + 改写过正则的 id→正则。 */
    data class BuiltinOverrides(
        val disabled: Set<String> = emptySet(),
        val regexEdits: Map<String, String> = emptyMap()
    ) {
        fun isDisabled(id: String) = id in disabled
        fun regexFor(id: String) = regexEdits[id]
        val isEmpty: Boolean get() = disabled.isEmpty() && regexEdits.isEmpty()
    }

    // 内置覆盖的 JSON 编解码抽成纯函数：无 Android 依赖，可直接单测格式与容错
    internal fun encodeDisabled(disabled: Set<String>): String = JSONArray(disabled.toList()).toString()

    internal fun decodeDisabled(json: String?): Set<String> = try {
        if (json.isNullOrBlank()) emptySet() else {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }
    } catch (_: Exception) { emptySet() }

    internal fun encodeRegexEdits(edits: Map<String, String>): String =
        JSONObject(edits as Map<*, *>).toString()

    internal fun decodeRegexEdits(json: String?): Map<String, String> = try {
        if (json.isNullOrBlank()) emptyMap() else {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }
    } catch (_: Exception) { emptyMap() }

    @Synchronized
    fun getBuiltinOverrides(context: Context): BuiltinOverrides {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BuiltinOverrides(
            disabled = decodeDisabled(prefs.getString(KEY_BUILTIN_DISABLED, null)),
            regexEdits = decodeRegexEdits(prefs.getString(KEY_BUILTIN_REGEX, null))
        )
    }

    private fun saveBuiltinOverrides(context: Context, ov: BuiltinOverrides) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BUILTIN_DISABLED, encodeDisabled(ov.disabled))
            .putString(KEY_BUILTIN_REGEX, encodeRegexEdits(ov.regexEdits))
            .apply()
        builtinCache = null   // 写入即失效热路径缓存
    }

    /** 停用/启用某条内置正则。 */
    @Synchronized
    fun setBuiltinEnabled(context: Context, id: String, enabled: Boolean) {
        val ov = getBuiltinOverrides(context)
        val next = if (enabled) ov.disabled - id else ov.disabled + id
        saveBuiltinOverrides(context, ov.copy(disabled = next))
    }

    /** 改写某条内置正则；传 null 表示撤掉改写、恢复原始正则。 */
    @Synchronized
    fun setBuiltinRegex(context: Context, id: String, regex: String?) {
        val ov = getBuiltinOverrides(context)
        val next = ov.regexEdits.toMutableMap()
        if (regex.isNullOrBlank()) next.remove(id) else next[id] = normalizeRegexInput(regex).trim()
        saveBuiltinOverrides(context, ov.copy(regexEdits = next))
    }

    /** 单条内置正则还原（同时清掉停用与改写）。 */
    @Synchronized
    fun resetBuiltin(context: Context, id: String) {
        val ov = getBuiltinOverrides(context)
        saveBuiltinOverrides(context, BuiltinOverrides(ov.disabled - id, ov.regexEdits - id))
    }

    /**
     * 一键还原默认：丢掉**本页能改的一切** —— 内置正则的停用/改写 + 全部自定义规则（自动学习与手动添加）。
     *
     * 有意**不碰**：已学习排除词（learned_excludes）、预存地址、常用取件点、识别统计。
     * 那些不是这个页面能编辑的东西，把它们一起清掉会超出用户点这个按钮时的预期。
     * 返回 (清掉的自定义规则数, 清掉的内置覆盖数) 供 UI 提示。
     */
    @Synchronized
    fun restoreDefaults(context: Context): Pair<Int, Int> {
        val ruleCount = getLearnedPatterns(context).size
        val ov = getBuiltinOverrides(context)
        val overrideCount = ov.disabled.size + ov.regexEdits.size
        saveLearnedPatterns(context, emptyList())
        saveBuiltinOverrides(context, BuiltinOverrides())
        Log.d(TAG, "一键还原默认：清掉 $ruleCount 条自定义规则、$overrideCount 项内置正则覆盖")
        return ruleCount to overrideCount
    }

    /** 是否存在任何用户改动（UI 用来决定"还原默认"按钮要不要高亮）。 */
    fun hasUserChanges(context: Context): Boolean =
        getLearnedPatterns(context).isNotEmpty() || !getBuiltinOverrides(context).isEmpty

    // 内置覆盖同样在识别热路径上被读，加 2s TTL 缓存（与 rulesCache 同款做法）
    @Volatile private var builtinCache: BuiltinOverrides? = null
    @Volatile private var builtinCacheAt = 0L

    /** 识别热路径用：带 2s TTL 的内置覆盖缓存。 */
    fun cachedBuiltinOverrides(context: Context): BuiltinOverrides {
        val now = System.currentTimeMillis()
        val cached = builtinCache
        if (cached != null && now - builtinCacheAt < RULES_CACHE_MS) return cached
        val fresh = getBuiltinOverrides(context)
        builtinCache = fresh
        builtinCacheAt = now
        return fresh
    }
}