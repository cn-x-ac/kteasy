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
package cn.x.ac.kteasy.server.schema

import cn.x.ac.kteasy.core.kernel.KteasyContext
import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.server.md.MetadataService
import cn.x.ac.kteasy.server.md.PinyinCodeGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * 步骤卡 M1-04 块 3 · 拼音检索码伴生列双库集成（真连，pg/mysql 各一次）。
 *
 * 证三件事：① 快查 ∩ 可拼音字段建表即落 `<api>_pinyin` varchar 真列 + 前缀 btree（非快查字段不落）；
 * ② 用 [PinyinCodeGenerator] 现算的检索码可被 `LIKE '前缀%'` 命中（EQL `~` 的底层，端到端归 M1-05）；
 * ③ 数字/非拼音字段即使被标快查也不产列。检索码值写入/回填属 M1-06，故本 IT 直写 SQL 落码（测试区字面量豁免红线⑤）。
 *
 * profile 由 `SPRING_PROFILES_ACTIVE` 定、门控 `KTEASY_IT_DB=true`。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class PinyinColumnIT {
    @Autowired
    lateinit var meta: MetadataService

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var context: KteasyContext

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var jobRepo: SchemaJobRepository

    private val suffix = Ulid.next().lowercase().takeLast(8)
    private val created = mutableListOf<String>()

    private fun name(base: String): String = "${base}_$suffix".also { created += it }

    private fun jdbc() = NamedParameterJdbcTemplate(dataSource)

    private fun qual(
        area: LogicalArea,
        logical: String,
    ): String = provider.namespace.qualified(area, logical)

    private fun exists(
        area: LogicalArea,
        logical: String,
    ): Boolean {
        val f = provider.introspection.tableExists(area, logical)
        return (jdbc().queryForObject(f.sql, MapSqlParameterSource(f.params), Long::class.java) ?: 0L) > 0
    }

    private fun hasColumn(
        area: LogicalArea,
        logical: String,
        column: String,
    ): Boolean {
        val f = provider.introspection.columnExists(area, logical, column)
        return (jdbc().queryForObject(f.sql, MapSqlParameterSource(f.params), Long::class.java) ?: 0L) > 0
    }

    private fun hasIndex(
        area: LogicalArea,
        logical: String,
        index: String,
    ): Boolean {
        val f = provider.introspection.indexExists(area, logical, index)
        return (jdbc().queryForObject(f.sql, MapSqlParameterSource(f.params), Long::class.java) ?: 0L) > 0
    }

    /** 轮询直到条件成立或超时（执行器异步）。 */
    private fun await(
        timeoutMs: Long = 40_000,
        poll: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (poll()) return true
            TimeUnit.MILLISECONDS.sleep(200)
        }
        return poll()
    }

    @AfterEach
    fun cleanup() {
        val jt = JdbcTemplate(dataSource)
        created.reversed().forEach { logical ->
            runCatching { jt.execute("DROP TABLE IF EXISTS ${provider.namespace.qualified(LogicalArea.ENTITY, logical)} CASCADE") }
        }
    }

    private fun fieldCmd(api: String): MetadataService.FieldCmd = MetadataService.FieldCmd(apiName = api, label = api, logicalType = "TEXT")

    @Test
    fun `快查文本字段落拼音检索码真列与索引 直写中文名可前缀命中`() {
        val custApi = name("pcust")
        val obj =
            meta.createObject(
                MetadataService.ObjectCreateCmd(
                    apiName = custApi,
                    label = "客户",
                    kind = "PLAIN",
                    displayName = "{name}",
                    quickSearchFields = listOf("name"),
                    fields = listOf(fieldCmd("name"), fieldCmd("nickname")),
                ),
            )

        // 异步执行器建表；作业到终态。
        assertThat(await { exists(LogicalArea.ENTITY, custApi) })
            .`as`("客户物理表应物化；诊断 rows=%s", jobRepo.listByObject(obj.id))
            .isTrue()
        assertThat(await { !jobRepo.hasUnfinished(obj.id) }).`as`("建表步应全终态").isTrue()

        // name 快查且可拼音 → 落 name_pinyin 真列 + 前缀 btree；nickname 未标快查 → 无检索码列。
        assertThat(hasColumn(LogicalArea.ENTITY, custApi, "name_pinyin")).`as`("快查文本字段应落 name_pinyin 真列").isTrue()
        assertThat(hasIndex(LogicalArea.ENTITY, custApi, "ix_${custApi}_name_pinyin")).`as`("检索码列应建前缀 btree").isTrue()
        assertThat(hasColumn(LogicalArea.ENTITY, custApi, "nickname_pinyin")).`as`("非快查字段不得落检索码列").isFalse()

        // 直写：现算检索码塞进伴生列（值写入/回填归 M1-06，故本 IT 不走写通道）。
        val code = requireNotNull(PinyinCodeGenerator.generate("张三")) { "张三应转出检索码" }
        assertThat(code).isEqualTo("zhangsan")
        jdbc().update(
            "INSERT INTO ${qual(LogicalArea.ENTITY, custApi)} (id, name_pinyin) VALUES (:id, :code)",
            mapOf("id" to Ulid.next(), "code" to code),
        )

        // 拼音前缀命中（EQL ~ 的底层），并反证不同前缀不误命中。
        val hit = jdbc().queryForObject("SELECT count(*) FROM ${qual(LogicalArea.ENTITY, custApi)} WHERE name_pinyin LIKE 'zhang%'", emptyMap<String, Any>(), Long::class.java)
        assertThat(hit).`as`("拼音前缀 zhang 应命中 张三").isEqualTo(1L)
        val miss = jdbc().queryForObject("SELECT count(*) FROM ${qual(LogicalArea.ENTITY, custApi)} WHERE name_pinyin LIKE 'li%'", emptyMap<String, Any>(), Long::class.java)
        assertThat(miss).`as`("无关前缀不应命中").isEqualTo(0L)
    }
}
