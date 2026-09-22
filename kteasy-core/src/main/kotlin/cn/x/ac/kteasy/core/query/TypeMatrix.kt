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

import cn.x.ac.kteasy.core.meta.FieldType
import cn.x.ac.kteasy.core.meta.LogicalType

/*
 * 类型 × 操作符 cast 矩阵（图纸 03 §2；「全表＝单测源」——本对象每格都有 TypeMatrixTest 断言）。
 *
 * 编译器据此把「字段类型能不能用这个算子/这个字面量/这个聚合」从运行期翻车前移到编译期拒绝
 * （[EqlErrors.typeMismatch]，code 420）。判定只依赖 [LogicalType] 与 [FieldType] 行为位，不含方言分支（红线⑤）。
 */
object TypeMatrix {
    /** 谓词/算子类别（矩阵列）。 */
    enum class Op {
        EQ, // = / !=
        ORD, // > >= < <=
        IN,
        LIKE,
        MATCH, // ~
        HAS,
        WITHIN,
    }

    private val TEXTISH = setOf(LogicalType.TEXT, LogicalType.TEXTAREA, LogicalType.PHONE, LogicalType.EMAIL, LogicalType.URL, LogicalType.AUTONUM)
    private val NUMERIC = setOf(LogicalType.NUMBER, LogicalType.DECIMAL)
    private val TEMPORAL = setOf(LogicalType.DATE, LogicalType.DATETIME)
    private val ARRAYS = setOf(LogicalType.MULTISELECT, LogicalType.TAGS, LogicalType.FILE, LogicalType.IMAGE, LogicalType.AVATAR, LogicalType.QRCODE, LogicalType.BARCODE, LogicalType.SIGN)
    private val CHOICES = setOf(LogicalType.PICKLIST, LogicalType.DICT)

    fun allows(
        logical: LogicalType,
        op: Op,
    ): Boolean =
        when (op) {
            Op.EQ -> true

            // 等值对全类型语义成立（右值合法性另由 validateLiteral 把关）
            Op.ORD -> logical in NUMERIC || logical in TEMPORAL || logical == LogicalType.TIME || logical == LogicalType.AUTONUM

            Op.IN -> logical in TEXTISH || logical in NUMERIC || logical in CHOICES || logical in ARRAYS || logical == LogicalType.REF || logical == LogicalType.ANYREF

            Op.LIKE -> logical in TEXTISH || logical == LogicalType.LOCATION || logical in CHOICES

            Op.MATCH -> logical in TEXTISH || logical in CHOICES || logical == LogicalType.LOCATION || logical == LogicalType.REF

            // REF 命中目标名称字段
            Op.HAS -> logical in ARRAYS || logical == LogicalType.N2N

            Op.WITHIN -> logical in TEMPORAL
        }

    /** 聚合合法性：count/count_distinct 广开，其余按类型族（图纸 §2「点链末端禁跨端聚合」另在编译器拦）。 */
    fun allowsAggregate(
        logical: LogicalType?,
        fn: AggFn,
    ): Boolean =
        when (fn) {
            AggFn.COUNT -> true
            AggFn.COUNT_DISTINCT -> true
            AggFn.SUM, AggFn.AVG -> logical != null && logical in NUMERIC
            AggFn.MIN, AggFn.MAX -> logical == null || logical in NUMERIC || logical in TEMPORAL || logical == LogicalType.TIME || logical == LogicalType.AUTONUM
        }

    /**
     * 右值字面量合法性：`=`/`in` 的每个值用 [FieldType.validate]（格式级）+ 数字/布尔族形状校验。
     *
     * ⟨已知限制，证据 §2⟩：`FieldType.validate` 仅校格式非语义（§E44：Date 放过 `2026-13-45`）；本矩阵只做格式级，
     * 语义域校验（∈option/dict/引用存在）归 M1-06 写通道，查询侧不假装能挡。返回 null＝合法，否则错误文案。
     */
    fun validateLiteral(
        logical: LogicalType,
        ft: FieldType,
        literal: Literal,
    ): String? {
        val raw =
            when (literal) {
                Literal.Null -> return null
                is Literal.Bool -> literal.value.toString()
                is Literal.Num -> literal.raw
                is Literal.Str -> literal.value
            }
        if (logical in NUMERIC && literal !is Literal.Num) return "数值字段须用数字字面量，实得 '$raw'"
        if (logical == LogicalType.BOOL && literal !is Literal.Bool) return "布尔字段须用 true/false，实得 '$raw'"
        return ft.validate(raw)
    }

    /** 点链末端是否参与聚合（禁跨端聚合——末端来自 join 目标的字段不可在本查询聚合）。 */
    fun rejectCrossEndAggregate(
        path: FieldPath,
        fn: AggFn,
    ) {
        if (path.hops > 0 && fn != AggFn.COUNT && fn != AggFn.COUNT_DISTINCT) {
            throw EqlErrors.typeMismatch("禁跨端聚合：聚合 [${fn.name}] 不可作用于点链 [${path.segments.joinToString(".")}]（recalc 归 M3）")
        }
    }
}
