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
package cn.x.ac.kteasy.core.query

/*
 * 日期条件 token 的**语法形态**（图纸 03 §3 token 名录；【全景】§2「日期条件族」的机器可读镜像）。
 *
 * 本文件只承载「解析器读到的结构」，不做区间展开——把 token 解析成 `[from,to)` 绑定参数、并落地
 * 本地时区↔UTC 边界，是块3 的职责（同一 resolver 亦作块5 双库一致性 oracle 的内存参考实现）。
 *
 * 语义含糊处（`agoN`/`afterN` 的精确窗口、`thismay`/`everymay` 的 `may` 究竟指「年内某月」还是笔误）
 * 一律在块3 显式定义为**单一确定解释**、写进 KDoc 并在证据 §2 标注「待与产品现网行为核对」，
 * 绝不在本卡静默臆造——编译器与 oracle 共用同一解释即自洽（验收是二者逐条一致，非对外部真值）。
 */

/** 日历粒度（相对窗口与精确偏移共用）。 */
enum class CalendarUnit {
    DAY,
    MONTH,
    YEAR,
    HOUR,
}

/**
 * 相对「此刻」的方向（对应图纸 03 §3 的 last/next/ago/after 四前缀）。
 *
 * ⟨可逆⟩代拍语义（块3 落地为 `[from,to)`，此处仅锚定意图，精确定义见块3 KDoc + 证据 §2）：
 * - [LAST]：最近 N 单位 → `(now - N, now]`（含当下、向过去回溯一段）；
 * - [NEXT]：未来 N 单位 → `[now, now + N)`；
 * - [AGO]：N 前 → `[now - N, now - N + 1单位)`（命中「N 单位前那一整格」，如 3 前(天)＝前天的整天）；
 * - [AFTER]：N 后 → `[now + N, now + N + 1单位)`（同 [AGO] 的未来镜像）。
 */
enum class RelativeDirection {
    LAST,
    NEXT,
    AGO,
    AFTER,
}

/** `this<period>` 的粗粒度周期：本周/本月/本季/本年。 */
enum class ThisPeriod {
    WEEK,
    MONTH,
    QUARTER,
    YEAR,
}

/**
 * `this<suffix>:N` / `every<suffix>:N` 的刻度基准（后缀 → 枚举）。
 *
 * 解析期映射：`wed`→[WEEKDAY]（每周/本周的第 N 个星期几）、`month`→[MONTH_DAY]（每月/本月的第 N 号，
 * 月末钳制）、`may`→[YEAR_MONTH]（每年/本年的第 N 月，⟨可逆⟩待核对，见文件头注释）。
 */
enum class CycleScale {
    WEEKDAY,
    MONTH_DAY,
    YEAR_MONTH,
}

/** `exact<kind>±N` 的锚定粒度。 */
enum class ExactKind {
    DAY,
    MONTH,
    YEAR,
    HOUR,
}

/**
 * `range(a,b)` 的端点原始记法：解析期只保留原文（`now` / `±N<unit>` 偏移 / `yyyy-MM-dd[THH:mm:ss]` ISO），
 * 合法性与到 `[from,to)` 的换算在块3。端点值永不进 SQL 串——展开后是绑定参数（红线④）。
 */
data class DateBoundary(
    val raw: String,
)

/** 日期条件 token 的密封语法树（与 [Expr.Within] 一起构成 `within` 谓词的右值）。 */
sealed interface DateToken {
    /** `last/next/ago/after` + N + 单位（图纸 03 §3 的 `lastNd/m/y` 等四族）。 */
    data class Relative(
        val direction: RelativeDirection,
        val n: Int,
        val unit: CalendarUnit,
    ) : DateToken

    /** `range(a,b)`：闭/开界由块3 定义（约定 `[from, to)`）。 */
    data class Range(
        val from: DateBoundary,
        val to: DateBoundary,
    ) : DateToken

    /** `thisw/m/q/y`：本周期整体。 */
    data class This(
        val period: ThisPeriod,
    ) : DateToken

    /** `this<wed|month|may>:N`：本周期内第 N 刻度（单日，月末钳制）。 */
    data class ThisOn(
        val scale: CycleScale,
        val index: Int,
    ) : DateToken

    /** `every<wed|month|may>:N`：每逢第 N 刻度（跨周期集合 → 块3 展开为一组区间/谓词）。 */
    data class Every(
        val scale: CycleScale,
        val index: Int,
    ) : DateToken

    /** `exact<day|month|year|hour>±N`：相对某锚定时刻的正负偏移点。 */
    data class Exact(
        val kind: ExactKind,
        val signedOffset: Int,
    ) : DateToken
}
