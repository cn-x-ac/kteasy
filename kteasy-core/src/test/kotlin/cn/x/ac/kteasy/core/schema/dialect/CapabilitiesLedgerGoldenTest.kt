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
package cn.x.ac.kteasy.core.schema.dialect

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * capability 台账 golden-file 防漂移（步骤卡 M1-02 验收④ / 图纸 02 §2）。
 *
 * 把两方言的**全量能力档位 + 面向用户的降级说明**烘进 `src/test/resources/capabilities/{pg,mysql}.txt`；
 * 任何「顺手翻一个能力标志 / 改一句降级说明」而未过评审的改动，都会让快照漂移 → 本测红。
 * 首次生成或经评审后确需更新：`-Dkteasy.regenGolden=true` 重写基准文件（然后必须人复核 diff）。
 */
class CapabilitiesLedgerGoldenTest {
    @Test
    fun `PG 能力台账不漂移`() {
        assertGolden("pg", PostgresSchemaProvider())
    }

    @Test
    fun `MySQL 能力台账不漂移`() {
        assertGolden("mysql", MySqlSchemaProvider())
    }

    private fun assertGolden(
        dialect: String,
        provider: SchemaProvider,
    ) {
        val actual = render(provider)
        val golden = goldenFile(dialect)
        if (!golden.exists()) {
            // 首跑自举：生成基准文件（内容须由人复核后入库）；此后实现漂移即红。
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            println("[golden] $dialect 基准缺失，已生成 ${golden.path} —— 请人工复核内容再提交：\n$actual")
        }
        assertThat(actual)
            .`as`("capability 台账漂移（%s）——防「悄悄假装等价」，改动须过评审", dialect)
            .isEqualTo(golden.readText())
    }

    private fun goldenFile(dialect: String): File = File("src/test/resources/capabilities/$dialect.txt")

    private fun render(provider: SchemaProvider): String =
        provider
            .ledger()
            .joinToString("\n") { "${it.capability.name}=${it.level.name}|${it.note}" } + "\n"
}
