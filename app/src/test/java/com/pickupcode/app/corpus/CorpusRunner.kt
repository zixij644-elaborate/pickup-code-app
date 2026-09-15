package com.pickupcode.app.corpus

import com.pickupcode.app.extractor.AddressExtractor
import com.pickupcode.app.extractor.CodeExtractor

/** 单条语料的执行结果与差异说明。 */
data class CorpusResult(
    val fixture: CorpusFixture,
    val gatedByFinancialNoise: Boolean,
    val actualCodes: List<ExpectedCode>,
    val actualAddress: String,
    val actualStation: String,
    val actualCabinet: String?,
    val actualFrom: String,
    val failures: List<String>
) {
    val passed: Boolean get() = failures.isEmpty()
}

/**
 * 语料执行器：**镜像生产入口**跑一遍识别，然后比对期望。
 *
 * 注意与生产的一致性：
 * - 先过金融/支付噪音闸门（三路径调用方都会先判 [CodeExtractor.isFinancialNoise]）
 * - `context = null`：不加载自学习规则、不写学习统计——语料必须确定性可复现
 */
object CorpusRunner {

    fun run(f: CorpusFixture): CorpusResult {
        val failures = mutableListOf<String>()
        val gated = CodeExtractor.isFinancialNoise(f.allText)
        val results = if (gated) {
            emptyList()
        } else {
            CodeExtractor.extract(f.lines, f.screenHeight, context = null, source = f.source)
        }
        val actual = results.map { ExpectedCode(it.code, it.type) }
        val loc = AddressExtractor.extractLocation(f.lines, f.allText)

        // 召回：期望码必须全部出现
        for (exp in f.expectedCodes) {
            if (actual.none { it.code == exp.code && it.type == exp.type }) {
                failures += "漏识别 ${exp.code}(${exp.type})；实际=${fmt(actual)}" +
                    if (gated) "（被金融闸门拦截）" else ""
            }
        }
        // 精确：不应有多余码（除非 allow-extra）
        if (!f.allowExtraCodes) {
            for (a in actual) {
                if (f.expectedCodes.none { it.code == a.code && it.type == a.type }) {
                    failures += "多识别 ${a.code}(${a.type})"
                }
            }
        }
        // 禁用串（子串/残码误报的显式断言）
        for (fb in f.forbiddenCodes) {
            if (actual.any { it.code == fb }) failures += "误报禁用码 '$fb'"
        }
        f.expectedAddress?.let {
            if (!loc.fullAddress.contains(it)) failures += "地址不符：期望含[$it]，实际=[${loc.fullAddress}]"
        }
        f.expectedStation?.let {
            if (!loc.stationName.contains(it)) failures += "站点不符：期望含[$it]，实际=[${loc.stationName}]"
        }
        f.expectedCabinet?.let {
            if (loc.cabinetNumber != it) failures += "柜号不符：期望[$it]，实际=[${loc.cabinetNumber}]"
        }
        f.expectedFrom?.let {
            if (loc.addrFrom != it) failures += "命中步骤不符：期望[$it]，实际=[${loc.addrFrom}]"
        }
        for (ca in f.expectedCodeAddresses) {
            val got = AddressExtractor.extractAddressForCode(f.lines, ca.code)
            if (!got.contains(ca.address)) {
                failures += "码 ${ca.code} 窗口地址不符：期望含[${ca.address}]，实际=[$got]"
            }
        }
        for (cs in f.expectedCodeSources) {
            val got = results.firstOrNull { it.code == cs.code }
            if (got == null) {
                failures += "码 ${cs.code} 未识别，无法核对品牌（期望 ${cs.source}）"
            } else if (!got.source.contains(cs.source)) {
                failures += "码 ${cs.code} 品牌不符：期望含[${cs.source}]，实际=[${got.source}]"
            }
        }
        return CorpusResult(
            fixture = f, gatedByFinancialNoise = gated, actualCodes = actual,
            actualAddress = loc.fullAddress, actualStation = loc.stationName,
            actualCabinet = loc.cabinetNumber, actualFrom = loc.addrFrom, failures = failures
        )
    }

    private fun fmt(list: List<ExpectedCode>): String =
        if (list.isEmpty()) "空" else list.joinToString { "${it.code}(${it.type})" }

    /** 聚合指标：码 TP/FP/FN + 全屏地址命中率。 */
    data class Metrics(
        val total: Int, val passed: Int,
        val tp: Int, val fp: Int, val fn: Int,
        val addrTotal: Int, val addrPassed: Int
    ) {
        val precision: Double get() = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
        val recall: Double get() = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn)
    }

    fun metrics(results: List<CorpusResult>): Metrics {
        var tp = 0; var fp = 0; var fn = 0; var addrTotal = 0; var addrPassed = 0
        for (r in results) {
            val exp = r.fixture.expectedCodes
            for (e in exp) {
                if (r.actualCodes.any { it.code == e.code && it.type == e.type }) tp++ else fn++
            }
            for (a in r.actualCodes) {
                if (exp.none { it.code == a.code && it.type == a.type }) fp++
            }
            r.fixture.expectedAddress?.let {
                addrTotal++
                if (r.actualAddress.contains(it)) addrPassed++
            }
        }
        return Metrics(results.size, results.count { it.passed }, tp, fp, fn, addrTotal, addrPassed)
    }
}
