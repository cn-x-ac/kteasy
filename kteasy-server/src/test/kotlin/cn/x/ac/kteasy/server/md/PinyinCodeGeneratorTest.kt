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
package cn.x.ac.kteasy.server.md

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 步骤卡 M1-04 块 3 · 拼音检索码生成器 L1（纯函数、不连库）：
 * 验证「逐字小写无分隔全拼、非汉字原样、空归 null」的取值口径；端到端 LIKE 命中归块 3 的 IT（KTEASY_IT_DB）。
 */
class PinyinCodeGeneratorTest {
    @Test
    fun `中文名称转小写无分隔全拼`() {
        assertThat(PinyinCodeGenerator.generate("客户甲")).isEqualTo("kehujia") // 「甲」＝jia，非交接文件示例的 ji
        assertThat(PinyinCodeGenerator.generate("上海")).isEqualTo("shanghai")
        assertThat(PinyinCodeGenerator.generate("张三丰")).isEqualTo("zhangsanfeng")
    }

    @Test
    fun `非汉字字符原样保留`() {
        // 姓名中的字母/数字/符号不参与转写，保证前缀稳定
        assertThat(PinyinCodeGenerator.generate("客户A1")).isEqualTo("kehuA1")
        assertThat(PinyinCodeGenerator.generate("李四-2")).isEqualTo("lisi-2")
    }

    @Test
    fun `空与空白归一为 null`() {
        assertThat(PinyinCodeGenerator.generate(null)).isNull()
        assertThat(PinyinCodeGenerator.generate("   ")).isNull()
        assertThat(PinyinCodeGenerator.generate("")).isNull()
    }

    @Test
    fun `转写结果幂等且恒为检索码字符集`() {
        val once = PinyinCodeGenerator.generate("客户甲")
        assertThat(PinyinCodeGenerator.generate(once)).isEqualTo(once) // 纯 ascii 再转写不变
        assertThat(once).matches("^[a-zA-Z0-9\\-]+$")
    }
}
