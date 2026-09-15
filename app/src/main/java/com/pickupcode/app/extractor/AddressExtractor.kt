package com.pickupcode.app.extractor

import com.pickupcode.app.BuildConfig
import com.pickupcode.app.ocr.OCREngine

/** 地址提取：从 OCR 行流中定位站点名/柜号/完整取件地址（自 CodeExtractor 拆出，R1）。
 *  品牌知识（【】品牌、餐饮关键词）来自 [BrandResolver]。 */
object AddressExtractor {

    internal enum class StationType { LOCKER, PICKUP_POINT, UNKNOWN }

    internal data class PickupLocation(
        val stationName: String,
        val stationType: StationType,
        val cabinetNumber: String?,
        val fullAddress: String,
        val addrFrom: String = "none"  // 命中步骤来源（竞争仲裁用；12 个来源值）
    )

    // 地址指示符（isAddressLike 核心判断）：合并同类产品 extractAddress 的 30+ 地标词表，
    // 覆盖 店/铺/站/点/园/苑/广场/中心/公寓/写字楼 等常见地址结尾，减少 S10 兜底漏抓真实地址。
    // 注意保留"元"仅在"单元"语境（见 isAddressLike 的 bareYuanOnly 处理）。
    private val ADDR_PIPE_FULL = Regex("[路街巷弄号栋幢单元柜室楼区县镇乡村庄店铺站点园苑院屋所广场中心商厦厦居宅房寓庭墅阁舍江河港湾门口岸桥山岭岗场]")
    private val ADDR_LANDMARK = Regex("(菜鸟|驿站|快递柜|丰巢|超市|诊所|对面|门口|小区|大厦|医院|银行|学校|商场|广场|中心|公寓|写字楼|工业园|科技园|物流园|产业园|代收点|便利店|商行|门面|花园|家园|宿舍|中学|孵化园)")
    private val ADDR_AFTER_TO = Regex("到(.+?)(领取|取件|门店|取运单尾号|取运单|取您|取你的|取貨|取货|取走|取你)")
    private val ADDR_LABEL = Regex("地址[:：]\\s*(.+)")
    private val ADDR_PLACED = Regex("(?:已放至|已暂存至|已放入|送达)\\s*([^，,。.\\n]{4,80})")
    private val CABINET_NUM = Regex("(\\d+)号柜")
    private val PAREN_ADDR = Regex("\\uFF08([^\\uFF09]*[路街段柜])\\uFF09")
    // 品牌前导 2~10 汉字（S-Coupon 循环内逐行匹配，提为常量避免重编译）
    private val REG_CJK_BRAND_LEAD = Regex("[\\u4e00-\\u9fff]{2,10}")
    // 「地址:」标签标记（S0b 与 extractAddressForCode 共用）
    private val REG_ADDR_LABEL_MARK = Regex("地址[:：]")

    /** 快递运单号行（品牌+快递后缀+冒号/空格+长数字串），如 中通快递:79130792811022——非地址。 */
    private val COURIER_TRACKING_LINE = Regex("(?:快递|速递|物流|速运|驿站|智能柜)[:：]?\\s*\\d{9,}")

    // ---------------------------------------------------------------
    // 长度/几何容差常量（各步骤共用，按语义分开命名）
    // ---------------------------------------------------------------

    /** 地址/站名长度上限（take(80) 截断 + isAddressLike 上限，20+ 处共用）。 */
    private const val MAX_ADDRESS_LEN = 80

    /** 地址核心长度下限（候选少于 4 字符不算地址/标签值）。 */
    private const val MIN_ADDRESS_CORE_LEN = 4

    /** S0c 列布局：标签与值中心 Y 容差。 */
    private const val COLUMN_BAND_Y_TOL = 60

    /** S0c 列布局：下一行中心 Y 继续收集的间隔上限。 */
    private const val COLUMN_CONTINUE_Y_GAP = 120

    /** S0c 列布局：值与标签左边缘 X 对齐容差。 */
    private const val COLUMN_SAME_X_TOL = 40

    /** S0b/S8 标签邻近行：Y 距离上限。 */
    private const val LABEL_NEARBY_Y_GAP = 300f

    /** S8 通知卡片：码行 ±3 行窗口（含 S6/S8 跨行拼接 1~3 行）。 */
    private const val CARD_LINE_WINDOW = 3

    /** S8 同卡片行距上限（超 400px 视为新卡片）。 */
    private const val CARD_WINDOW_Y_GAP = 400f

    /** S6/S8 跨行拼接：行间 Y 间隔超 600 视为断卡。 */
    private const val ADDR_LINE_GAP_MAX = 600

    /** extractCabinetNumber 循环匹配用（提为常量避免每次迭代重编译）。 */
    private val CABINET_NUM_COMPLEX = Regex("(\\d{1,3}号(?:副|主)?柜|云柜\\d{1,3}号|\\d{1,3}号格口|\\d{1,3}号丰巢柜)")

    /** 门牌号+柜号存在性检测用（提为常量避免每次调用重编译）。 */
    private val ANY_CABINET_NUM = Regex("\\d+号柜")

    /** X超市/X便利店/X商行 放宽匹配（extractStationName 每次调用需匹配，提为常量）。 */
    private val FOOD_STORE_PATTERN = Regex("(?<![元券])[\\u4e00-\\u9fffA-Za-z0-9]{1,8}?(超市|便利店|商行)")

    // 营销横幅/优惠标签词——出现这些词的片段不是店名/站名（如【新店福利】、满减、优惠券）
    private val PROMO_LABEL_WORDS = listOf("福利", "优惠", "满减", "红包", "立减", "折扣", "特惠", "会员")
    private val PING_NOISE_TRAIL = Regex("凭\\s*[A-Za-z0-9\\-]+\\s*$")

    private val STATION_TYPE_MAP = mapOf(
        "丰巢" to StationType.LOCKER, "欢猫智柜" to StationType.LOCKER,
        "快递柜" to StationType.LOCKER,
        "菜鸟驿站" to StationType.PICKUP_POINT, "妈妈驿站" to StationType.PICKUP_POINT,
        "兔喜" to StationType.PICKUP_POINT, "免喜" to StationType.PICKUP_POINT,
        "快递超市" to StationType.PICKUP_POINT, "韵达超市" to StationType.PICKUP_POINT,
        "代收点" to StationType.PICKUP_POINT
    )

    // ---------------------------------------------------------------
    // Address extraction (structured)
    // ---------------------------------------------------------------


    /** 显式地址标签（S0 找地址写入点、S8 找邻近补站名共用）。 */
    private val EXPLICIT_LABELS = listOf("取件点位置", "取件地址", "收货地址", "代收点地址", "取件点")

    /** 续行阻断词（S2/S8/S10 共用：出现即停止向下拼接，防 UI 按钮文案混入地址）。 */
    private val CONTINUE_BLOCK_WORDS = listOf("展开", "收起", "复制", "拨打", "导航", "昨天", "今天", "消息", "通知")

    /** extractLocation 各步骤间共享的可变状态（拆分后替代原 4 个并列局部变量）。 */
    private class LocationState {
        var stationName = ""
        var fullAddress = ""
        var cabinet: String? = null
        var addrFrom = "none"   // 诊断标签（CodeExtrDiag 日志用，12 个来源值原样保留）
    }

    internal fun extractLocation(lines: List<OCREngine.TextLine>, allText: String): PickupLocation {
        val st = LocationState()

        stepCoupon(lines, allText, st)              // S-Coupon: 券码门店（内部守卫）
        stepBracketBrand(allText, st)               // S1: 【】括号站名（无守卫）
        stepExplicitLabel(lines, st)                // S0: 显式标签（内层守卫）
        if (st.fullAddress.isEmpty()) stepAddrLabel(lines, st)       // S0b
        if (st.fullAddress.isEmpty()) stepColumnLayout(lines, st)    // S0c
        if (st.fullAddress.isEmpty()) stepPipeSeparated(lines, st)   // S2
        if (st.fullAddress.isEmpty()) stepPlacedPhrase(allText, st)  // S5
        if (st.fullAddress.isEmpty()) stepAfterToPhrase(lines, st)   // S6/S6b
        stepCabinetLine(lines, st)                  // S7: 号柜行（无守卫，cabinet 无条件更新）
        stepNearbyPrefix(lines, st)                 // S8: 前缀邻行（无守卫）
        if (st.fullAddress.isEmpty()) stepParenthesized(lines, st)   // S9
        if (st.fullAddress.isEmpty()) stepFallback(lines, st)        // S10
        postProcessLocker(allText, st)              // 收尾：柜名合并/站名修正
        return buildLocation(lines, allText, st)
    }

    /** S-Coupon: 券码/到店券门店识别（最高优先级；内部含券码上下文守卫）。 */
    private fun stepCoupon(lines: List<OCREngine.TextLine>, allText: String, st: LocationState) {
    // S-Coupon: 券码/到店券的门店识别（最高优先级）
    // 场景：外卖/到店券（德克士、蜜雪冰城等）截图，用户要的是"适用门店"（如 蜜雪冰城(老十字街店)），
    // 而非配送地址/周边地址。信号：待使用/用券/到店取/适用门店/立即用券/到店使用/券号 等券码上下文。
    val couponContext = listOf(
        "券号", "用券", "到店取", "到店使用", "待使用", "适用门店", "立即用券",
        "再次使用", "已使用", "兑换", "代金券", "优惠券", "满减券"
    )
    val isCouponContext = couponContext.any { allText.contains(it) }
    if (isCouponContext && st.fullAddress.isEmpty()) {
        // 优先扫描逐行，找"品牌名(店名)"格式（蜜雪冰城(老十字街店) / 德克士(郸械万果园店)）
        // 注意：括号字符类易触发 ICU 正则"incorrectly nested parentheses"，
        // 故不用单条大正则，改用简单匹配 + 字符串定位，稳妥且兼容。
        var couponAddr = ""
        var couponStation = ""
        for (line in lines) {
            val t = line.text.trim()
            // 用全角/半角开括号定位门店串：形如 品牌(店名) 或 品牌（店名）
            val openIdx = t.indexOfAny(charArrayOf('(', '（'))
            if (openIdx < 1) continue
            val afterOpen = t.substring(openIdx + 1)
            // 闭括号位置（全角/半角）
            val closeP = afterOpen.indexOf(')'); val closeF = afterOpen.indexOf('）')
            val closeIdx = when {
                closeP >= 0 && closeF >= 0 -> minOf(closeP, closeF)
                closeP >= 0 -> closeP
                closeF >= 0 -> closeF
                else -> -1
            }
            if (closeIdx <= 0) continue
            val brandPart = t.substring(0, openIdx).trim()
            val paren = afterOpen.substring(0, closeIdx).trim()
            // 品牌前导需为 2~10 个汉字；括号名需以"店"结尾且不含数字（排除快递员电话括号）
            if (!brandPart.matches(REG_CJK_BRAND_LEAD)) continue
            if (!paren.endsWith("店") || paren.any { it.isDigit() }) continue
            // 完整门店串 = t 中从行首到闭括号的整段（基于原始行重建，避免 trimmed paren 导致丢字）
            val full = t.substring(0, openIdx) + t[openIdx] + afterOpen.substring(0, closeIdx + 1)
            // 行内门店信号：命中（已通过品牌前导校验的前提下）再认。
            // 注意：品牌前导校验已足够窄，此处要求额外的"到店/适用门店/营业中"这类券码信号，
            // 以提高精确度——避免把正文里任意 "X(某店)" 当门店（PRD：只认券码截图的门店）。
            val brandHits = BrandResolver.FOOD_BRAND_KEYWORDS.any { brandPart.contains(it, ignoreCase = true) }
            val storeSig =
                t.contains("营业中") || t.contains("适用门店") || t.contains("到店") ||
                    t.contains("用券") || t.contains("门店") || brandHits
            if (!storeSig) continue
            couponAddr = full
            couponStation = full
            break
        }
        if (couponAddr.isNotEmpty()) {
            st.fullAddress = couponAddr.take(MAX_ADDRESS_LEN)
            st.stationName = couponStation
            st.addrFrom = "SCoupon-store"
            // 若 isAddressLike 校验不通过（如门店串太短/不含地址特征），仍保留但降级以不改后续逻辑
        }
    }
    }

    /** S1: 【】括号站名（无守卫，纯填 stationName）。 */
    private fun stepBracketBrand(allText: String, st: LocationState) {
    // S1: 【】 bracket brand for station name
    // 优先取含站点/快递关键词的括号；跳过快递员姓名+电话的括号（如【刘趁义:19037835253】）
    // 注意：不设"取第一个括号"的兜底——否则快递员括号会误当站名，留空交给后面的分支补全
    val bracketMatches = BrandResolver.BRACKET_BRAND.findAll(allText).map { it.groupValues[1].trim() }.toList()
    val goodBracket = bracketMatches.firstOrNull { content ->
        // 跳过包含手机号/运单号等数字的括号
        if (content.any { it.isDigit() }) return@firstOrNull false
        // 跳过优惠/福利/券类营销横幅（如【新店福利】是"新店优惠"标签，不是店名/站名）
        if (PROMO_LABEL_WORDS.any { content.contains(it) }) return@firstOrNull false
        STATION_TYPE_MAP.keys.any { content.contains(it) } ||
            ADDR_PIPE_FULL.containsMatchIn(content) ||
            ADDR_LANDMARK.containsMatchIn(content) ||
            listOf("店", "超市", "智柜", "生活", "代收", "驿站").any { content.contains(it) }
    }
    if (goodBracket != null) st.stationName = stripBrackets(goodBracket)
    }

    /** S0: 显式标签（取件地址/收货地址…）——最可靠地址信号，内层守卫原样。 */
    private fun stepExplicitLabel(lines: List<OCREngine.TextLine>, st: LocationState) {
    // S0: 显式标签（取件地址/收货地址/代收点地址/取件点…）——最可靠的地址信号，最高优先级
    for (lineIdx in lines.indices) {
        val line = lines[lineIdx]
        for (p in EXPLICIT_LABELS) {
            val i = line.text.indexOf(p)
            if (i < 0) continue
            var a = line.text.substring(i + p.length).trimStart(':', '：', ' ')
            for (sep in listOf('|', '｜')) {
                val bar = a.indexOf(sep)
                if (bar >= 0) { if (st.stationName.isEmpty()) st.stationName = extractStationName(a.substring(0, bar).trim()); a = a.substring(bar + 1).trim() }
            }
            a = cleanAddress(a)
            // 标签后若空/太短（值可能在下一行），向下拼 1~2 行续行
            if (st.fullAddress.isEmpty() && !isAddressLike(a) && lineIdx + 1 < lines.size) {
                val cont = StringBuilder()
                for (j in lineIdx + 1 until minOf(lineIdx + CARD_LINE_WINDOW, lines.size)) {
                    val c = lines[j].text.trim()
                    if (c.isEmpty()) continue
                    if (c.first().isDigit() || c.startsWith("|")) break
                    cont.append(c)
                }
                val combined = cleanAddress(a + cont.toString())
                if (isAddressLike(combined)) a = combined
            }
            // 折叠地址补全：S0-label 抓到的地址可能是被 UI 折叠的短串（如"…育新北展开"或"【xx店:.."），
            // 而同屏另有更具体的完整街道地址行（快递正文，如 育新路北段爱玛电动车旁边）。
            // 判断标准：标签行所在卡片窗口（±CARD_LINE_WINDOW 行）内，存在比 a 更长、像地址、
            // 无折叠残留(未闭合括号/省略号/展开) 且含明确街道特征的行 → 就用它替换。
            // 限定窗口：避免多通知同屏时串台到其他通知的地址。
            val streetLike = listOf("路", "街", "巷", "弄", "道", "号店", "小区", "苑", "大厦", "超市", "驿站", "快柜", "智柜", "村", "庄")
            val adminLike = listOf("省", "市", "县", "区")
            // 折叠残留检测：a 以未闭合括号开头（如 【xx店，右括号被截断），或含 …/.. 省略号痕迹
            val uncleanA = a.startsWith("【") || a.startsWith("（") || a.startsWith("(") ||
                a.contains("..") || a.contains("…")
            val winLo = (lineIdx - CARD_LINE_WINDOW).coerceAtLeast(0)
            val winHi = (lineIdx + CARD_LINE_WINDOW).coerceAtMost(lines.lastIndex)
            val better = lines.slice(winLo..winHi)
                .map { it.text.trim() }
                .filter {
                    it.length in 6..60 &&
                        it.length > a.length &&
                        streetLike.any { s -> it.contains(s) } &&
                        adminLike.none { ad -> it.contains(ad) } &&
                        listOf("电话", "..", "拨打", "联系", "展开", "【", "（", "(").none { it2 -> it.contains(it2) } &&
                        isAddressLike(it)
                }
                .maxByOrNull { it.length }
            // 条件：a 有折叠残留，或（a 是地址但缺明确街道特征时，且能找到更长完整行）→ 替换
            if (better != null && better != a &&
                (uncleanA || streetLike.none { a.contains(it) })) {
                a = better
            }
            if (st.fullAddress.isEmpty() && isAddressLike(a)) {
                st.fullAddress = a.take(MAX_ADDRESS_LEN)
                st.addrFrom = "S0-label"
                if (st.stationName.isEmpty()) st.stationName = extractStationName(a)
            }
        }
    }
    }

    /** S0b: "地址:" 标签后跟地址（调用点守卫：地址为空才跑）。 */
    private fun stepAddrLabel(lines: List<OCREngine.TextLine>, st: LocationState) {
    // S0b: 地址: 后跟收货地址（如 收货地址:河南省周口市郸城县育新北…）
    // 逐行匹配标签值，避免 ADDR_LABEL 在整屏 allText 上贪婪匹配到无关行尾噪声
    if (st.fullAddress.isEmpty()) {
        val labelLine = lines.firstOrNull { it.text.trim().contains(REG_ADDR_LABEL_MARK) }
        if (labelLine != null) {
            val a0 = cleanAddress(ADDR_LABEL.find(labelLine.text)?.groupValues?.get(1).orEmpty())
            // ①同前缀更长地址行(完整地址与标签同屏出现时优先)
            var a = a0
            if (isAddressLike(a0) && a0.length >= MIN_ADDRESS_CORE_LEN) {
                val p4 = a0.substring(0, MIN_ADDRESS_CORE_LEN)
                val byPrefix = lines
                    .map { it.text.trim() }
                    .filter { it.length > a0.length && it.startsWith(p4) && isAddressLike(it) }
                    .maxByOrNull { it.length }
                if (byPrefix != null) a = byPrefix
            }
            // ②标签值退化(短/OCR读重如 地址:育新路育新路育)时，取「标签行下方邻近」的干净完整地址，
            // 按 labelLine 的 y 定位同一通知卡片区域，避免错抓同屏其它驿站(不同通知)的地址。
            // 用彼此重复兜底：标签行下方的更长地址行优先于退化标签值。
            val labY = labelLine.boundingBox?.let { it.top.toFloat() } ?: 0f
            val nearbyBest = lines
                .filter { tl ->
                    val y = tl.boundingBox?.let { it.top.toFloat() } ?: 0f
                    y > labY && y - labY < LABEL_NEARBY_Y_GAP && tl.text.trim().length > a0.length
                }
                .map { it.text.trim() }
                .filter { it.length >= MIN_ADDRESS_CORE_LEN && isAddressLike(it) }
                .maxByOrNull { it.length }
            if (nearbyBest != null) a = nearbyBest
            if (isAddressLike(a)) {
                st.fullAddress = a.take(MAX_ADDRESS_LEN)
                st.addrFrom = "S0b-addrLabel"
                if (st.stationName.isEmpty()) st.stationName = extractStationName(a)
            }
        }
    }
    }

    /** S0c: 两列键值布局（5G 消息卡片，centerY 对齐）。 */
    private fun stepColumnLayout(lines: List<OCREngine.TextLine>, st: LocationState) {
    // S0c: 两列键值布局（5G消息卡片）——标签在左列，值在右列同一横带，地址续行在下方同列
    // 例：LINE[取件地址 y=942 x=107] + LINE[育新路北段店 y=942 x=380] + LINE[育新路…爱玛电动车 y=1032 x=380]
    if (st.fullAddress.isEmpty()) {
        val labelKw = listOf("取件地址", "取件点位置", "代收点地址", "取件点", "地址")
        for (labLine in lines) {
            val labBox = labLine.boundingBox ?: continue
            if (!labelKw.any { labLine.text.contains(it) }) continue
            // 找同一横带的右侧值行（y 接近 + 值在标签右边）
            val valueLine = lines.firstOrNull { v ->
                val vb = v.boundingBox ?: return@firstOrNull false
                vb !== labBox &&
                    kotlin.math.abs(vb.centerY() - labBox.centerY()) < COLUMN_BAND_Y_TOL &&
                    vb.left > labBox.right
            } ?: continue
            // 拼接值行 + 下方同列（地址续行）
            val valueTxt = valueLine.text.trim()
            val valueBox = valueLine.boundingBox ?: continue
            val sb = StringBuilder()
            var curY = valueBox.bottom
            for (contLine in lines) {
                val cb = contLine.boundingBox ?: continue
                if (cb.centerY() > curY && cb.centerY() - curY < COLUMN_CONTINUE_Y_GAP &&
                    kotlin.math.abs(cb.left - valueBox.left) < COLUMN_SAME_X_TOL) {
                    sb.append(contLine.text.trim())
                    curY = cb.bottom
                }
            }
            val contTxt = sb.toString()
            // 若续行已包含取值行的核心地址（前4字），直接用更完整的续行，避免重复拼接
            // （例：取值行=育新路北段店，续行=育新路育新路育新路北段爱玛电动车旁边 → 只用续行）
            val core = if (valueTxt.length >= MIN_ADDRESS_CORE_LEN) valueTxt.substring(0, MIN_ADDRESS_CORE_LEN) else valueTxt
            val usesValue = valueTxt.length < MIN_ADDRESS_CORE_LEN || !contTxt.contains(core)
            val a = cleanAddress(if (usesValue) (valueTxt + contTxt) else contTxt)
            if (st.fullAddress.isEmpty() && a.isNotEmpty() && isAddressLike(a)) {
                st.fullAddress = a.take(MAX_ADDRESS_LEN)
                st.addrFrom = "S0c-column"
                if (st.stationName.isEmpty()) st.stationName = extractStationName(a)
            }
        }
    }
    }

    /** S2: "门店 | 地址" 管道分隔行。 */
    private fun stepPipeSeparated(lines: List<OCREngine.TextLine>, st: LocationState) {
    // S2: pipe-separated "shop | address"
    if (st.fullAddress.isEmpty()) {
        for (line in lines) {
            for (sep in listOf('|', '｜')) {
                val bar = line.text.indexOf(sep)
                if (bar < 0) continue
                val left = line.text.substring(0, bar).trim()
                val right = line.text.substring(bar + 1).trim()
                if (st.stationName.isEmpty() && isAddressLike(left).not() && left.isNotBlank()) {
                    st.stationName = extractStationName(left)
                }
                if (st.fullAddress.isEmpty() && right.isNotBlank() && isAddressLike(right)) {
                    // 长地址可能被 OCR 拆到相邻多行：先向下拼 1~3 行，拼完仍像地址且非空则用拼接结果，否则退回单行
                    val parts = mutableListOf(right)
                    var cursorY = line.boundingBox?.bottom
                    for (j in lines.indexOf(line) + 1 until minOf(lines.indexOf(line) + 4, lines.size)) {
                        val n = lines[j]
                        val nBox = n.boundingBox
                        if (nBox != null && cursorY != null && nBox.top - cursorY > ADDR_LINE_GAP_MAX) break
                        val nt = n.text.trim()
                        if (nt.isEmpty()) continue
                        if (nt.length < 2 || nt.first().isDigit() || nt.startsWith("|") ||
                            CONTINUE_BLOCK_WORDS.any { nt.contains(it) }) break
                        parts.add(nt)
                        cursorY = nBox?.bottom ?: cursorY
                    }
                    val joinedAddr = parts.joinToString("")
                    val finalAddr = if (isAddressLike(joinedAddr) && joinedAddr.length > right.length) joinedAddr else right
                    st.fullAddress = stripBrackets(finalAddr).take(MAX_ADDRESS_LEN); st.addrFrom = "S2-pipe"
                }
            }
        }
    }
    }

    /** S5: "已放至/已暂存至" 句式。 */
    private fun stepPlacedPhrase(allText: String, st: LocationState) {
    // S5: "已放至/已暂存至" pattern
    if (st.fullAddress.isEmpty()) {
        ADDR_PLACED.find(allText)?.let { m ->
            val a = m.groupValues[1].trim()
            if (isAddressLike(a)) { st.fullAddress = stripBrackets(a).take(MAX_ADDRESS_LEN); st.addrFrom = "S5-placed" }
        }
    }
    }

    /** S6/S6b: "到...取件" 单行+跨行（1~3 行回拼）。 */
    private fun stepAfterToPhrase(lines: List<OCREngine.TextLine>, st: LocationState) {
    // S6: "到...取件/领取/门店/取运单" template (SMS/APP style)
    // Scope to lines containing 凭/取件 to avoid greedy match across unrelated 到 in joined text.
    // 合并自 S6a（单行）+ S6b（跨行）：先试单行，单行失败再向前拼 1~3 行，触发词判断与后处理复用同一套。
    if (st.fullAddress.isEmpty()) {
        for (i in lines.indices) {
            val line = lines[i]
            val hasTrigger = line.text.contains("凭") || line.text.contains("取件") || line.text.contains("取运单") ||
                line.text.contains("取您") || line.text.contains("取你的") || line.text.contains("取走")
            if (!hasTrigger) continue

            // S6a: 单行内匹配
            ADDR_AFTER_TO.find(line.text)?.let { m6 ->
                val a = m6.groupValues[1].trim()
                val clean = a.replace(PING_NOISE_TRAIL, "").trim()
                if (isAddressLike(clean)) {
                    st.fullAddress = stripBrackets(clean).take(MAX_ADDRESS_LEN)
                    st.addrFrom = "S6a"
                    if (st.stationName.isEmpty()) st.stationName = extractStationName(clean)
                    return@let
                }
            }
            if (st.fullAddress.isNotEmpty()) break

            // S6b: 跨行匹配——OCR 常把「到<地址>」和「取运单…」拆成多个 TextLine，
            // 拼接前 1~3 行（结束词「取您/取件」可能在 3 行外）
            for (span in 1..CARD_LINE_WINDOW) {
                if (i - span < 0) break
                val start = i - span
                val combined = (start until i).joinToString(" ") { lines[it].text } + " " + line.text
                ADDR_AFTER_TO.find(combined)?.let { m6 ->
                    val a = m6.groupValues[1].trim()
                    val clean = a.replace(PING_NOISE_TRAIL, "").trim()
                    if (isAddressLike(clean)) {
                        st.fullAddress = stripBrackets(clean).take(MAX_ADDRESS_LEN)
                        st.addrFrom = "S6b"
                        if (st.stationName.isEmpty()) st.stationName = extractStationName(clean)
                        return@let
                    }
                }
            }
            if (st.fullAddress.isNotEmpty()) break
        }
    }
    }

    /** S7: 号柜行——无守卫，cabinet 无条件更新（拆分后不得加 early-return）。 */
    private fun stepCabinetLine(lines: List<OCREngine.TextLine>, st: LocationState) {
    // S7: cabinet number + address from "号柜" line
    for (idx in lines.indices) {
        val line = lines[idx]
        if (!line.text.contains("号柜")) continue
        CABINET_NUM.find(line.text)?.let { st.cabinet = it.groupValues[1] }
        var s7addr = stripBrackets(line.text.trim())
        // 若本行不够像地址（柜号行常只有「2号柜」），向上拼 1~2 行的地址前缀
        if (!isAddressLike(s7addr) && idx > 0) {
            val up = StringBuilder()
            for (j in (idx - 2).coerceAtLeast(0) until idx) {
                val u = lines[j].text.trim()
                if (u.isEmpty()) continue
                up.append(u)
            }
            val cand = (up.toString() + s7addr)
            if (isAddressLike(cand)) s7addr = cand.take(MAX_ADDRESS_LEN)
        }
        if (st.fullAddress.isEmpty() && isAddressLike(s7addr))
            { st.fullAddress = stripBrackets(s7addr).take(MAX_ADDRESS_LEN); st.addrFrom = "S7-cabinet" }
    }
    }

    /** S8: 前缀关键词邻近行（无守卫，stationName 填充无条件）。 */
    private fun stepNearbyPrefix(lines: List<OCREngine.TextLine>, st: LocationState) {
    // S8: nearby lines after prefix keywords
    val prefixes = EXPLICIT_LABELS + listOf("代收点", "地址", "号柜")
    for (i in lines.indices) {
        if (!prefixes.any { lines[i].text.contains(it) }) continue
        if (st.stationName.isEmpty()) st.stationName = extractStationName(lines[i].text)
        for (j in i + 1..minOf(i + 2, lines.lastIndex)) {
            if (st.fullAddress.isNotEmpty()) break
            val n = lines[j].text.trim()
            if (!isAddressLike(n)) continue
            // 命中后向下拼 1~2 行地址续行（跳过单字/数字/噪声行）
            var s8addr = stripBrackets(n).take(MAX_ADDRESS_LEN)
            if (s8addr.length < MAX_ADDRESS_LEN) {
                val contParts = mutableListOf(s8addr)
                for (k in j + 1 until minOf(j + CARD_LINE_WINDOW, lines.size)) {
                    val c = lines[k].text.trim()
                    if (c.isEmpty()) break
                    if (c.length < 2 || c.first().isDigit() || c.startsWith("|") ||
                        CONTINUE_BLOCK_WORDS.any { c.contains(it) }) break
                    contParts.add(c)
                }
                val joined = contParts.joinToString("")
                if (isAddressLike(joined)) s8addr = joined.take(MAX_ADDRESS_LEN)
            }
            st.fullAddress = s8addr; st.addrFrom = "S8-nearby"; break
        }
    }
    }

    /** S9: 全角括号内地址。 */
    private fun stepParenthesized(lines: List<OCREngine.TextLine>, st: LocationState) {
    // S9: parenthesized address
    if (st.fullAddress.isEmpty()) {
        for (line in lines) {
            val m = PAREN_ADDR.find(line.text)
            if (m != null && isAddressLike(m.groupValues[1])) {
                st.fullAddress = stripBrackets(m.groupValues[1]).take(MAX_ADDRESS_LEN); st.addrFrom = "S9-paren"; break
            }
        }
    }
    }

    /** S10: 兜底——含路/街/柜特征的行 + 向下续行拼接。 */
    private fun stepFallback(lines: List<OCREngine.TextLine>, st: LocationState) {
    // S10: fallback - any line with road/street/cabinet indicators
    if (st.fullAddress.isEmpty()) {
        for (idx in lines.indices) {
            val line = lines[idx]
            if (st.stationName.isEmpty()) st.stationName = extractStationName(line.text)
            if (!line.text.contains(ADDR_PIPE_FULL) || !isAddressLike(line.text.trim())) continue
            // 向下续行拼接：OCR 常把地址拆成相邻多行（如 申通快/谦/申通快递）
            var addrBase = stripBrackets(line.text.trim())
            if (addrBase.length < MAX_ADDRESS_LEN) {
                var cursorY = line.boundingBox?.bottom
                val parts = mutableListOf(addrBase)
                for (j in idx + 1 until minOf(idx + 4, lines.size)) {
                    val n = lines[j]
                    val nBox = n.boundingBox
                    // 纵 gap 过大（>600px）说明不是同一地址块，停止
                    if (nBox != null && cursorY != null && nBox.top - cursorY > ADDR_LINE_GAP_MAX) break
                    val nt = n.text.trim()
                    if (nt.isEmpty()) continue
                    // 过滤单字错字（OCR 拆出的笔画字，如 谦）与非地址噪声行
                    if (nt.length < 2) continue
                    if (nt.first().isDigit() || nt.startsWith("|") || nt.startsWith("●") ||
                        CONTINUE_BLOCK_WORDS.any { nt.contains(it) }) break
                    parts.add(nt)
                    cursorY = nBox?.bottom ?: cursorY
                }
                val joined = parts.joinToString("")
                if (isAddressLike(joined)) { addrBase = joined.take(MAX_ADDRESS_LEN) } else { addrBase = parts.first() }
            }
            st.fullAddress = addrBase; st.addrFrom = "S10-fallback"; break
        }
    }
    }

    /** 收尾：快递柜/智能柜识别——追加柜名+柜号，修正劣质站名。 */
    private fun postProcessLocker(allText: String, st: LocationState) {
    // 后处理：快递柜/智能柜识别——在取件地址后追加柜名+柜号，并修正站名
    val lockerName = STATION_TYPE_MAP.entries
        .filter { it.value == StationType.LOCKER }
        .map { it.key }
        .sortedByDescending { it.length }
        .firstOrNull { allText.contains(it) }
    if (lockerName != null) {
        // 劣质站名替换：动作前缀（已放入/待取件…）或"含数字的商品/规格名"（如 4.5英寸昧碟）都不是站名 → 换成柜名
        val stationLooksBad = listOf("已放入", "已放至", "已暂存", "待取件", "待取", "已派送").any { st.stationName.startsWith(it) } ||
            (st.stationName.any { it.isDigit() } &&
                STATION_TYPE_MAP.keys.none { st.stationName.contains(it) } &&
                ADDR_PIPE_FULL.containsMatchIn(st.stationName).not())
        if (st.stationName.isNotEmpty() && st.stationName != lockerName && stationLooksBad) {
            st.stationName = lockerName
        }
        if (st.fullAddress.isNotEmpty() && !st.fullAddress.contains(lockerName)) {
            st.fullAddress = (st.fullAddress + lockerName).take(MAX_ADDRESS_LEN)
        }
        // 追加柜号（如 2号柜）：地址若没有"X号柜"则补上
        if (st.cabinet?.isNotEmpty() == true &&
            !ANY_CABINET_NUM.containsMatchIn(st.fullAddress)) {
            st.fullAddress = (st.fullAddress + st.cabinet + "号柜").take(MAX_ADDRESS_LEN)
        }
    }
    }

    /** 结束：折叠重复、归类站点、调试日志、组装 [PickupLocation]。 */
    private fun buildLocation(lines: List<OCREngine.TextLine>, allText: String, st: LocationState): PickupLocation {
    // 折叠 OCR 重复的路名/站名（对 S2/S5/S10 等未走 cleanAddress 的路径也生效）
    st.fullAddress = dedupeRepeated(st.fullAddress)

    // Determine station type
    val stype = classifyStation(st.stationName, st.fullAddress, allText)

    // If station name still empty, try to extract from full address or all text
    if (st.stationName.isEmpty()) {
        st.stationName = extractStationName(st.fullAddress)
    }
    if (st.stationName.isEmpty()) {
        st.stationName = extractStationName(allText)
    }

    if (BuildConfig.DEBUG) {
        android.util.Log.d("CodeExtrDiag",
            "ADDR=full=[${st.fullAddress}] from=[${st.addrFrom}] station=[${st.stationName}] cabinet=[${st.cabinet}] type=[$stype] allText=" + allText)
        // 调试快照：地址结果（与码候选同屏展示）
        RecognitionDebugStore.captureAddress(
            RecognitionDebugStore.AddressInfo(
                fullAddress = st.fullAddress,
                station = st.stationName,
                cabinet = st.cabinet,
                from = st.addrFrom
            )
        )
    }

    return PickupLocation(
        stationName = st.stationName.ifEmpty { "未知站点" },
        stationType = stype,
        cabinetNumber = st.cabinet,
        fullAddress = st.fullAddress,
        addrFrom = st.addrFrom
    )
    }


    /**
     * 该行是否落在 `code` 所在的**通知卡片窗口**内（±3 行且 y 距离 ≤400px）。
     *
     * 供上层判断"预存地址只作用于命中关键词那一行所属的那个码" ——
     * 多码同屏时，一条预存地址不能被套到所有码上。
     * 与 [extractAddressForCode] 共用同一套窗口规则（[inCodeWindow]），避免两处漂移。
     */
    fun isLineInCodeWindow(lines: List<OCREngine.TextLine>, code: String, lineIndex: Int): Boolean {
        if (lines.isEmpty() || lineIndex !in lines.indices) return false
        val codeIdx = lines.indexOfFirst { it.text.contains(code) }
        if (codeIdx < 0) return false
        val codeBoxTop = lines[codeIdx].boundingBox?.let { it.top.toFloat() }
        return inCodeWindow(lines, codeIdx, codeBoxTop, lineIndex)
    }

    /** 卡片窗口规则（唯一实现）。`otherIdx == codeIdx` 视为不在窗口内（地址总在码行之外）。 */
    private fun inCodeWindow(
        lines: List<OCREngine.TextLine>,
        codeIdx: Int,
        codeBoxTop: Float?,
        otherIdx: Int
    ): Boolean {
        if (otherIdx == codeIdx) return false
        if (otherIdx !in lines.indices) return false
        if (kotlin.math.abs(otherIdx - codeIdx) > CARD_LINE_WINDOW) return false
        val b = lines[otherIdx].boundingBox ?: return true
        val cIdxBox = lines[codeIdx].boundingBox
        if (cIdxBox != null && codeBoxTop != null) {
            return kotlin.math.abs(b.top.toFloat() - codeBoxTop) <= CARD_WINDOW_Y_GAP
        }
        return true
    }

    /**
     * 按码定位提取专属地址（多驿站通知中心场景）。
     * 在「码所在行附近的通知卡片窗口」内找该码的取件地址，而不是全屏抓一个地址。
     * 码行 ±3 行 且 y 距离 ≤ 400px 视为同一通知卡片。
     */
    fun extractAddressForCode(lines: List<OCREngine.TextLine>, code: String): String {
        if (lines.isEmpty()) return ""
        val codeIdx = lines.indexOfFirst { it.text.contains(code) }
        if (codeIdx < 0) return ""
        val codeBoxTop = lines[codeIdx].boundingBox?.let { it.top.toFloat() }

        fun inWindow(otherIdx: Int): Boolean = inCodeWindow(lines, codeIdx, codeBoxTop, otherIdx)

        // 窗口内的行（按 y 排序，从码行下方优先——地址/取件说明通常在码下方）
        val windowLines = lines
            .filterIndexed { i, _ -> inWindow(i) }
            .sortedBy { it.boundingBox?.top ?: 0 }
        if (windowLines.isEmpty()) return ""
        val windowText = windowLines.joinToString(" ") { it.text }

        // 优先级 0（新增，2026-09-16 多码同框地址串台修复）：
        // **行内含本码值**的「到…」句式 —— 地址与码写在同一行，是唯一不依赖窗口边界的硬证据。
        // 真机/短信典型：凭8-2-3311到建设南路取您的快递；而相邻卡片的「凭1-6-5020到育新路北段店」
        // 在 ±3 行窗口里排在前面，旧实现按行号升序取第一个「到」→ 地址串台到别的码上。
        for (i in lines.indices) {
            val t = lines[i].text
            if (!t.contains(code)) continue
            for (span in 0..CARD_LINE_WINDOW) {
                if (i + span >= lines.size) break
                val combined = (i..i + span).joinToString("") { lines[it].text }
                // 去掉本码值本身，避免「凭<码>」被当成地址片段
                ADDR_AFTER_TO.find(combined)?.let { m0 ->
                    val clean0 = m0.groupValues[1].trim().replace(PING_NOISE_TRAIL, "").trim()
                    if (!clean0.startsWith("达") && isAddressLike(clean0)) {
                        return stripBrackets(clean0).take(MAX_ADDRESS_LEN)
                    }
                }
            }
        }

        // 优先级 1：S6 「到…取件/取用」句式（通知体最常见的地址锚点）
        // 地址可能跨行（LINE8"…到育新路与季庄街…社区卫生" + LINE9"所对面2号柜H36…取您的快递"）
        // 仅在本码 ±3 行的窗口内找；含「到」即尝试（同码头尾地址常在码行，无需同行的取件词）
        val lo = (codeIdx - CARD_LINE_WINDOW).coerceAtLeast(0)
        val hi = (codeIdx + CARD_LINE_WINDOW).coerceAtMost(lines.lastIndex)
        for (i in lo..hi) {
            val t = lines[i].text
            if (!t.contains("到")) continue
            // 单行先试（排除"到达/已到达"动词：捕获以"达"开头说明是"到达xx"误抽，非地址介词"到"）
            ADDR_AFTER_TO.find(t)?.let { m6 ->
                val clean0 = m6.groupValues[1].trim().replace(PING_NOISE_TRAIL, "").trim()
                if (!clean0.startsWith("达") && isAddressLike(clean0)) return stripBrackets(clean0).take(MAX_ADDRESS_LEN)
            }
            // 跨行向下拼接 1~3 行
            for (span in 1..CARD_LINE_WINDOW) {
                if (i + span >= lines.size) break
                val combined = (i..i + span).joinToString("") { lines[it].text }
                ADDR_AFTER_TO.find(combined)?.let { m6b ->
                    val clean0 = m6b.groupValues[1].trim().replace(PING_NOISE_TRAIL, "").trim()
                    if (!clean0.startsWith("达") && isAddressLike(clean0)) return stripBrackets(clean0).take(MAX_ADDRESS_LEN)
                }
            }
        }

        // 优先级 2：地址: 标签
        // 优先级 2：地址: 标签（含退化标签补全——如 地址:育新路育新路育 时取下方干净地址行）
        val labLine = windowLines.firstOrNull { it.text.contains(REG_ADDR_LABEL_MARK) }
        if (labLine != null) {
            val a0 = cleanAddress(ADDR_LABEL.find(labLine.text)?.groupValues?.get(1).orEmpty())
            if (isAddressLike(a0) && a0.length >= MIN_ADDRESS_CORE_LEN) {
                // 前缀命中同行更完整行 或 同前缀更长行
                val p4 = a0.substring(0, MIN_ADDRESS_CORE_LEN)
                val byPrefix = windowLines.map { it.text.trim() }
                    .filter { it.length > a0.length && it.startsWith(p4) && isAddressLike(it) }
                    .maxByOrNull { it.length }
                if (byPrefix != null) return byPrefix.take(MAX_ADDRESS_LEN)
            }
            // 标签值退化/短时：取标签行下方邻近的干净完整地址（同一通知卡片区域）
            val labY = labLine.boundingBox?.let { it.top.toFloat() } ?: 0f
            val nearby = windowLines
                .filter { tl ->
                    val y = tl.boundingBox?.let { it.top.toFloat() } ?: 0f
                    y > labY && y - labY < LABEL_NEARBY_Y_GAP && tl.text.trim().length > a0.length
                }
                .map { it.text.trim() }
                .filter { it.length >= MIN_ADDRESS_CORE_LEN && isAddressLike(it) }
                .maxByOrNull { it.length }
            if (nearby != null) return nearby.take(MAX_ADDRESS_LEN)
            if (isAddressLike(a0)) return a0.take(MAX_ADDRESS_LEN)
        }

        // 优先级 3：窗口内最长的像地址行
        val best = windowLines
            .map { it.text.trim() }
            .filter { isAddressLike(it) }
            .maxByOrNull { it.length }
        return best?.take(MAX_ADDRESS_LEN) ?: ""
    }

    /** Backward-compatible: return address string from structured location. */
    fun extractAddress(lines: List<OCREngine.TextLine>, allText: String): String {
        return extractLocation(lines, allText).fullAddress
    }

    // ---------------------------------------------------------------
    // 竞争仲裁（渐进版，2026-08-13）：窗口地址优先；全屏地址仅高置信来源采信
    // 背景：步骤制"先到先得"的结构性弱点——S7/S8/S9/S10 几何兜底可能在多通知
    // 同屏时抓到别的通知的地址（串台）。复审规则：码窗口内的地址无条件优先；
    // 窗口无地址时，全屏结果只有来自「文本证据型」步骤（S0/S0b/S0c/S2/S5/S6/
    // S-Coupon）才采信，几何兜底型（S7/S8/S9/S10）在多码同屏时宁缺毋滥。
    // ---------------------------------------------------------------

    /** 文本证据型步骤（标签/句式直接写明地址）——高置信，全屏采信。 */
    private val HIGH_CONFIDENCE_SOURCES = setOf(
        "SCoupon-store", "S0-label", "S0b-addrLabel", "S0c-column",
        "S2-pipe", "S5-placed", "S6a", "S6b"
    )

    /**
     * 竞争仲裁：合并窗口地址与全屏地址。
     * @param perCodeAddr [extractAddressForCode] 的窗口定位结果（可能为空）
     * @param fullAddress 全屏 [extractLocation] 的兜底结果（可能为空）
     * @param multiCodeOnScreen 是否多码同屏。多码时几何兜底型来源（S7~S10）可能抓到别的
     *   通知的地址（串台），仅采信文本证据型来源，宁缺毋滥；单码时全屏地址必然属于本卡，
     *   几何兜底照常采信。
     * @return 最终地址；窗口地址优先，全屏按上述规则采信
     */
    fun resolveAddress(
        lines: List<OCREngine.TextLine>,
        allText: String,
        perCodeAddr: String,
        fullAddress: String,
        multiCodeOnScreen: Boolean = false
    ): String {
        if (perCodeAddr.isNotBlank()) return perCodeAddr
        if (fullAddress.isBlank()) return ""
        if (!multiCodeOnScreen) return fullAddress
        val loc = extractLocation(lines, allText)
        return if (loc.addrFrom in HIGH_CONFIDENCE_SOURCES) fullAddress else ""
    }

    /**
     * 全屏地址是否为高置信文本证据来源（竞争仲裁判定，供管线复用）。
     * 多码同屏时外层只需调用一次，避免每个码重复全量 [extractLocation] 扫描。
     */
    fun isHighConfidenceFullAddress(lines: List<OCREngine.TextLine>, allText: String): Boolean =
        extractLocation(lines, allText).addrFrom in HIGH_CONFIDENCE_SOURCES

    /**
     * 地址识别增强版：**用户预存地址 → 原 11 级策略**。
     *
     * 预存模型（用户 2026-09-15 的想法）：**完整名称 + 一个至多个关键词**；
     * OCR 命中任一关键词 → 用该记录的**完整名称**（写进记录、主页显示）。
     *
     * 以**纯数据**注入（不依赖 Context）——语料回归与 JVM 单测都能覆盖。
     * 各识别入口用 [extractAddressFromStores] 从 Context 取数据后调用本函数。
     *
     * 2026-09-15：**移除了原 S1b「自动学习站点匹配」**（只保留一套匹配）。
     * 自动学习数据仍作为「常用取件地址 → 从历史导入」的候选来源，不参与识别。
     */
    fun extractAddress(
        lines: List<OCREngine.TextLine>,
        allText: String,
        saved: List<com.pickupcode.app.learner.SavedAddressStore.MatcherView> = emptyList()
    ): String {
        // 预存命中即终结：返回**完整名称**，不再走下面的通用策略。
        SavedAddressMatcher.match(lines, saved)?.let { return it.fullName }
        return extractAddress(lines, allText)
    }

    /**
     * 便捷入口：从 Context 读预存地址后走上面的纯函数版。
     * 四个识别入口（无障碍截图 / 分享图片 ×2 / 短信）都应走这里 ——
     * 历史 bug：地址增强只挂在短信路径的旧重载上，另外三条主路径从未生效。
     */
    fun extractAddressFromStores(
        context: android.content.Context,
        lines: List<OCREngine.TextLine>,
        allText: String
    ): String = extractAddress(
        lines = lines,
        allText = allText,
        saved = com.pickupcode.app.learner.SavedAddressStore.matcherViews(context)
    )

    /**
     * 独立柜号提取（参考同类产品实现 extractCabinetInfo）：从取件文本里抓柜号/格口，
     * 如 2号柜、5号副柜、云柜12号、12号格口、A区3号柜。返回规范化串（含"柜/格口"后缀），
     * 无则空串。供入库时作为独立 cabinetNumber 字段保存（区别于拼进地址尾部）。
     */
    fun extractCabinetNumber(lines: List<OCREngine.TextLine>, allText: String): String {
        val texts = lines.map { it.text }.filter { it.isNotBlank() }
        // 优先整行完整柜号：X号[副/主]柜 / 云柜X号 / X号格口
        // 去掉泛化 [\u4e00-\u9fa5]{0,4}柜（会抓出「5号中午到柜」类噪声），位数限制 ≤3
        for (t in texts) {
            val m = CABINET_NUM_COMPLEX.find(t) ?: continue
            val v = m.value
            if (v.length <= 12) return v
        }
        // 兜底：纯 X号柜
        val plain = CABINET_NUM.find(allText)
        return if (plain != null && plain.groupValues[1].length <= 6) plain.groupValues[1] + "号柜" else ""
    }

    // ---------------------------------------------------------------
    // Station helpers
    // ---------------------------------------------------------------

    private fun extractStationName(text: String): String {
        // Try 【】 first (skip promo/voucher labels like 【新店福利】)
        BrandResolver.BRACKET_BRAND.findAll(text).forEach { m ->
            val c = m.groupValues[1].trim()
            if (c.any { it.isDigit() }) return@forEach
            if (PROMO_LABEL_WORDS.any { c.contains(it) }) return@forEach
            return stripBrackets(c)
        }
        // Try known station keywords
        for (kw in STATION_TYPE_MAP.keys) {
            if (text.contains(kw)) {
                // Extract the full station name: text before and including the keyword
                val idx = text.indexOf(kw)
                val start = (0 until idx).lastOrNull { text[it] in "，,。.；;、|｜ " }?.plus(1) ?: 0
                val end = (idx + kw.length until text.length)
                    .firstOrNull { text[it] in "，,。.；;、|｜ " } ?: text.length
                val name = text.substring(start, end).trim()
                if (name.length in 2..16) return name
            }
        }
        // 放宽：形如 X超市 / X便利店 / X商行 的店名（如 鮮佰汇超市）也当站点/收货点
        FOOD_STORE_PATTERN.find(text)?.let { m ->
            val name = m.groupValues[0].trim()
            if (name.length in 2..12 && PROMO_LABEL_WORDS.none { name.contains(it) }) return name
        }
        return ""
    }

    private fun classifyStation(stationName: String, address: String, allText: String): StationType {
        val combined = "$stationName $address $allText"
        for ((kw, type) in STATION_TYPE_MAP) {
            if (combined.contains(kw)) return type
        }
        return StationType.UNKNOWN
    }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    /** 去掉取件地址开头/结尾的【】（）包围（OCR 常把站名/地址包在全角括号里）。 */
    private fun stripBrackets(s: String): String {
        var r = s.trim()
        while (r.isNotEmpty()) {
            val c0 = r.first(); val c9 = r.last()
            if ((c0 == '【' && c9 == '】') || (c0 == '(' && c9 == ')') || (c0 == '（' && c9 == '）')) {
                r = r.substring(1, r.length - 1).trim()
            } else break
        }
        // 处理不闭合的左括号（OCR/UI 折叠截断导致如 "【育新路北段店" 无右括号）：剥掉孤立左括号
        if (r.startsWith("【") && !r.contains("】")) r = r.removePrefix("【").trim()
        if (r.startsWith("（") && !r.contains("）")) r = r.removePrefix("（").trim()
        if (r.startsWith("(") && !r.contains(")")) r = r.removePrefix("(").trim()
        return r
    }

    /** 营销/促销/商品文案关键词：命中即整条判否（不是地址）。
     *  来源：2026-09-16 真机语料回归（58 张相册截图）里被误当地址的原文。 */
    private val ADDR_MARKETING_NOISE = listOf(
        "退货包运费", "包运费", "包邮", "无理由", "运费险", "退货", "七天", "7天",
        "秒杀", "限时", "疯抢", "抢购", "即将抢完", "优惠", "立减", "满减", "到手价", "券后",
        "回头客", "已售", "已抢", "全周可用", "免预约", "随时退", "过期退", "先用后付",
        "直播中", "百亿补贴", "赠品", "试吃", "试用", "去抢购", "查看订单", "查看物流",
        "订单详情", "汀单", "交易快照", "实付", "合计", "小计", "预计获得", "两个工作日内到账",
        "导航", "派件中", "运输中", "待收货", "全部 待付款",
        // 电商店铺/商品页残留（拼多多／淘宝待取件列表里混进来的店铺名与促销角标）
        "上新", "旗舰店", "专营店", "专卖店", "已拼", "领券", "人付款", "免运费", "预售", "秒付"
    )

    /** 价格串：地址里不会出现 ¥10.8 / 9.9 元 这类价格。 */
    private val PRICE_LIKE = Regex("[¥￥]\\s*\\d|\\d+\\.\\d+")

    /** 地址尾部 UI 噪音关键词，出现时截断（展开/复制/拨打电话 等按钮文案）。 */    private val ADDR_TRAIL_NOISE = listOf(
        "展开", "收起", "复制", "订阅提醒", "拨打电话", "拨打", "查看物流", "确认收货", "物流电话", "联系驿站", "联系快递员",
        "分享", "号码保护", "虚拟号码", "已通过虚拟号码发货", "待取件", "物流服务", "物流信息"
    )

    /** 清理标签式地址：剥括号 + 在标点/噪音词处截断，取干净的地址前缀。 */
    private fun cleanAddress(s: String): String {
        var r = stripBrackets(s.trim())
        for (sep in ":：，,。.。;；…") {
            val idx = r.indexOf(sep)
            if (idx >= 0) r = r.substring(0, idx)
        }
        for (noise in ADDR_TRAIL_NOISE) {
            val idx = r.indexOf(noise)
            if (idx >= 0) r = r.substring(0, idx)
        }
        r = dedupeRepeated(stripBrackets(r.trim()))
        return r.trim()
    }

    /** 折叠连续 3 次以上重复的相邻片段（OCR 常把路名/站名读重，如 育新路育新路育新路→育新路）。
     *  只折叠 3+ 次重复，保留合法的双字重复（如站名里正常的两个相同字）。 */
    private fun dedupeRepeated(s: String): String {
        var r = s
        if (r.isEmpty()) return r
        for (len in 4 downTo 2) {
            val re = Regex("(.{$len})\\1{2,}")
            var prev = ""
            while (prev != r) {
                prev = r
                val m = re.find(r) ?: break
                // 用第一个匹配的重复单元长度做逐步折叠（处理同一串内多种重复）
                val unit = m.groupValues[1]
                r = r.replace(Regex("(?:${Regex.escape(unit)}){3,}"), unit)
            }
        }
        return r
    }

    private fun isAddressLike(s: String): Boolean {
        val t = stripBrackets(s)
        if (t.length !in MIN_ADDRESS_CORE_LEN..MAX_ADDRESS_LEN || t.none { it in '\u4e00'..'\u9fff' }) return false
        // Must contain address indicators (road/street/building/cabinet etc)
        // 「元」会与货币/金额冲突（如 累计省4元>、¥9.9元、实付¥8.90）——只有 单元/几单元 里的 元 才算地址指示符
        val pipeChars = ADDR_PIPE_FULL.findAll(t).map { it.value }.toList()
        val hasStreet = pipeChars.isNotEmpty() || ADDR_LANDMARK.containsMatchIn(t)
        val bareYuanOnly = pipeChars.isNotEmpty() && pipeChars.all { it == "元" } && !t.contains("单元")
        if (!hasStreet || bareYuanOnly) return false
        // Exclude non-address strings that happen to contain a "号" indicator (e.g. 运单尾号)
        if (listOf("取运单", "运单尾号", "运单", "包裹", "删除").any { t.contains(it) }) return false
        // Exclude pickup-code prefix noise (e.g. OUCR 把「取件码」拆成 件码 紧跟码值，如 件码067865到…)
        if (listOf("件码", "取件码", "取货码", "提取码", "取餐码", "取单码").any { t.contains(it) }) return false
        // Exclude 运单号/单号 标签（如 OCR 误写的 快谨单号）——不是取件地址
        if (t.endsWith("单号") || listOf("运单号", "订单号", "快运单号", "快递单号").any { t.contains(it) }) return false
        // Exclude 快递运单号行："品牌+快递后缀+冒号/空格+长数字串"（如 中通快递:79130792811022）
        // 这是快递详情页的运单号行，绝不可能是指件地址；真实地址不会带"快递:9位以上纯数字"。
        if (COURIER_TRACKING_LINE.containsMatchIn(t)) return false
        // Exclude 营销/促销/商品 文案（真机语料回归 2026-09-16）：这类文案常含「号/中/站/店」等地址指示字
        // （如「退货包运费保障中」「家用大号桌面键盘书桌写字垫」「承诺达退货包运费保障中」），
        // 但绝不可能是指件地址 —— 宁可该码地址为空（走兜底/留空），也不要把垃圾写进记录。
        if (ADDR_MARKETING_NOISE.any { t.contains(it) }) return false
        // Exclude 价格串（¥10.8 / 9.9元 / 22.1）
        if (PRICE_LIKE.containsMatchIn(t)) return false
        // Exclude 订单/交易/UI 界面标签（如 OCR 把「订单详情」读成 订单详惰、交易快照、券号/券码等）——不是取件地址
        // 「商品」单独排除会误杀真实地址「商品街」（如 育新路商品街），仅当不含「商品街」时才排除
        if (listOf("订单", "交易", "快照", "详惰", "详情页", "规格", "小计", "合计", "数量", "券码", "券号").any { t.contains(it) } ||
            (t.contains("商品") && !t.contains("商品街"))) return false
        // OCR 把「详情/快照」等标签的字读错（详惰/快照）概率高，真实地址几乎不会以「详/惰」作实义词——单独拦以开头为详的标签串
        if (t.startsWith("详惰") || t.startsWith("订单")) return false
        // Exclude 隐私号/虚拟号/联系电话 等通知文案（带 **** 脱敏的手机信息），不是取件地址
        if (listOf("号码保护", "虚拟号码", "联系电话", "手机号", "客服电话", "已通过虚拟号码发货").any { t.contains(it) }) return false
        if (t.contains("****")) return false
        return listOf("展开", "复制", "拨打", "导航", "订阅", "延长收货", "查看物流", "确认收货").none { t.contains(it) }
    }
}
