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
package cn.x.ac.kteasy.core.write

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ext 编解码单测（步骤卡 M1-06 块 3）。锁三件事：
 * ① 清空＝删键，绝不写 JSON null（M1-05 D9 的键存在性口径——落 null 会让两库读出不同语义）；
 * ② 四种值形往返无损，含引号/反斜杠/换行与不可见字符转义；
 * ③ 脏数据必须抛错而非静默降级（嵌套对象、残缺串、尾部垃圾）——静默会让 diff 与审计读到假值。
 */
class ExtCodecTest {
    private val codec = StrictJsonExtCodec()

    @Test
    fun `清空即删键`() {
        val json = codec.encode(linkedMapOf("a" to DraftValue.Text("x"), "b" to DraftValue.Cleared))
        assertEquals("""{"a":"x"}""", json)
        assertTrue("\"b\"" !in json)
        assertEquals(mapOf("a" to DraftValue.Text("x")), codec.decode(json))
    }

    @Test
    fun `四种值形往返无损`() {
        val src =
            linkedMapOf(
                "t" to DraftValue.Text("a\nb\"c\\d"),
                "n" to DraftValue.Number("-12.50"),
                "b" to DraftValue.Bool(false),
                "m" to DraftValue.Many(listOf("甲", "乙")),
            )
        val json = codec.encode(src)
        assertEquals(src, codec.decode(json), "往返必须逐值相等：$json")
        assertEquals("""{"t":"a\nb\"c\\d","n":-12.50,"b":false,"m":["甲","乙"]}""", json)
    }

    @Test
    fun `空输入与空对象都给空图`() {
        listOf<String?>(null, "", "   ", "null", "{}").forEach { assertNull(it) }
    }

    @Test
    fun `控制字符走 u 转义且可还原`() {
        val v = DraftValue.Text("tab\tbell")
        val json = codec.encode(mapOf("k" to v))
        assertTrue("\\t" in json, json)
        assertEquals(v, codec.decode(json)["k"])
    }

    @Test
    fun `脏数据一律抛错 不静默降级`() {
        // 嵌套对象、裸词、未闭合、尾部垃圾、非字符串数组元素
        listOf(
            """{"a":{"b":1}}""",
            """{"a":undefined}""",
            """{"a":"x""",
            """{"a":"x"} trailing""",
            """{"a":[1,2]}""",
        ).forEach { bad ->
            assertFailsWith<RuntimeException> { codec.decode(bad) }
                .also { assertTrue(it.message!!.isNotEmpty(), "报错要能定位：$bad") }
        }
    }

    @Test
    fun `非数字字面量不得伪装成数字写进库`() {
        assertFailsWith<IllegalArgumentException> { codec.encode(mapOf("n" to DraftValue.Number("1;DROP TABLE x"))) }
        assertFailsWith<IllegalArgumentException> { codec.encode(mapOf("n" to DraftValue.Number(""))) }
    }

    @Test
    fun `空图编码为空对象 由绑定层决定是否写 null`() {
        assertEquals("{}", codec.encode(emptyMap()))
        assertEquals("{}", codec.encode(mapOf("only" to DraftValue.Cleared)))
    }

    private fun assertNull(
        raw: String?,
    ) {
        assertEquals(emptyMap(), codec.decode(raw))
    }
}
