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

import com.github.promeg.pinyinhelper.Pinyin

/**
 * 步骤卡 M1-04 块 3 · 名称字段拼音检索码生成器。
 *
 * 落 `kteasy-server`（core 维持零第三方依赖，重依赖只在此侧）；被选为显示名称主显的字段（[cn.x.ac.kteasy.core.meta.FieldType.pinyinGeneratable]）
 * 用本器把中文名转成小写无分隔全拼检索码，供伴生列 `<nameField>_pinyin` 存储、EQL `LIKE '前缀%'` 命中（M1-05 复证端到端）。
 *
 * 取值口径（⟨可逆⟩）：**逐字**转写——汉字取单字全拼并转小写、非汉字（数字/字母/符号）原样保留、字间无分隔。
 * 例：`客户甲` → `kehujia`。多音字取库内默认读音；AhoCorasick 短语级纠音（如 `重庆`→chongqing）留作后续增强，
 * 本卡不引以保检索码前缀稳定与行为可测。空/blank → `null`（不落检索码）。
 */
object PinyinCodeGenerator {
    /**
     * 中文名 → 小写无分隔全拼检索码；空/blank 返回 null。
     */
    fun generate(raw: String?): String? {
        val name = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sb = StringBuilder(name.length * PINYIN_AVG_LEN)
        for (c in name) {
            if (Pinyin.isChinese(c)) {
                // TinyPinyin 单字默认返回大写无声调全拼；转小写以稳定前缀、去分隔以贴合「检索码」语义。
                sb.append(Pinyin.toPinyin(c).lowercase())
            } else {
                // 非汉字原样保留（如姓名中的字母/数字），不参与拼音转换。
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private const val PINYIN_AVG_LEN = 4
}
