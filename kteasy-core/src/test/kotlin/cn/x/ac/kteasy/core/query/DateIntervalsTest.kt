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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 块3 日期族语义 L1：固定 `now=2026-03-18T10:00 +08`（该日恰为**周三** ISO=3，天然对齐卡面「每周3」；
 * 无 DST 的 Asia/Shanghai 保证区间边界可复现）。逐 token 断言半开区间 / [DateResolution.Recurrence]。
 */
class DateIntervalsTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 3, 18, 10, 0, 0, 0, zone)

    private fun z(
        y: Int,
        mo: Int,
        d: Int,
        h: Int = 0,
    ): ZonedDateTime = ZonedDateTime.of(y, mo, d, h, 0, 0, 0, zone)

    private fun window(
        r: DateResolution,
    ): DateResolution.Window = r as DateResolution.Window

    @Test
    fun `最近7天含今天向前满7日`() {
        val w = window(DateIntervals.resolve(DateToken.Relative(RelativeDirection.LAST, 7, CalendarUnit.DAY), now))
        assertThat(w.from).isEqualTo(z(2026, 3, 12))
        assertThat(w.to).isEqualTo(z(2026, 3, 19))
    }

    @Test
    fun `未来3月起于本月初`() {
        val w = window(DateIntervals.resolve(DateToken.Relative(RelativeDirection.NEXT, 3, CalendarUnit.MONTH), now))
        assertThat(w.from).isEqualTo(z(2026, 3, 1))
        assertThat(w.to).isEqualTo(z(2026, 6, 1))
    }

    @Test
    fun `1年前为整年单格`() {
        val w = window(DateIntervals.resolve(DateToken.Relative(RelativeDirection.AGO, 1, CalendarUnit.YEAR), now))
        assertThat(w.from).isEqualTo(z(2025, 1, 1))
        assertThat(w.to).isEqualTo(z(2026, 1, 1))
    }

    @Test
    fun `本周为周一至下周一`() {
        val w = window(DateIntervals.resolve(DateToken.This(ThisPeriod.WEEK), now))
        assertThat(w.from).isEqualTo(z(2026, 3, 16)) // 周一
        assertThat(w.to).isEqualTo(z(2026, 3, 23))
    }

    @Test
    fun `本周周一为单日窗`() {
        val w = window(DateIntervals.resolve(DateToken.ThisOn(CycleScale.WEEKDAY, 1), now))
        assertThat(w.from).isEqualTo(z(2026, 3, 16))
        assertThat(w.to).isEqualTo(z(2026, 3, 17))
    }

    @Test
    fun `本月某日月末钳制`() {
        val w = window(DateIntervals.resolve(DateToken.ThisOn(CycleScale.MONTH_DAY, 31), now))
        assertThat(w.from).isEqualTo(z(2026, 3, 31)) // 3 月有 31 日
        val feb = ZonedDateTime.of(2026, 2, 10, 9, 0, 0, 0, zone)
        val clamped = window(DateIntervals.resolve(DateToken.ThisOn(CycleScale.MONTH_DAY, 31), feb))
        assertThat(clamped.from).isEqualTo(z(2026, 2, 28)) // 2 月钳到 28
    }

    @Test
    fun `每年月为整月窗`() {
        val w = window(DateIntervals.resolve(DateToken.ThisOn(CycleScale.YEAR_MONTH, 5), now))
        assertThat(w.from).isEqualTo(z(2026, 5, 1))
        assertThat(w.to).isEqualTo(z(2026, 6, 1))
    }

    @Test
    fun `每周3展开为循环谓词而非区间`() {
        // 卡面点名的「每周3」——不是 [from,to)，而是按星期几==3 的循环命中（块4 经 DateOps 渲染）
        assertThat(DateIntervals.resolve(DateToken.Every(CycleScale.WEEKDAY, 3), now)).isEqualTo(
            DateResolution.Recurrence(CycleScale.WEEKDAY, 3),
        )
    }

    @Test
    fun `指定负5时为整点单格`() {
        // 卡面「指定-5时」= exacthour-5：本小时前第 5 个整点那一小时 [05:00,06:00)
        val w = window(DateIntervals.resolve(DateToken.Exact(ExactKind.HOUR, -5), now))
        assertThat(w.from).isEqualTo(z(2026, 3, 18, 5))
        assertThat(w.to).isEqualTo(z(2026, 3, 18, 6))
    }

    @Test
    fun `range 两端 ISO 展开`() {
        val w = window(DateIntervals.resolve(DateToken.Range(DateBoundary("2026-03-01"), DateBoundary("2026-03-20")), now))
        assertThat(w.from).isEqualTo(z(2026, 3, 1))
        assertThat(w.to).isEqualTo(z(2026, 3, 20))
    }

    @Test
    fun `成员判定半开含前不含后`() {
        val w = window(DateIntervals.resolve(DateToken.Relative(RelativeDirection.LAST, 7, CalendarUnit.DAY), now))
        assertThat(DateIntervals.windowContains(w.from, w.to, z(2026, 3, 12))).isTrue() // 含起点
        assertThat(DateIntervals.windowContains(w.from, w.to, z(2026, 3, 19))).isFalse() // 不含终点
        assertThat(DateIntervals.windowContains(w.from, w.to, z(2026, 3, 11))).isFalse()
    }
}
