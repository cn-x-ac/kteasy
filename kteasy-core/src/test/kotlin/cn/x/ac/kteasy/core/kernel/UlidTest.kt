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
package cn.x.ac.kteasy.core.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ulid 生成器 L1 单测：形状/唯一性/单调性/时间戳还原。 */
class UlidTest {
    @Test
    fun `形状为 26 位大写 Crockford 字母表`() {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        repeat(100) {
            val ulid = Ulid.next()
            assertEquals(26, ulid.length)
            assertTrue(ulid.all { it in alphabet }, "非法字符: $ulid")
        }
    }

    @Test
    fun `1 万个互不重复`() {
        val seen = HashSet<String>(10_000)
        repeat(10_000) { assertTrue(seen.add(Ulid.next()), "重复: ${seen.size}") }
    }

    @Test
    fun `同毫秒内字典序单调不降`() {
        var prev = Ulid.next()
        repeat(500) {
            val next = Ulid.next()
            assertTrue(prev <= next, "字典序回退: $prev > $next")
            prev = next
        }
    }

    @Test
    fun `时间戳还原等于生成时刻（1 秒容差）`() {
        val before = System.currentTimeMillis()
        val ulid = Ulid.next()
        val after = System.currentTimeMillis()
        val ts = Ulid.parseTimestamp(ulid)
        assertTrue(ts in before..after, "ts=$ts 不在 [$before, $after] 内")
    }

    @Test
    fun `合法性校验容忍 Crockford 混淆字符并拒绝坏输入`() {
        val ulid = Ulid.next()
        assertTrue(Ulid.isValid(ulid))
        assertTrue(Ulid.isValid(ulid.lowercase()))
        assertTrue(Ulid.isValid("O" + ulid.substring(1)))
        assertTrue(!Ulid.isValid(ulid.substring(1)))
        assertTrue(!Ulid.isValid("U".repeat(26)))
    }
}
