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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 编号模板纯函数单测（步骤卡 M1-07 块 1 单元②）。锁四件事：
 * ① 四类段的解析与渲染按声明序拼接；
 * ② period_key 按 UTC 墙钟日切档（含跨年/跨月边界），NONE 恒空串；
 * ③ 序号补零是最小宽度、溢出如实变宽（绝不截断成假号）；
 * ④ 一切配置错误（空/零或多 SEQ/未知 kind/未知键/小数/更深嵌套/尾垃圾/缺必填/坏 pattern）
 *    在 parse 期硬失败——脏模板宁可保存即拒，也不静默产出漂移的号形。
 */
class AutonumSpecTest {
    private val d2026 = LocalDate.of(2026, 9, 24)

    @Test
    fun `四段按声明序渲染`() {
        val spec =
            AutonumSpec.parse(
                """
                [
                  {"kind":"TEXT","text":"INV-"},
                  {"kind":"FIELD","api":"dept"},
                  {"kind":"DATE","pattern":"yyyy-MM-dd"},
                  {"kind":"SEQ","width":4,"start":1,"reset":"DAY"}
                ]
                """.trimIndent(),
            )
        val out = spec.render(seqValue = 7, date = d2026, fieldValues = mapOf("dept" to "SH"))
        // 段之间直接拼接（分隔符由段自带，如 TEXT="INV-"、DATE 内含 "-"）；这正是模板把分隔交给配置的用意。
        assertEquals("INV-SH2026-09-240007", out)
    }

    @Test
    fun `period_key 按 reset 档切 UTC 墙钟日`() {
        fun key(
            reset: String,
            date: LocalDate,
        ): String {
            val spec = AutonumSpec.parse("""[{"kind":"SEQ","width":3,"start":1,"reset":"$reset"}]""")
            return spec.periodKey(date)
        }
        assertEquals("", key("NONE", d2026))
        assertEquals("20260924", key("DAY", d2026))
        assertEquals("202609", key("MONTH", d2026))
        assertEquals("2026", key("YEAR", d2026))
        // 跨年/跨月边界：2027-01-01 的三档键各自归零到新周期。
        val y2027 = LocalDate.of(2027, 1, 1)
        assertEquals("20270101", key("DAY", y2027))
        assertEquals("202701", key("MONTH", y2027))
        assertEquals("2027", key("YEAR", y2027))
    }

    @Test
    fun `reset 缺省即 NONE`() {
        val spec = AutonumSpec.parse("""[{"kind":"SEQ","width":2,"start":1}]""")
        assertEquals(ResetCycle.NONE, spec.seq.reset)
        assertEquals("", spec.periodKey(d2026))
    }

    @Test
    fun `补零是最小宽度 溢出如实变宽不截断`() {
        val spec = AutonumSpec.parse("""[{"kind":"TEXT","text":"#"},{"kind":"SEQ","width":2,"start":1}]""")
        assertEquals("#01", spec.render(1, d2026, emptyMap()))
        assertEquals("#99", spec.render(99, d2026, emptyMap()))
        assertEquals("#100", spec.render(100, d2026, emptyMap())) // 越过宽度变宽，非截成 "00"
        assertEquals("#12345", spec.render(12345, d2026, emptyMap()))
    }

    @Test
    fun `字段变量缺键按空串`() {
        val spec = AutonumSpec.parse("""[{"kind":"FIELD","api":"x"},{"kind":"SEQ","width":1,"start":1}]""")
        assertEquals("5", spec.render(5, d2026, emptyMap()))
        assertEquals("AB5", spec.render(5, d2026, mapOf("x" to "AB")))
    }

    @Test
    fun `空数组被拒`() {
        assertFailsWith<IllegalArgumentException> { AutonumSpec.parse("[]") }
    }

    @Test
    fun `零个 SEQ 被拒`() {
        assertFailsWith<IllegalArgumentException> { AutonumSpec.parse("""[{"kind":"TEXT","text":"X"}]""") }
    }

    @Test
    fun `多个 SEQ 被拒`() {
        val two = """[{"kind":"SEQ","width":2,"start":1},{"kind":"SEQ","width":3,"start":1}]"""
        assertFailsWith<IllegalArgumentException> { AutonumSpec.parse(two) }
    }

    @Test
    fun `未知 kind 未知键 缺必填 坏数字 更深嵌套 尾垃圾全部硬拒`() {
        val cases =
            listOf(
                """[{"kind":"WAT","text":"x"},{"kind":"SEQ","width":1,"start":1}]""", // 未知 kind
                """[{"kind":"TEXT","text":"x","oops":1},{"kind":"SEQ","width":1,"start":1}]""", // 未知键
                """[{"kind":"DATE","pattern":"yyyy"},{"kind":"SEQ","width":1,"start":1}]""", // 好，反例参考
                """[{"kind":"FIELD"},{"kind":"SEQ","width":1,"start":1}]""", // FIELD 缺 api
                """[{"kind":"SEQ","width":1.5,"start":1}]""", // 小数
                """[{"kind":"SEQ","width":1,"start":1,"reset":"WEEK"}]""", // 非法 reset
                """[{"kind":"TEXT","text":{"deep":1}},{"kind":"SEQ","width":1,"start":1}]""", // 更深嵌套
                """[{"kind":"SEQ","width":1,"start":1}] trailing""", // 尾垃圾
                """{"kind":"SEQ"}""", // 顶层非数组
            )
        // 第 3 例是「应通过」的对照，其余均应抛。
        for ((idx, c) in cases.withIndex()) {
            if (idx == 2) {
                AutonumSpec.parse(c)
            } else {
                assertFailsWith<IllegalArgumentException> { AutonumSpec.parse(c) }
            }
        }
    }

    @Test
    fun `坏 pattern 在 parse 期即拒`() {
        // 未闭合的单引号＝DateTimeFormatter.ofPattern 必抛 IllegalArgumentException（确定非法，不赌字母表）。
        assertFailsWith<IllegalArgumentException> {
            AutonumSpec.parse("""[{"kind":"DATE","pattern":"'YYYY"},{"kind":"SEQ","width":1,"start":1}]""")
        }
    }
}
