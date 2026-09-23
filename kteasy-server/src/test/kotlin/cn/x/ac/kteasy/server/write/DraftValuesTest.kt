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
package cn.x.ac.kteasy.server.write

import cn.x.ac.kteasy.core.kernel.KnownKteasyException
import cn.x.ac.kteasy.core.write.DraftValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JSON 载荷 -> 草稿的边界映射单测（块 4，不连库）。
 *
 * 锁的是「边界层不重复判业务、但精度问题必须在这层挡掉」这条分工：
 * 浮点、嵌套对象、混合数组在这里拒；字段是否存在、域校验、必填留给写通道。
 */
class DraftValuesTest {
    @Test
    fun `JSON 形状映射到值代数`() {
        val d =
            DraftValues.toDraft(
                linkedMapOf<String?, Any?>(
                    "name" to "甲",
                    "cnt" to 7,
                    "vip" to true,
                    "tags" to listOf("a", "b"),
                    "memo" to null,
                ),
            )
        assertEquals(DraftValue.Text("甲"), d.values["name"], "字符串在边界层就是 Text，是哪种业务类型由管道裁决")
        assertEquals(DraftValue.Number("7"), d.values["cnt"])
        assertEquals(DraftValue.Bool(true), d.values["vip"])
        assertEquals(DraftValue.Many(listOf("a", "b")), d.values["tags"])
        assertEquals(DraftValue.Cleared, d.values["memo"], "JSON null 即显式清空（与未提交该键是两件事）")
    }

    @Test
    fun `浮点一律拒 小数必须走字符串`() {
        val ex = assertFailsWith<KnownKteasyException> { DraftValues.toDraft(linkedMapOf<String?, Any?>("amount" to 12.5)) }
        assertTrue(ex.message!!.contains("字符串"), ex.message!!)
        assertEquals(DraftValue.Text("12.50"), DraftValues.toDraft(linkedMapOf<String?, Any?>("amount" to "12.50")).values["amount"])
        assertEquals(DraftValue.Number("12.50"), DraftValues.toDraft(linkedMapOf<String?, Any?>("amount" to java.math.BigDecimal("12.50"))).values["amount"])
    }

    @Test
    fun `嵌套对象与混合数组被拒 且逐条报出字段名`() {
        val ex =
            assertFailsWith<KnownKteasyException> {
                DraftValues.toDraft(linkedMapOf<String?, Any?>("addr" to linkedMapOf("city" to "x"), "mix" to listOf("a", 1)))
            }
        assertTrue(ex.message!!.contains("addr"), ex.message!!)
        assertTrue(ex.message!!.contains("mix"), ex.message!!)
    }

    @Test
    fun `空载荷给空草稿`() {
        assertEquals(0, DraftValues.toDraft(null).values.size)
        assertEquals(0, DraftValues.toDraft(emptyMap<String?, Any?>()).values.size)
    }
}
