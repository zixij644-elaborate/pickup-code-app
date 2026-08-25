package com.pickupcode.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.pickupcode.app.App
import com.pickupcode.app.BuildConfig
import com.pickupcode.app.data.AppDatabase
import com.pickupcode.app.data.CodeHistory
import com.pickupcode.app.extractor.AIExtractor
import com.pickupcode.app.extractor.CodeExtractor
import com.pickupcode.app.extractor.AddressExtractor
import com.pickupcode.app.extractor.BrandResolver
import com.pickupcode.app.extractor.CouponDetector
import com.pickupcode.app.geocoder.GeocoderVerifier
import com.pickupcode.app.kuaidi100.Kuaidi100Verifier
import com.pickupcode.app.learner.PatternLearner
import com.pickupcode.app.notification.CodeNotificationManager
import com.pickupcode.app.ocr.OCREngine
import com.pickupcode.app.preferences.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class PickupCodeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PickupCodeA11y"
        private const val CHANNEL_ID = "pickup_code_result"
        // 结果提示通知 id：占最高位 0x7FFFFFFF，与主通知池(1..0x3FFFFFFF)/提醒(0x4..)/去重(0x6..) 三段互不相交（M12）
        private const val RESULT_NOTIFY_ID = 0x7FFFFFFF

        @JvmField
        val triggerRequested = AtomicBoolean(false)

        /** 触发来源：true=磁贴（需等控制面板收起再扫），false=音量键（目标界面已在当前屏幕，立即扫）。 */
        @Volatile
        var triggerViaPanel = false
            private set
        /** 触发（武装）时刻。 */
        @Volatile
        private var armedAtMs = 0L
        /** 武装后最后一次收到控制面板（systemui）窗口事件的时刻；0=武装后未见过面板事件。 */
        @Volatile
        private var lastSystemUiEventAtMs = 0L

        private const val SYSTEMUI_PKG = "com.android.systemui"
        /** 磁贴触发后，面板静默多久开始兜底扫描（用户有充分时间滑出面板并停在目标界面）。 */
        private const val PANEL_SILENCE_MS = 2000L
        /** 完全没收到面板事件（个别 ROM 不派发）时，武装多久后兜底扫描。 */
        private const val PANEL_FALLBACK_MS = 2000L

        /**
         * 手动触发（磁贴/音量键）统一入口：置标记 + 记录来源与时刻。
         * @param fromPanel true=快捷设置磁贴触发——消费策略会等控制面板收起；
         *                  false=音量键/无障碍快捷方式触发——立即消费。
         */
        fun armManual(fromPanel: Boolean) {
            triggerRequested.set(true)
            triggerViaPanel = fromPanel
            armedAtMs = System.currentTimeMillis()
            lastSystemUiEventAtMs = 0
        }

        /** 服务实例当前是否已被系统真实绑定（区别于 Settings.Secure 里的开关字符串）。
         *  onServiceConnected=true / onUnbind、onDestroy=false。 */
        @Volatile
        var connected = false
            private set

        /**
         * 判断「服务真正在运行」：AccessibilityManager.getEnabledAccessibilityServiceList
         * 只返回**已绑定**的服务（区别于 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 字符串）。
         * Xiaomi/HyperOS 杀进程/省电后常出现"设置里开着、服务实际没连上"的假连接状态——
         * 磁贴/主界面必须用此检测，否则触发标记无人消费、点击静默失效。
         */
        fun isReallyConnected(context: Context): Boolean {
            return try {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                    ?: return false
                val target = "${context.packageName}/${PickupCodeAccessibilityService::class.java.name}"
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { info ->
                        // 标准路径：id = "包名/类名"
                        val id = try { info.id } catch (_: Exception) { null }
                        if (id != null) {
                            id == target
                        } else {
                            // 个别 ROM 返回的 info 无 id：退到 resolveInfo 比对包名+类名
                            info.resolveInfo?.serviceInfo?.let { si ->
                                si.packageName == context.packageName &&
                                    si.name == PickupCodeAccessibilityService::class.java.name
                            } == true
                        }
                    }
            } catch (e: Exception) {
                Log.w(TAG, "查询无障碍真实连接状态失败", e)
                false
            }
        }

        private val AUTO_SCAN_PACKAGES = setOf(
            "com.meituan", "com.sankuai", "me.ele", "com.eg.android",
            "com.kfc", "com.mcdonalds", "com.cainiao",
            "com.taobao.taobao", "com.jingdong.app.mall", "com.pinduoduo",
        )
    }

    /** 顶层协程异常兜底：SupervisorJob 不吞子协程异常，无 handler 时任何未捕获异常都会崩进程。
     *  挂到 scope 上兜住所有 launch 子协程（节点遍历/验证/回填等），记日志 + 提示，不再崩溃。 */
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        if (e is CancellationException) return@CoroutineExceptionHandler
        Log.e(TAG, "协程未捕获异常", e)
        showResult("识别出错")
    }

    // 实例级协程作用域：随服务实例创建/销毁，onUnbind 时 cancel 避免跨重建累积泄漏（H2）。
    // 注意：服务实例在 onUnbind 后会被系统复用（再次 onServiceConnected）——因此必须 var，
    // 在 onServiceConnected 里检测失效后重建，否则关一次无障碍再开会永久空转（Top1 修复）。
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)
    // 复用单例主线程 Handler：heartbeat 自续 + onAccessibilityEvent 延时调度共用，便于统一 removeCallbacks（H3/M2）
    private val mainHandler = Handler(Looper.getMainLooper())
    // 截图回调线程池：模块级单例，避免每次 captureAndExtract 新建线程泄漏（M7）。
    // 同 scope：onUnbind shutdownNow 后需在重连时重建（Top1 修复）。
    private var screenshotExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 异步释放 ML Kit 客户端。close() 会等待在途识别/检测完成（最长 30s/10s），
     *  不能在 onUnbind/onDestroy 主线程里同步阻塞（ANR 风险）。 */
    private fun closeMlKitClients() {
        App.appScope.launch(Dispatchers.IO) {
            try { OCREngine.close() } catch (_: Exception) {}
            try { CouponDetector.close() } catch (_: Exception) {}
        }
    }

    /** 让系统重新回调磁贴的 onStartListening，磁贴按本进程 connected 标志刷新亮/暗。 */
    private fun notifyTileRefresh() {
        try {
            TileService.requestListeningState(this, ComponentName(this, PickupCodeTileService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "通知磁贴刷新失败: ${e.message}")
        }
    }

    private var lastAutoScanPkg: String? = null
    private var lastAutoScanTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        Log.d(TAG, "无障碍服务已连接")
        // 通知磁贴刷新状态：vivo 等 ROM 上 AccessibilityManager.getEnabledAccessibilityServiceList
        // 对已绑定服务会假阴性，磁贴以本进程 connected 标志为准（同进程内无 binder 歧义）
        notifyTileRefresh()

        // Top1: 服务实例可能在 onUnbind 后复用（用户关→开无障碍、系统临时解绑均会再次走到这里）。
        // 上一轮 onUnbind 已 cancel scope / shutdown executor，必须重建，否则识别功能静默全废。
        if (!scope.isActive) scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)
        if (screenshotExecutor.isShutdown) screenshotExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        val info = AccessibilityServiceInfo().apply {
            // Medium-1: 只注册 WINDOW_STATE_CHANGED（服务只消费该事件），减少无关事件唤醒
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        serviceInfo = info

        // 重连时先清掉可能残留的心跳任务，避免双心跳叠加
        mainHandler.removeCallbacks(heartbeat)
        mainHandler.postDelayed(heartbeat, 3000)

        // 冷启动竞态：磁贴可能在服务连接前已置标记。但**不在此立即扫描**——
        // 此刻用户多半还站在控制面板里，立即截图会截到面板（小红书用户反馈"还在面板里就开始记录了"）。
        // 交给心跳（按 triggerViaPanel 策略等面板收起）或窗口事件消费。
        if (triggerRequested.get()) {
            Log.d(TAG, "连接时触发标记待消费（按策略等待面板收起后自动扫描）")
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (triggerRequested.get() && manualTriggerDue()) {
                consumeManualTrigger("心跳兜底")
            }
            mainHandler.postDelayed(this, 3000)
        }
    }

    /** 手动触发是否到了可扫描时刻：磁贴触发必须等控制面板收起（防截到面板），音量键立即。 */
    private fun manualTriggerDue(): Boolean {
        if (!triggerViaPanel) return true // 音量键：目标界面已在当前屏幕
        val now = System.currentTimeMillis()
        return if (lastSystemUiEventAtMs >= armedAtMs) {
            // 武装后见过面板事件：面板已操作过，静默 PANEL_SILENCE_MS 后即可扫
            now - lastSystemUiEventAtMs >= PANEL_SILENCE_MS
        } else {
            // 武装后未收到任何面板事件（个别 ROM 不派发 systemui 事件）：宽限 PANEL_FALLBACK_MS 后兜底扫
            now - armedAtMs >= PANEL_FALLBACK_MS
        }
    }

    /** 消费手动触发标记并调度扫描（延迟 1.2s 让控制面板收起动画完成，扫描前还会再校验活动窗口）。 */
    private fun consumeManualTrigger(reason: String) {
        if (triggerRequested.getAndSet(false)) {
            triggerViaPanel = false
            armedAtMs = 0
            lastSystemUiEventAtMs = 0
            Log.d(TAG, "磁贴触发消费（$reason），延迟扫描")
            mainHandler.postDelayed({ performScanAfterPanelClose(0) }, 1200)
        }
    }

    /**
     * 扫描前再等一等：若当前活动窗口仍是系统控制面板/通知栏（个别 ROM 事件时序靠前），
     * 每秒复查一次，最多推迟 6 秒，避免截图截到面板"显示没有码"。
     */
    private fun performScanAfterPanelClose(tries: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && tries < 6) {
            val activePkg = try {
                rootInActiveWindow?.packageName?.toString()
            } catch (_: Exception) { null }
            if (activePkg == SYSTEMUI_PKG) {
                Log.d(TAG, "活动窗口仍是控制面板，推迟扫描(第${tries + 1}次)")
                mainHandler.postDelayed({ performScanAfterPanelClose(tries + 1) }, 1000)
                return
            }
        }
        performScan("手动触发")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // 收敛协程与 Handler，避免服务卸载后空转/泄漏（H2/H3）
        connected = false
        Log.d(TAG, "无障碍服务已解绑")
        notifyTileRefresh()
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        screenshotExecutor.shutdownNow()
        // 释放 ML Kit 客户端（unbind 未必紧跟 destroy，提前释放避免 native 累积）；异步，不在主线程阻塞
        closeMlKitClients()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        notifyTileRefresh()
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        screenshotExecutor.shutdownNow()
        // 释放 ML Kit 客户端，避免 native 资源随服务重建累积泄漏；异步，不在主线程阻塞
        closeMlKitClients()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 手动触发（磁贴/音量键）：
        //  - 控制面板（systemui）窗口事件只记录时刻、不消费标记——面板还开着，截了也是面板；
        //  - 非面板窗口事件 = 面板已收起、目标应用窗口回到前台 → 消费并延迟扫描。
        if (triggerRequested.get()) {
            val pkg = event?.packageName?.toString()
            if (pkg == SYSTEMUI_PKG) {
                lastSystemUiEventAtMs = System.currentTimeMillis()
                return
            }
            consumeManualTrigger("窗口=${pkg ?: "?"}")
            return
        }

        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            val now = System.currentTimeMillis()
            if (pkg == lastAutoScanPkg && now - lastAutoScanTime < 3000) return
            if (AUTO_SCAN_PACKAGES.any { pkg.startsWith(it) }) {
                lastAutoScanPkg = pkg
                lastAutoScanTime = now
                Log.d(TAG, "自动扫描: $pkg")
                mainHandler.postDelayed({
                    performScan("自动检测: $pkg")
                }, 800)
            }
        }
    }

    override fun onInterrupt() {}

    private fun performScan(source: String) {
        Log.d(TAG, "开始扫描: $source")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureAndExtract(source)
        } else {
            performScanFromText(source)
        }
    }

    /** 仅节点文字模式（API < 30兜底），不存截图 */
    private fun performScanFromText(source: String) {
        scope.launch {
            try {
                val settings = AppPreferences.observe(this@PickupCodeAccessibilityService).first()
                // 无障碍节点树遍历必须在主线程（AccessibilityNodeInfo 跨线程使用无官方保证，
                // 部分 ROM/窗口销毁竞态下在后台线程遍历会抛异常崩进程）
                val allText = withContext(Dispatchers.Main) { collectAllText() }
                val lines = allText.lines().map { OCREngine.TextLine(it, null, null) }
                // 正则/提取等 CPU 密集工作在 Default 线程
                withContext(Dispatchers.Default) {
                    tryExtract(allText, lines, null, settings, source)
                }
            } catch (e: CancellationException) {
                throw e // 取消必须重抛
            } catch (e: Exception) {
                // 顶层兜底：节点遍历/提取偶发异常只记日志 + 提示，不再直达未捕获处理器崩进程
                Log.e(TAG, "文本识别失败", e)
                showResult("识别出错")
            }
        }
    }

    private fun collectAllText(): String {
        val root = rootInActiveWindow ?: return ""
        val lines = collectNodeText(root)
        root.recycle()
        return lines.joinToString("\n")
    }

    private fun collectNodeText(node: AccessibilityNodeInfo): List<String> {
        val lines = mutableListOf<String>()
        if (node.text != null) {
            node.text.toString().trim().takeIf { it.length >= 2 }?.let { lines.add(it) }
        }
        if (node.contentDescription != null) {
            val desc = node.contentDescription.toString().trim()
            val txt = node.text?.toString()?.trim()
            if (desc.length >= 2 && desc != txt) lines.add(desc)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                lines.addAll(collectNodeText(child))
                child.recycle()
            }
        }
        return lines
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureAndExtract(source: String) {
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    val buf = s.hardwareBuffer
                    try {
                        val hwBmp = Bitmap.wrapHardwareBuffer(buf, s.colorSpace)
                            ?: return showResult("截屏失败")
                        // 深拷贝为普通位图：wrapHardwareBuffer 返回的位图依赖 buf 存活，
                        // 若在 OCR 异步读取前 close buf 会读到已释放缓冲。拷贝后即可安全 close（H1）
                        // 内存峰值控制：全分辨率 1:1 copy 会与原图同时存活（2× 屏幕内存，4K 屏约 66MB）。
                        // 拷贝时直接降采样到长边 ≤1920px（取件码/地址文本 OCR 足够），峰值显著下降。
                        val maxDim = 1920f
                        val scale = minOf(1f, maxDim / maxOf(hwBmp.width, hwBmp.height))
                        val bmp = if (scale < 1f) {
                            Bitmap.createScaledBitmap(
                                hwBmp,
                                (hwBmp.width * scale).toInt().coerceAtLeast(1),
                                (hwBmp.height * scale).toInt().coerceAtLeast(1),
                                true
                            )
                        } else {
                            hwBmp.copy(Bitmap.Config.ARGB_8888, false)
                        }
                        hwBmp.recycle()

                        scope.launch(Dispatchers.IO) {
                            try {
                                val lines = OCREngine.recognize(bmp)
                                val settings = AppPreferences.observe(this@PickupCodeAccessibilityService).first()
                                // 券码检测（需在 recycle 前用同一张 bitmap）
                                val coupons = if (settings.enableCouponCodes) {
                                    CouponDetector.detect(bmp)
                                } else emptyList()
                                val allText = lines.joinToString("\n") { it.text }
                                // H4: 截图保存时机后移到识别成功路径（tryExtract 内），失败不再产生垃圾文件；bmp 由 tryExtract 保存后回收
                                tryExtract(allText, lines, bmp, settings, source, coupons)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                try { bmp.recycle() } catch (_: Exception) {}
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "OCR失败", e)
                                try { bmp.recycle() } catch (re: Exception) { Log.w(TAG, "Bitmap recycle failed", re) }
                                showResult("识别出错")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "截屏失败", e)
                        showResult("识别出错")
                    } finally { buf.close() }
                }

                override fun onFailure(code: Int) { showResult("截屏失败($code)") }
            })
    }

    private suspend fun tryExtract(allText: String, ocrLines: List<OCREngine.TextLine>, bmp: Bitmap?, settings: AppPreferences.Settings, source: String, coupons: List<CouponDetector.CouponResult> = emptyList()) {
        val allResults = mutableListOf<Pair<String, CodeExtractor.CodeType>>()
        val codeSources = mutableMapOf<String, String>()
        // Medium-2: 自动扫描（自动检测）静默，不弹"未识别"类提示，避免骚扰
        val silent = source.startsWith("自动")

        // 金融/支付噪音拦截：银行、支付、转账等通知截图/短信里常出现数字（金额/验证码/余额）
        // 极易被当成取件码。命中金融词且无快递/取件信号词 → 整段不识别。
        if (CodeExtractor.isFinancialNoise(allText)) {
            Log.d(TAG, "金融/支付噪音文本，跳过识别")
            bmp?.recycle()
            if (!silent) showResult("未识别到取餐码/取件码（疑似银行/支付类通知）")
            return
        }

        // ① 券码：检测到二维码/条码并解码，code = 解码内容（不需要 OCR）
        val hasCoupon = collectCouponResults(coupons, settings, allResults, codeSources)

        // ② 识别到券码后互斥：不再做取餐码/取件码的识别与标注
        val aiDeferred = startAiExtract(allText, settings, hasCoupon)

        // ③ 正则识别（无券码时运行）
        if (!hasCoupon) collectRegexResults(ocrLines, settings, allResults, codeSources)

        // ④ 合并 AI 结果：与正则同码同 type 直接去重；不同 type 才留给下方冲突提示（问题2）
        val aiErr = mergeAiResults(aiDeferred, settings, allResults, codeSources)

        // Extract address (parcel scenario)
        val address = AddressExtractor.extractAddress(ocrLines, allText)

        // ⑤ 问题5：若正则未识别到且 AI 也失败，提示里带上失败原因（用户有感知）
        if (notifyIfNoResult(allResults, aiErr, settings, silent, bmp)) return

        // H4: 识别到结果才落盘截图（保存后立即回收位图，避免泄漏）
        val screenshotPath = bmp?.let {
            val path = saveScreenshot(it, System.currentTimeMillis())
            it.recycle()
            path
        } ?: ""

        // ⑥ 冲突检测（无障碍特有：同码不同类型提示用户确认）
        val conflicts = detectConflicts(allResults)
        notifyConflicts(conflicts, silent)

        // 逐码落库 + 站点学习（三路径共用管线）
        val saved = RecognitionPipeline.finalize(
            context = this@PickupCodeAccessibilityService,
            allResults = allResults,
            codeSources = codeSources,
            lines = ocrLines,
            allText = allText,
            fullAddress = address,
            rawSnippet = allText,
            screenshotPath = screenshotPath,
            repo = AppDatabase.getInstance(this@PickupCodeAccessibilityService).repository
        )
        // 通知：同码已存在(existed) → 重复提示；否则正常通知。与短信路径统一，避免重复截图时同码常驻通知堆叠。
        for (s in saved) {
            RecognitionPipeline.notifySaved(
                context = this@PickupCodeAccessibilityService,
                dupCountProvider = { AppDatabase.getInstance(this@PickupCodeAccessibilityService).repository.countDuplicateGroups() },
                code = s.code, type = s.type, source = s.source, id = s.id, existed = s.existed
            )
        }

        // 地图地址验证（finalize 之后才有 id，写回 geo 字段与分享路径一致）
        verifyMapAddress(address, settings, saved.map { it.id })

        // ⑦ 快递100 验证：识别到取件码时，用运单号反查取件码/地址作为标准答案，对照 OCR 结果（fire-and-forget）
        verifyWithKuaidi100(settings, allText, address, allResults)
    }

    /** ① 券码：解码内容加入 allResults；返回 true 表示存在券码（互斥标志）。 */
    private fun collectCouponResults(coupons: List<CouponDetector.CouponResult>, settings: AppPreferences.Settings,
                                     allResults: MutableList<Pair<String, CodeExtractor.CodeType>>,
                                     codeSources: MutableMap<String, String>): Boolean {
        var hasCoupon = false
        if (settings.enableCouponCodes) {
            for (c in coupons) {
                val v = c.rawValue?.trim()
                if (v.isNullOrBlank()) continue
                val key = "$v|${CodeExtractor.CodeType.coupon}"
                if (allResults.any { "${it.first}|${it.second}" == key }) continue
                allResults.add(v to CodeExtractor.CodeType.coupon)
                codeSources[v] = "券码"
                hasCoupon = true
            }
        }
        return hasCoupon
    }

    /** ② 有券码 / 未启用 AI / 无 API Key 时返回 null（此时 AI 不会运行）。 */
    private fun startAiExtract(allText: String, settings: AppPreferences.Settings, hasCoupon: Boolean): Deferred<AIExtractor.AIExtractResult>? {
        return if (!hasCoupon && settings.enableAI && settings.apiKey.isNotBlank()) {
            scope.async(Dispatchers.IO) {
                AIExtractor.extract(allText, settings.apiKey, settings.apiBaseUrl, settings.apiModel)
            }
        } else null
    }

    /** ③ 正则识别：按置信度阈值与类型开关过滤后追加到 allResults。 */
    private fun collectRegexResults(ocrLines: List<OCREngine.TextLine>, settings: AppPreferences.Settings,
                                    allResults: MutableList<Pair<String, CodeExtractor.CodeType>>,
                                    codeSources: MutableMap<String, String>) {
        val regexResults = CodeExtractor.extract(ocrLines, resources.displayMetrics.heightPixels, this, source = "screen")
        for (re in regexResults) {
            if (re.confidence >= settings.confidenceThreshold && isTypeEnabled(re.type, settings)) {
                allResults.add(re.code to re.type)
                codeSources[re.code] = re.source
            }
        }
    }

    /** ④ 合并 AI 结果：同码同 type 已有（正则或其它 AI 项）→ 跳过；否则加入。返回 aiErr（失败原因，供空结果提示用）。 */
    private suspend fun mergeAiResults(aiDeferred: Deferred<AIExtractor.AIExtractResult>?, settings: AppPreferences.Settings,
                                       allResults: MutableList<Pair<String, CodeExtractor.CodeType>>,
                                       codeSources: MutableMap<String, String>): String? {
        var aiErr: String? = null
        if (aiDeferred != null) {
            try {
                // 与短信/分享路径对齐：AI 最多等 8s，超时仅用正则结果，不拖死落库/通知
                val aiRes = kotlinx.coroutines.withTimeoutOrNull(8_000L) { aiDeferred.await() }
                if (aiRes == null) {
                    aiDeferred.cancel()
                    Log.d(TAG, "AI 超时未返回（预算 8000ms），仅用正则结果")
                    return "AI服务超时"
                }
                aiErr = aiRes.error
                if (aiRes.error != null) {
                    Log.w(TAG, "AI 识别失败: ${aiRes.error}")
                }
                for (ai in aiRes.results) {
                    if (!isTypeEnabled(ai.type, settings)) continue
                    val alreadySame = allResults.any { it.first == ai.code && it.second == ai.type }
                    if (alreadySame) continue
                    allResults.add(ai.code to ai.type)
                    codeSources.putIfAbsent(ai.code, ai.source)
                }
            } catch (e: Exception) {
                // Low-1: 协程取消异常必须向上传播，不能被吞掉
                if (e is kotlinx.coroutines.CancellationException) throw e
                aiErr = e.message ?: "AI同步异常"
                Log.w(TAG, "AI 结果合并异常: ${e.message}")
            }
        }
        return aiErr
    }

    /** 地图地址验证（async, fire-and-forget）：学习 + 定向写回 geo 字段（与分享路径一致）。 */
    private fun verifyMapAddress(address: String, settings: AppPreferences.Settings, savedIds: List<Long>) {
        if (settings.enableMapVerify && address.isNotBlank() && savedIds.isNotEmpty()) {
            scope.launch {
                PostVerifier.verifyMap(this@PickupCodeAccessibilityService, address, settings.amapApiKey.ifBlank { null }) { conf, fmtAddr ->
                    try {
                        PatternLearner.recordAddressVerified(this@PickupCodeAccessibilityService, address, conf)
                    } catch (e: Exception) {
                        Log.w(TAG, "recordAddressVerified failed: ${e.message}")
                    }
                    try {
                        val repo = AppDatabase.getInstance(this@PickupCodeAccessibilityService).repository
                        for (id in savedIds) {
                            repo.updateGeo(id, true, conf, fmtAddr ?: "")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "updateGeo failed: ${e.message}")
                    }
                }
            }
        }
    }

    /** ⑤ 无结果时提示（AI 失败带上原因）。返回 true 表示提前退出（后续步骤不再执行）。 */
    private fun notifyIfNoResult(allResults: List<Pair<String, CodeExtractor.CodeType>>, aiErr: String?,
                                 settings: AppPreferences.Settings, silent: Boolean, bmp: Bitmap?): Boolean {
        if (allResults.isNotEmpty()) return false
        bmp?.recycle()
        // Medium-2: 自动扫描时静默（仅日志），不弹"未识别"通知
        if (!silent) {
            if (settings.enableAI && settings.apiKey.isNotBlank()) {
                // PII/端点防护：AI 错误原文可能含 URL/端点细节，不直接进用户通知，
                // 只映射成类别文案（原始错误仍会经 mergeAiResults 落 Log.w 供排查）
                if (aiErr != null) showResult("未识别到取餐码/取件码 · ${categorizeAiError(aiErr)}")
                else showResult("未识别到取餐码/取件码")
            } else {
                showResult("未识别到取餐码/取件码")
            }
        }
        return true
    }

    /** AI 错误原文 → 用户可读类别文案（不进通知的原始细节只写日志）。 */
    private fun categorizeAiError(err: String): String {
        val e = err.lowercase()
        return when {
            e.contains("timeout") || e.contains("timed out") || e.contains("超时") -> "AI服务超时"
            e.contains("401") || e.contains("unauthorized") || e.contains("api key") || e.contains("invalid key") -> "AI密钥无效"
            e.contains("429") || e.contains("rate limit") || e.contains("too many") -> "AI请求过于频繁"
            e.contains("404") || e.contains("model") -> "AI模型不可用"
            e.contains("connect") || e.contains("network") || e.contains("socket") ||
                e.contains("unreachable") || e.contains("refused") || e.contains("resolve") -> "网络连接失败"
            else -> "AI识别失败"
        }
    }

    /** ⑥ 冲突检测：同码同时匹配取餐/取件类型时返回该码（提示用户确认）。 */
    private fun detectConflicts(allResults: List<Pair<String, CodeExtractor.CodeType>>): List<String> {
        val seen = mutableSetOf<String>()
        val conflicts = mutableListOf<String>()
        for ((code, type) in allResults) {
            val key = "$code|$type"
            if (key in seen) continue
            seen.add(key)
            val otherType = if (type == CodeExtractor.CodeType.pickup_food)
                CodeExtractor.CodeType.pickup_parcel else CodeExtractor.CodeType.pickup_food
            if ("$code|$otherType" in seen || allResults.any { it.first == code && it.second == otherType }) {
                conflicts.add(code)
            }
        }
        return conflicts
    }

    /** ⑥ 有冲突时通知用户自行判断（自动扫描静默）。 */
    private fun notifyConflicts(conflicts: List<String>, silent: Boolean) {
        if (conflicts.isNotEmpty() && !silent) {
            showResult("⚠️ 「${conflicts.joinToString("、")}」同时匹配取餐/取件类型，请进入App确认")
        }
    }

    /** ⑦ 快递100 验证：识别到取件码时，用运单号反查取件码/地址作为标准答案，对照 OCR 结果（fire-and-forget）。 */
    private fun verifyWithKuaidi100(settings: AppPreferences.Settings, allText: String, address: String,
                                    allResults: List<Pair<String, CodeExtractor.CodeType>>) {
        if (settings.enableKuaidi100 && settings.kuaidi100Key.isNotBlank()) {
            val trackingNum = BrandResolver.findOrderNumber(allText)
            if (trackingNum != null) {
                scope.launch {
                    val res = PostVerifier.verifyKuaidi100(
                        this@PickupCodeAccessibilityService, settings.kuaidi100Key, trackingNum,
                        allResults.map { it.first }
                    ) ?: return@launch
                    val pCode = res.pickUpCode ?: return@launch
                    // 若 OCR 未识别出地址，且 API 返回了标准地址，定向补全（不覆盖中间用户操作）
                    if (address.isBlank() && !res.pickUpAddress.isNullOrBlank()) {
                        val repo = AppDatabase.getInstance(this@PickupCodeAccessibilityService).repository
                        val rec = repo.findByCodeAndType(pCode, CodeExtractor.CodeType.pickup_parcel.name)
                        if (rec != null && rec.pickupAddress.isBlank()) {
                            repo.updatePickupAddress(rec.id, res.pickUpAddress)
                        }
                    }
                }
            }
        }
    }

    private fun isTypeEnabled(type: CodeExtractor.CodeType, settings: AppPreferences.Settings): Boolean =
        RecognitionPipeline.isTypeEnabled(type, settings)


    private fun saveScreenshot(bmp: Bitmap, timestamp: Long): String {
        try {
            // H4: 存 cacheDir（系统可自动清理），避免 filesDir 无限累积
            val dir = File(cacheDir, "screenshots")
            dir.mkdirs()
            File(dir, ".nomedia").createNewFile()
            val file = File(dir, "screenshot_$timestamp.jpg")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            return file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存截屏失败", e)
            return ""
        }
    }

    private fun showResult(msg: String) {
        mainHandler.post {
            val nm = getSystemService(android.app.NotificationManager::class.java) ?: return@post
            // 频道只需创建一次，但重复 create 是幂等的（同名频道会复用），保留以自取
            nm.createNotificationChannel(android.app.NotificationChannel(
                CHANNEL_ID, "结果", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            // M12: 结果提示用独立保留 id 段，避免与 CodeNotificationManager.safeId 空间冲突
            nm.notify(RESULT_NOTIFY_ID, NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("码上闪记").setContentText(msg)
                .setAutoCancel(true).setTimeoutAfter(3000).build())
        }
    }
}
