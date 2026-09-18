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

import java.math.BigInteger
import java.security.SecureRandom

/**
 * ULID（Universally Unique Lexicographically Sortable Identifier）生成器（【规格】§4-1 决策板：
 * 主键形态 M1 内可逆项，本引擎定 26 位大写 Crockford Base32 字符串）。
 *
 * 结构 = 48 位毫秒时间戳 + 80 位随机量；同一毫秒内单调递增（monotonic），
 * 保证同进程内字典序不回退——这是元数据行与记录主键「按 id 排序≈按创建时间排序」的根基。
 *
 * 纯 JDK 实现，core 保持零第三方依赖（规格 §2）。
 */
object Ulid {
    /** Crockford Base32 字母表：剔除 I/L/O/U 防误读，输出恒为大写 26 位。 */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private val RAND_MASK = BigInteger.ONE.shiftLeft(80).subtract(BigInteger.ONE)
    private val THIRTY_ONE = BigInteger.valueOf(0x1F)
    private val random = SecureRandom()

    private val lock = Any()
    private var lastMs = -1L
    private var lastRand = BigInteger.ZERO

    /**
     * 生成下一个 ULID。同毫秒内随机量 +1（单调递增）；跨毫秒重新取随机量。
     * 全程持锁串行——元数据写入是低频操作，锁不构成热点；记录主键的批量取号由写入通道负责（M1-06）。
     */
    fun next(): String =
        synchronized(lock) {
            var ms = System.currentTimeMillis()
            val rand =
                if (ms == lastMs) {
                    // 同毫秒：随机量 +1；80 位溢出（现实不可能：2^80 次/毫秒）则借下毫秒重取
                    val bumped = lastRand.add(BigInteger.ONE)
                    if (bumped.testBit(80)) {
                        ms += 1
                        freshRand()
                    } else {
                        bumped
                    }
                } else {
                    freshRand()
                }
            lastMs = ms
            lastRand = rand
            String(encode(ms, rand))
        }

    /** 从 ULID 还原毫秒时间戳（前 10 字符即 48 位时间戳），用于测试与诊断。 */
    fun parseTimestamp(ulid: String): Long {
        require(ulid.length == 26) { "ULID 长度必须为 26 位，实际 ${ulid.length}" }
        var ts = BigInteger.ZERO
        for (i in 0 until 10) {
            ts = ts.shiftLeft(5).or(BigInteger.valueOf(digitOf(ulid[i]).toLong()))
        }
        return ts.longValueExact()
    }

    /** 合法性校验：26 位、Crockford 字母表（容忍小写输入，内部统一大写判定）。 */
    fun isValid(ulid: String): Boolean = ulid.length == 26 && ulid.all { digitOfOrNull(it) != null }

    private fun freshRand(): BigInteger = BigInteger(80, random)

    private fun encode(
        ms: Long,
        rand: BigInteger,
    ): CharArray {
        val value = BigInteger.valueOf(ms).shiftLeft(80).or(rand)
        val out = CharArray(26)
        // 大端序：最高 5 位组落在 out[0]（i=25 → 下标 0），最低组落在 out[25]
        for (i in 25 downTo 0) {
            out[25 - i] = ALPHABET[value.shiftRight(5 * i).and(THIRTY_ONE).toInt()]
        }
        return out
    }

    private fun digitOf(c: Char): Int = digitOfOrNull(c) ?: throw IllegalArgumentException("非法 ULID 字符: '$c'")

    private fun digitOfOrNull(c: Char): Int? {
        val upper = c.uppercaseChar()
        val idx = ALPHABET.indexOf(upper)
        if (idx >= 0) return idx
        // Crockford 混淆字符映射：I/L→1、O→0
        return when (upper) {
            'I', 'L' -> 1
            'O' -> 0
            else -> null
        }
    }
}
