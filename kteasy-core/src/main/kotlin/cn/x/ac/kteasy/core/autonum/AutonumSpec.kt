/*
 * Copyright 2026 阿杰很厉害 <master@x-ac.cn>. SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.x.ac.kteasy.core.autonum

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 自动编号模板（步骤卡 M1-07 设计要点 4；模块图纸 01 §1 `md_autonum_rule.segments_json`）。
 *
 * 本文件是**纯函数半区**：模板段的解析、周期键推导、号串渲染都在此，零 IO、零第三方依赖、DB-free 可单测。
 * 取号的「计数器推进」是 IO（依赖 `kteasy_autonum_seq` upsert），归 server 装配层，只消费本包的
 * [AutonumSpec.periodKey]（决定哪一行计数）与 [AutonumSpec.render]（把序号拼成最终号串）。
 *
 * 设计口径（决策台 P13）：
 * - 一条规则**恰好含一个 SEQ 段**（流水）；缺或多都在 [parse] 期硬失败——没有 SEQ 就没有可推进的计数器，
 *   多个 SEQ 语义未定义，宁可拒绝也不猜（与 ext「脏数据抛错不降级」同一纪律）。
 * - `reset` ∈ DAY|MONTH|YEAR|NONE（缺省 NONE＝连续流水）。周期键按 **UTC 墙钟日** 切，绑 [WallClock] 唯一时间出口，
 *   与写侧其它时间列同轴，杜绝跨库时区分叉。
 * - SEQ 的 `width` 是**补零最小宽度、非硬上限**：序号增长越过宽度时如实变宽，绝不截断成假号。
 */
enum class ResetCycle { DAY, MONTH, YEAR, NONE }

/** 模板段（闭合四类）。 */
sealed interface AutonumSegment

/** 固定文本段。 */
data class TextSegment(
    val text: String,
) : AutonumSegment

/** 日期格式段：按 [pattern] 格式化编号生成当日的 UTC 墙钟日期。 */
data class DateSegment(
    val pattern: String,
) : AutonumSegment {
    // 构造即编译，坏 pattern 当场抛（parse 期已先校验给清晰错误，直连构造亦不放过）。
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT)

    fun format(date: LocalDate): String = date.format(formatter)
}

/** 流水段：宽度、起点、周期重置档。 */
data class SeqSegment(
    val width: Int,
    val start: Long,
    val reset: ResetCycle,
) : AutonumSegment

/** 字段变量段：渲染时取同记录里 [api] 字段的当前值。 */
data class FieldSegment(
    val api: String,
) : AutonumSegment

/**
 * 已解析的编号模板。[segments] 保留声明顺序（渲染按序拼接）。
 */
class AutonumSpec private constructor(
    val segments: List<AutonumSegment>,
) {
    /** 唯一的流水段（构造期已保证恰有一个）。 */
    val seq: SeqSegment = segments.filterIsInstance<SeqSegment>().single()

    /** 该模板在给定 UTC 墙钟日下对应的计数桶键：NONE→空串、DAY→yyyyMMdd、MONTH→yyyyMM、YEAR→yyyy。 */
    fun periodKey(date: LocalDate): String =
        when (seq.reset) {
            ResetCycle.NONE -> ""
            ResetCycle.DAY -> date.format(DAY_KEY)
            ResetCycle.MONTH -> date.format(MONTH_KEY)
            ResetCycle.YEAR -> date.format(YEAR_KEY)
        }

    /**
     * 把已推进到的序号 [seqValue] 拼成最终号串。
     *
     * @param date 编号生成当日的 UTC 墙钟日期（供 DATE 段与调试）
     * @param fieldValues 字段变量段取值表（key＝字段 api_name）；缺键按空串处理（必填性由写通道裁决，非本包职责）
     */
    fun render(
        seqValue: Long,
        date: LocalDate,
        fieldValues: Map<String, String>,
    ): String {
        val padded = seqValue.toString().padStart(seq.width, '0')
        val sb = StringBuilder()
        for (s in segments) {
            when (s) {
                is TextSegment -> sb.append(s.text)
                is DateSegment -> sb.append(s.format(date))
                is SeqSegment -> sb.append(padded)
                is FieldSegment -> sb.append(fieldValues[s.api] ?: "")
            }
        }
        return sb.toString()
    }

    companion object {
        private val DAY_KEY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
        private val MONTH_KEY = DateTimeFormatter.ofPattern("yyyyMM", Locale.ROOT)
        private val YEAR_KEY = DateTimeFormatter.ofPattern("yyyy", Locale.ROOT)

        private val KINDS = setOf("TEXT", "DATE", "SEQ", "FIELD")
        private val RESETS = mapOf("DAY" to ResetCycle.DAY, "MONTH" to ResetCycle.MONTH, "YEAR" to ResetCycle.YEAR, "NONE" to ResetCycle.NONE)

        /**
         * 严格解析 `segments_json`。文法：JSON 对象数组，每对象含 `kind`（四类之一）与该类必填键。
         * 未知 kind、未知键、缺必填键、坏数字、非数组、SEQ 段非唯一——一律抛 [IllegalArgumentException]（人话消息），
         * 由元数据保存/加载处翻成 410（改载荷可自救）。
         */
        fun parse(segmentsJson: String): AutonumSpec {
            val raw = SegmentsJson.read(segmentsJson)
            val segments = raw.map { obj -> objToSegment(obj) }
            require(segments.isNotEmpty()) { "编号模板不能为空数组" }
            val seqCount = segments.count { it is SeqSegment }
            require(seqCount == 1) { "编号模板必须且仅含一个 SEQ 段，实际 $seqCount 个" }
            return AutonumSpec(segments)
        }

        private fun objToSegment(obj: Map<String, Any>): AutonumSegment {
            val kind = obj["kind"] as? String ?: error("编号段缺 kind")
            // 逐类只认白名单键：出现未声明键＝配置错误，硬失败不静默（防野段悄悄改变号形）。
            val allowed =
                when (kind) {
                    "TEXT" -> setOf("kind", "text")
                    "DATE" -> setOf("kind", "pattern")
                    "SEQ" -> setOf("kind", "width", "start", "reset")
                    "FIELD" -> setOf("kind", "api")
                    else -> throw IllegalArgumentException("未知编号段 kind：$kind（允许 $KINDS）")
                }
            val extra = obj.keys - allowed
            require(extra.isEmpty()) { "编号段 $kind 含未知键 $extra" }
            return when (kind) {
                "TEXT" -> {
                    val text = obj["text"] as? String
                    require(text != null && text.isNotEmpty()) { "TEXT 段的 text 不能为空" }
                    TextSegment(text)
                }

                "DATE" -> {
                    val pattern = obj["pattern"] as? String
                    require(!pattern.isNullOrBlank()) { "DATE 段缺 pattern" }
                    // 编译校验：坏 pattern 当场拒绝（消息带上原始 pattern 供人话回显）。
                    try {
                        DateTimeFormatter.ofPattern(pattern, Locale.ROOT)
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("DATE 段 pattern 非法：$pattern", e)
                    }
                    DateSegment(pattern)
                }

                "SEQ" -> {
                    val width = (obj["width"] as? Number)?.toInt()
                    require(width != null && width >= 1) { "SEQ 段 width 必须为正整数" }
                    val start = (obj["start"] as? Number)?.toLong()
                    require(start != null && start >= 0) { "SEQ 段 start 必须为非负整数" }
                    val reset =
                        (obj["reset"] as? String)?.let { r ->
                            RESETS[r] ?: throw IllegalArgumentException("SEQ 段 reset 非法：$r（允许 ${RESETS.keys}）")
                        } ?: ResetCycle.NONE
                    SeqSegment(width, start, reset)
                }

                else -> {
                    // FIELD
                    val api = obj["api"] as? String
                    require(!api.isNullOrBlank()) { "FIELD 段缺 api" }
                    FieldSegment(api)
                }
            }
        }
    }
}
