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
package cn.x.ac.kteasy.core.meta

import cn.x.ac.kteasy.core.kernel.Ulid
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * 步骤卡 M1-04 块 4 · 字段类型注册表 property 大矩阵（L1 纯函数面，不连库）。
 *
 * 每型 200 随机合法值：`validate` 100% 放行 + `normalize` 幂等（`normalize∘normalize == normalize`）+ 归一后仍合法；
 * 每型 50 随机非法值：`validate` 100% 拒绝（仅对「有类型级格式校验」的型有意义；无格式校验的型断言其 validate 恒放行）。
 *
 * 生成器 `when` 穷举 [LogicalType] 全枚举，编译器逼覆盖、不漏型；ext→真列的 encode/decode 四象限（pg/mysql 手写 SQL 直查）另属块 4 的 IT，不在本类。
 */
class FieldTypePropertyTest {
    // 固定种子：可复现（失败能从零重跑同序列定位）。
    private val rnd = Random(0x2026_0921)

    private fun legal(t: LogicalType): String =
        when (t) {
            LogicalType.TEXT, LogicalType.TEXTAREA -> "客户" + letters(1 + rnd.nextInt(6))
            LogicalType.PHONE ->
                when (rnd.nextInt(3)) {
                    0 -> "1" + (3 + rnd.nextInt(7)) + digits(9) // 大陆手机
                    1 -> "0" + digits(2 + rnd.nextInt(2)) + "-" + digits(8) // 固话
                    else -> "+" + digits(5 + rnd.nextInt(11)) // 国际
                }
            LogicalType.EMAIL -> letters(3 + rnd.nextInt(5)) + "@" + letters(2 + rnd.nextInt(4)) + "." + letters(2 + rnd.nextInt(3))
            LogicalType.URL -> (if (rnd.nextBoolean()) "https://" else "http://") + letters(3 + rnd.nextInt(6)) + ".cn/p" + digits(3)
            LogicalType.NUMBER -> (if (rnd.nextBoolean()) "-" else "") + digits(1 + rnd.nextInt(9))
            LogicalType.DECIMAL -> (if (rnd.nextBoolean()) "-" else "") + digits(1 + rnd.nextInt(4)) + if (rnd.nextBoolean()) "." + digits(2) else ""
            LogicalType.AUTONUM, LogicalType.SYSTEM, LogicalType.PICKLIST -> "v" + digits(4)
            LogicalType.DATE -> "20%02d-%02d-%02d".format(rnd.nextInt(20), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28))
            LogicalType.DATETIME -> "20%02d-%02d-%02dT%02d:%02d:%02dZ".format(rnd.nextInt(20), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28), rnd.nextInt(24), rnd.nextInt(60), rnd.nextInt(60))
            LogicalType.TIME -> "%02d:%02d".format(rnd.nextInt(24), rnd.nextInt(60))
            LogicalType.MULTISELECT, LogicalType.TAGS -> jsonStrArray(rnd.nextInt(1, 5))
            LogicalType.N2N -> jsonStrArray(rnd.nextInt(1, 4)) { Ulid.next() }
            LogicalType.DICT -> (0..rnd.nextInt(4)).joinToString("/") { "c" + digits(2) }
            LogicalType.REF -> Ulid.next()
            LogicalType.ANYREF -> "cust" + digits(3) + ":" + Ulid.next()
            LogicalType.FILE, LogicalType.IMAGE, LogicalType.AVATAR, LogicalType.QRCODE, LogicalType.BARCODE, LogicalType.SIGN ->
                jsonStrArray(rnd.nextInt(0, 10)) { "ref" + digits(4) }
            LogicalType.LOCATION -> "中关村" + rnd.nextInt(100) + "\$\$\$-" + digits(2) + "." + digits(3) + ",39." + digits(3)
            LogicalType.BOOL -> BOOL_TRUE[rnd.nextInt(BOOL_TRUE.size)]
        }

    /** 非法值；返回 null＝该型无类型级格式校验（validate 恒放行），非法断言不适用。 */
    private fun illegalOrNull(t: LogicalType): String? =
        when (t) {
            LogicalType.TEXT, LogicalType.TEXTAREA, LogicalType.AUTONUM, LogicalType.SYSTEM, LogicalType.PICKLIST -> null
            LogicalType.PHONE -> listOf("12345", "abcdefgh", "1380013800", "0-1").let { it[rnd.nextInt(it.size)] }
            LogicalType.EMAIL -> listOf("not-an-email", "a@b", "@b.com", "a b@c.com").let { it[rnd.nextInt(it.size)] }
            LogicalType.URL -> listOf("ftp://x", "www.x.cn", "https://", "javascript:alert").let { it[rnd.nextInt(it.size)] }
            LogicalType.NUMBER -> listOf("1.5", "abc", "1e3", "-", "12,000").let { it[rnd.nextInt(it.size)] }
            LogicalType.DECIMAL -> listOf("abc", "1.2.3", "--1", "12o00").let { it[rnd.nextInt(it.size)] }
            LogicalType.DATE -> listOf("2026/09/20", "2026-9-2", "yesterday", "20260920").let { it[rnd.nextInt(it.size)] }
            LogicalType.DATETIME -> listOf("2026-9-2T10:00", "not a time", "2026-09-20T1:00").let { it[rnd.nextInt(it.size)] }
            LogicalType.TIME -> listOf("25:00:00x", "9:00", "hh:mm", "").let { it[rnd.nextInt(it.size)] }
            LogicalType.MULTISELECT, LogicalType.TAGS -> listOf("not-array", "{a,b}", "[1,2", "a]b").let { it[rnd.nextInt(it.size)] }
            LogicalType.N2N -> listOf("[\"short\"]", "[\"abc\",\"not-a-ulid\"]", "not-array").let { it[rnd.nextInt(it.size)] }
            LogicalType.DICT -> listOf("a/b/c/d/e", "/lead", "trailing/", "").let { it[rnd.nextInt(it.size)] }
            LogicalType.REF -> listOf("short", "0123456789", "OILZnotulid" + "0".repeat(10)).let { it[rnd.nextInt(it.size)] }
            LogicalType.ANYREF -> listOf("nocolon", "ab:" + Ulid.next(), ":id", "cust 1:x").let { it[rnd.nextInt(it.size)] }
            LogicalType.FILE, LogicalType.IMAGE, LogicalType.AVATAR, LogicalType.QRCODE, LogicalType.BARCODE, LogicalType.SIGN ->
                if (rnd.nextBoolean()) "not-array" else jsonStrArray(10 + rnd.nextInt(3)) { "ref" + digits(4) }
            LogicalType.LOCATION -> listOf("no-separator", "中关村", "\$\$\$,", "116.3,39.9").let { it[rnd.nextInt(it.size)] }
            LogicalType.BOOL -> listOf("maybe", "2", "truthy", "yess").let { it[rnd.nextInt(it.size)] }
        }

    @Test
    fun `每型 200 合法值 validate 放行且 normalize 幂等`() {
        LogicalType.entries.forEach { t ->
            val ft = TypeRegistry.of(t)
            repeat(200) {
                val v = legal(t)
                assertThat(ft.validate(v)).`as`("$t 合法值应通过校验：$v").isNull()
                val once = ft.normalize(v)
                assertThat(ft.normalize(once)).`as`("$t normalize 应幂等：$v → $once").isEqualTo(once)
                if (once != null) {
                    assertThat(ft.validate(once)).`as`("$t 归一后仍应合法：$v → $once").isNull()
                }
            }
        }
    }

    @Test
    fun `每型 50 非法值 validate 100 拒绝`() {
        LogicalType.entries.forEach { t ->
            val ft = TypeRegistry.of(t)
            repeat(50) {
                val bad = illegalOrNull(t) ?: return@repeat // 无类型级格式校验的型不适用
                assertThat(ft.validate(bad)).`as`("$t 非法值应被拒：$bad").isNotNull()
            }
        }
    }

    private fun digits(n: Int): String = (1..n).map { rnd.nextInt(10).toString() }.joinToString("")

    private fun letters(n: Int): String = (1..n).map { ('a' + rnd.nextInt(26)).toString() }.joinToString("")

    private fun jsonStrArray(count: Int): String = (1..count).joinToString(",", "[", "]") { "\"s" + digits(2) + "\"" }

    private inline fun jsonStrArray(count: Int, crossinline item: () -> String): String = (1..count).joinToString(",", "[", "]") { "\"" + item() + "\"" }

    private companion object {
        val BOOL_TRUE = arrayOf("true", "t", "yes", "y", "1", "是", "TRUE", "Yes")
    }
}
