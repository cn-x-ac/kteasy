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
package cn.x.ac.kteasy.server.arch

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 方言字面量隔离门禁（步骤卡 M1-02 验收③ / 红线④⑤）。
 *
 * ArchUnit 读不到字节码常量池里的 SQL 字符串，故用**源码扫描**兜这一类：
 * 除 `core/schema/dialect` 这个唯一扩展点外，任何 main 源码都不得出现方言 SQL 字面量
 * （`JSON_EXTRACT`/`->>`/`ON CONFLICT`/`pg_advisory`/`CONCURRENTLY` …）。业务里一旦出现＝编译期红线⑤违例。
 *
 * 反例自证（[detectorFlagsInjectedViolation]）喂一段含 `JSON_EXTRACT` 的假业务源码，断言扫描器必须报红——
 * 证明门禁「有牙」，对应验收③「故意在 data 包写字面量 → CI 红」。
 */
class DialectLiteralIsolationTest {
    /** 只准出现在 core.schema.dialect 里的方言字面量（按需扩容；勿把泛 SQL 如 CAST/SELECT 放进来免误伤）。 */
    private val dialectLiterals =
        listOf(
            "JSON_EXTRACT",
            "JSON_UNQUOTE",
            "JSON_CONTAINS",
            "MEMBER OF",
            "GET_LOCK",
            "RELEASE_LOCK",
            "pg_advisory",
            "CONCURRENTLY",
            "ON CONFLICT",
            "ON DUPLICATE KEY",
            "jsonb_path_ops",
            "AS jsonb",
            "->>",
            "#>",
            "FOR UPDATE SKIP LOCKED",
        )

    @Test
    fun `全仓 main 源码除方言区外无方言字面量`() {
        val violations = scanMainSources()
        assertThat(violations)
            .`as`("方言 SQL 字面量越界（红线⑤）：%s", violations.joinToString { "${it.first}: ${it.second}" })
            .isEmpty()
    }

    @Test
    fun `扫描器对越界字面量必报红 反例自证`() {
        val fake =
            SourceHit(
                "src/main/kotlin/cn/x/ac/kteasy/server/data/FakeRepo.kt",
                "val s = \"SELECT JSON_EXTRACT(ext,'\$.x') FROM t\"",
            )
        assertThat(hitsIn(fake.path, fake.content)).containsExactly("JSON_EXTRACT")
    }

    @Test
    fun `方言区自身允许承载这些字面量`() {
        val inDialectZone =
            SourceHit(
                "kteasy-core/src/main/kotlin/cn/x/ac/kteasy/core/schema/dialect/PostgresSchemaProvider.kt",
                "JSON_EXTRACT pg_advisory CONCURRENTLY",
            )
        assertThat(zoneAllowed(inDialectZone.path)).isTrue()
    }

    private data class SourceHit(
        val path: String,
        val content: String,
    )

    private fun hitsIn(
        path: String,
        content: String,
    ): List<String> = if (zoneAllowed(path)) emptyList() else dialectLiterals.filter { content.contains(it, ignoreCase = true) }

    private fun zoneAllowed(path: String): Boolean = path.replace('\\', '/').contains("/core/schema/dialect/")

    private fun scanMainSources(): List<Pair<String, String>> {
        val root = repoRoot()
        val offenders = mutableListOf<Pair<String, String>>()
        root
            .walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { f ->
                val p = f.absolutePath.replace('\\', '/')
                p.contains("/src/main/") && !p.contains("/build/")
            }.forEach { f ->
                val hits = hitsIn(f.path, f.readText())
                if (hits.isNotEmpty()) offenders += f.path to hits.joinToString()
            }
        return offenders
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "settings.gradle.kts").exists()) return@repoRoot dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未能从 ${System.getProperty("user.dir")} 上溯到仓库根（settings.gradle.kts）")
    }
}
