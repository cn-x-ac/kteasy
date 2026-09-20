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

/**
 * 步骤卡 M1-04 · 字段类型注册表 L1（纯函数面）：
 * ① 完整性（覆盖 26+SYSTEM 全枚举、一一对应无重复）；② normalize 往返幂等；
 * ③ 类型级 validator 合法/非法矩阵；④ 主显/快查/聚合资格矩阵。
 * 成员/存在性校验与 50w/property 大矩阵分别归 M1-06/块 4。
 */
class FieldTypeTest {
    @Test
    fun `注册表覆盖 26+SYSTEM 全枚举且一一对应`() {
        assertThat(TypeRegistry.all).hasSize(27)
        assertThat(TypeRegistry.all.map { it.logicalType }.toSet() == LogicalType.entries.toSet()).isTrue()
        // 无重复 logicalType
        assertThat(TypeRegistry.all.map { it.logicalType }.distinct()).hasSize(27)
        // storage 与枚举矩阵一致
        LogicalType.entries.forEach { t -> assertThat(TypeRegistry.of(t).storage == t.storage).isTrue() }
    }

    @Test
    fun `合法值 normalize 往返幂等`() {
        val valid =
            listOf(
                LogicalType.TEXT to "客户甲",
                LogicalType.PHONE to "13800138000",
                LogicalType.EMAIL to "a@b.com",
                LogicalType.URL to "https://x.cn",
                LogicalType.NUMBER to "042",
                LogicalType.DECIMAL to "-3.14",
                LogicalType.DATE to "2026-09-20",
                LogicalType.DATETIME to "2026-09-20T10:00:00Z",
                LogicalType.TIME to "10:00",
                LogicalType.BOOL to "TRUE",
                LogicalType.LOCATION to "中关村$$$116.31,39.98",
                LogicalType.DICT to "001/002",
                LogicalType.REF to Ulid.next(),
                LogicalType.ANYREF to "customer:" + Ulid.next(),
                LogicalType.MULTISELECT to "[ \"a\", \"b\" ]",
                LogicalType.TAGS to "[\"t1\"]",
                LogicalType.N2N to "[\"" + Ulid.next() + "\"]",
                LogicalType.FILE to "[\"f1\",\"f2\"]",
            )
        for ((type, raw) in valid) {
            val ft = TypeRegistry.of(type)
            assertThat(ft.validate(raw)).`as`("$type 应合法：$raw").isNull()
            val once = ft.normalize(raw)
            val twice = ft.normalize(once)
            assertThat(twice).`as`("$type normalize 应幂等").isEqualTo(once)
        }
    }

    @Test
    fun `非法值被类型级 validator 拒绝`() {
        val illegal =
            mapOf(
                LogicalType.PHONE to "12345",
                LogicalType.EMAIL to "not-an-email",
                LogicalType.URL to "ftp://x",
                LogicalType.NUMBER to "1.5",
                LogicalType.DECIMAL to "abc",
                LogicalType.DATE to "2026/09/20",
                LogicalType.TIME to "25:00:00x",
                LogicalType.BOOL to "maybe",
                LogicalType.LOCATION to "no-separator",
                LogicalType.REF to "short",
                LogicalType.ANYREF to "nocolon",
                LogicalType.MULTISELECT to "not-array",
                LogicalType.DICT to "a/b/c/d/e",
                LogicalType.N2N to "[\"bad\"]",
            )
        for ((type, raw) in illegal) {
            assertThat(TypeRegistry.of(type).validate(raw)).`as`("$type 应拒绝：$raw").isNotNull()
        }
    }

    @Test
    fun `主显_快查_聚合_物理化 资格矩阵`() {
        // 主显资格：文本/电话/邮箱/下拉/多选…可；关系/数字/系统列不可（按设计）
        assertThat(Text.nameFieldEligible).isTrue()
        assertThat(Phone.nameFieldEligible).isTrue()
        assertThat(Number.nameFieldEligible).isFalse()
        assertThat(Ref.nameFieldEligible).isFalse()
        assertThat(System.nameFieldEligible).isFalse()
        // 快查：文本/电话/邮箱/自动编号/下拉 可，多行文本不可
        assertThat(Text.quickSearchEligible).isTrue()
        assertThat(Autonum.quickSearchEligible).isTrue()
        assertThat(Textarea.quickSearchEligible).isFalse()
        // 聚合：数字族可，文本/数组/关系不可
        assertThat(Number.aggregatable).isTrue()
        assertThat(Decimal.aggregatable).isTrue()
        assertThat(Text.aggregatable).isFalse()
        // 物理化（S7 档二可提真列的标量）：文本/数字/布尔/日期等 true；关系/系统 false
        assertThat(Text.physicalizable).isTrue()
        assertThat(Bool.physicalizable).isTrue()
        assertThat(Ref.physicalizable).isFalse()
        assertThat(Dict.physicalizable).isFalse()
        assertThat(N2n.physicalizable).isFalse()
        // 拼音伴生：文本/电话/多行文本可生成检索码，数字/布尔不可
        assertThat(Text.pinyinGeneratable).isTrue()
        assertThat(Email.pinyinGeneratable).isFalse()
    }
}
