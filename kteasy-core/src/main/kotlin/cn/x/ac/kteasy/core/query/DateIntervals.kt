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

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.IsoFields

/*
 * 日期条件族的**唯一语义源**（图纸 03 §3；块3）。区间类 token 在此展开为半开 `[from,to)`（本地时区 [now] 锚定、
 * 上层按字段类型转 UTC 存/绑）；循环类 token（every*）展开为 [DateResolution.Recurrence]，由块4 经 [DateOps] 渲染。
 *
 * ⟨可逆·语义待与 rebuild 现网核对，见证据 §2⟩：本文件是编译器与块5 内存 oracle 的**共同参照**——两执行路径
 * （SQL 区间参数 vs 内存逐条判定）共用同一 resolver，故一致性测证的是「参数化/时区/粒度落库正确」，非对外部日历真值。
 * 具体档位语义（LAST 是否含今天、周一起算、月末钳制、ago/after 单格、exact 以本刻为锚）按下方 KDoc 定义为单一确定解。
 */
sealed interface DateResolution {
    /** 半开时间窗 `[from, to)`（本地时区）。 */
    data class Window(
        val from: ZonedDateTime,
        val to: ZonedDateTime,
    ) : DateResolution

    /** 循环谓词：命中「星期几==index / 月内日==index / 年内月==index」（块4 用 DateOps 提取分量比较）。 */
    data class Recurrence(
        val scale: CycleScale,
        val index: Int,
    ) : DateResolution
}

object DateIntervals {
    /** 解析 token。[now] = 用户本地时区当前时刻（注入以保证块5 测可复现）。 */
    fun resolve(
        token: DateToken,
        now: ZonedDateTime,
    ): DateResolution =
        when (token) {
            is DateToken.Relative -> relative(token, now)
            is DateToken.Range -> range(token, now)
            is DateToken.This -> thisPeriod(token.period, now)
            is DateToken.ThisOn -> thisOn(token.scale, token.index, now)
            is DateToken.Every -> DateResolution.Recurrence(token.scale, token.index)
            is DateToken.Exact -> exact(token, now)
        }

    private fun relative(
        t: DateToken.Relative,
        now: ZonedDateTime,
    ): DateResolution.Window {
        val base = floor(now, t.unit)
        val from: ZonedDateTime
        val to: ZonedDateTime
        when (t.direction) {
            // 最近 N 期：含当前期，向前数满 N 期 → [floor-(N-1)期, floor+1期)
            RelativeDirection.LAST -> {
                from = plus(base, -(t.n - 1), t.unit)
                to = plus(base, 1, t.unit)
            }

            // 未来 N 期：[floor, floor+N期)
            RelativeDirection.NEXT -> {
                from = base
                to = plus(base, t.n, t.unit)
            }

            // N 前：往前第 N 个整期单格 [floor-N期, floor-(N-1)期)
            RelativeDirection.AGO -> {
                from = plus(base, -t.n, t.unit)
                to = plus(from, 1, t.unit)
            }

            // N 后：往后第 N 个整期单格 [floor+N期, floor+(N+1)期)
            RelativeDirection.AFTER -> {
                from = plus(base, t.n, t.unit)
                to = plus(from, 1, t.unit)
            }
        }
        return DateResolution.Window(from, to)
    }

    private fun exact(
        t: DateToken.Exact,
        now: ZonedDateTime,
    ): DateResolution.Window {
        val unit =
            when (t.kind) {
                ExactKind.DAY -> CalendarUnit.DAY
                ExactKind.MONTH -> CalendarUnit.MONTH
                ExactKind.YEAR -> CalendarUnit.YEAR
                ExactKind.HOUR -> CalendarUnit.HOUR
            }
        val base = floor(now, unit)
        val from = plus(base, t.signedOffset, unit)
        return DateResolution.Window(from, plus(from, 1, unit))
    }

    private fun thisPeriod(
        period: ThisPeriod,
        now: ZonedDateTime,
    ): DateResolution.Window =
        when (period) {
            ThisPeriod.WEEK -> {
                val s = startOfWeek(now)
                DateResolution.Window(s, s.plusWeeks(1))
            }

            ThisPeriod.MONTH -> {
                val s = startOfMonth(now)
                DateResolution.Window(s, s.plusMonths(1))
            }

            ThisPeriod.QUARTER -> {
                val q = now.get(IsoFields.DAY_OF_QUARTER)
                val s = now.minusDays((q - 1).toLong()).toLocalDate().atStartOfDay(now.zone)
                DateResolution.Window(s, s.plusMonths(3))
            }

            ThisPeriod.YEAR -> {
                val s = startOfYear(now)
                DateResolution.Window(s, s.plusYears(1))
            }
        }

    private fun thisOn(
        scale: CycleScale,
        index: Int,
        now: ZonedDateTime,
    ): DateResolution.Window =
        when (scale) {
            CycleScale.WEEKDAY -> {
                require(index in 1..7) { "星期序须 1..7" }
                val s = startOfWeek(now).plusDays((index - 1).toLong())
                DateResolution.Window(s, s.plusDays(1))
            }

            CycleScale.MONTH_DAY -> {
                val month = startOfMonth(now)
                val maxDay = month.toLocalDate().lengthOfMonth()
                val day = index.coerceIn(1, maxDay) // 月末钳制
                val s = month.withDayOfMonth(day)
                DateResolution.Window(s, s.plusDays(1))
            }

            CycleScale.YEAR_MONTH -> {
                require(index in 1..12) { "月份须 1..12" }
                val s = startOfYear(now).withMonth(index)
                DateResolution.Window(s, s.plusMonths(1))
            }
        }

    private fun range(
        t: DateToken.Range,
        now: ZonedDateTime,
    ): DateResolution.Window {
        val a = boundary(t.from.raw, now)
        val b = boundary(t.to.raw, now)
        return if (a.isBefore(b)) DateResolution.Window(a, b) else DateResolution.Window(b, a)
    }

    /** range 端点：`now` | ISO 日期/时间串 | 相对词（lastNd/m/y/h，取该期起点）。 */
    private fun boundary(
        raw: String,
        now: ZonedDateTime,
    ): ZonedDateTime {
        if (raw == "now") return now
        RELATIVE.matchEntire(raw)?.let { m ->
            val unit = unitOf(m.groupValues[3])
            return floor(now, unit).let { b ->
                if (m.groupValues[1] == "last" || m.groupValues[1] == "ago") plus(b, -m.groupValues[2].toInt(), unit) else plus(b, m.groupValues[2].toInt(), unit)
            }
        }
        val local: LocalDateTime =
            if (raw.length <= 10) LocalDate.parse(raw).atStartOfDay() else LocalDateTime.parse(raw.removeSuffix("Z"))
        return local.atZone(now.zone)
    }

    // ---------- 时钟/日历工具（全部 ISO、时区随 now） ----------

    private fun floor(
        now: ZonedDateTime,
        unit: CalendarUnit,
    ): ZonedDateTime =
        when (unit) {
            CalendarUnit.HOUR -> now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            CalendarUnit.DAY -> now.toLocalDate().atStartOfDay(now.zone)
            CalendarUnit.MONTH -> startOfMonth(now)
            CalendarUnit.YEAR -> startOfYear(now)
        }

    private fun plus(
        base: ZonedDateTime,
        amount: Int,
        unit: CalendarUnit,
    ): ZonedDateTime =
        when (unit) {
            CalendarUnit.HOUR -> base.plusHours(amount.toLong())
            CalendarUnit.DAY -> base.plusDays(amount.toLong())
            CalendarUnit.MONTH -> base.plusMonths(amount.toLong())
            CalendarUnit.YEAR -> base.plusYears(amount.toLong())
        }

    private fun startOfDay(
        z: ZonedDateTime,
    ): ZonedDateTime = z.toLocalDate().atStartOfDay(z.zone)

    private fun startOfWeek(
        z: ZonedDateTime,
    ): ZonedDateTime = startOfDay(z).minusDays((z.dayOfWeek.value - 1).toLong()) // ISO 周一

    private fun startOfMonth(
        z: ZonedDateTime,
    ): ZonedDateTime = z.toLocalDate().withDayOfMonth(1).atStartOfDay(z.zone)

    private fun startOfYear(
        z: ZonedDateTime,
    ): ZonedDateTime = z.toLocalDate().withDayOfYear(1).atStartOfDay(z.zone)

    private fun unitOf(
        s: String,
    ): CalendarUnit =
        when (s) {
            "d" -> CalendarUnit.DAY
            "m" -> CalendarUnit.MONTH
            "y" -> CalendarUnit.YEAR
            else -> CalendarUnit.HOUR
        }

    /** 半开区间成员判定（供块5 内存 oracle 直接复用，确保两路径同源）。 */
    fun windowContains(
        from: ZonedDateTime,
        to: ZonedDateTime,
        value: ZonedDateTime,
    ): Boolean = !value.isBefore(from) && value.isBefore(to)

    private val RELATIVE = Regex("^(last|next|ago|after)(\\d+)([dmyh])$")
}

/** 便捷：取某时区的「此刻」（秒精度，纳秒清零以对齐存库精度）。测试/运行期注入固定 [now] 以保证可复现。 */
fun zonedNow(
    zone: ZoneId,
): ZonedDateTime = ZonedDateTime.now(zone).withNano(0)
