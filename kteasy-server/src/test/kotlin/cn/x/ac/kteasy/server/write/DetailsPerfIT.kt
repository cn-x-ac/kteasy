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

import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.write.DetailRow
import cn.x.ac.kteasy.core.write.DraftValue
import cn.x.ac.kteasy.core.write.RecordDraft
import cn.x.ac.kteasy.core.write.WriteActor
import cn.x.ac.kteasy.core.write.WriteContext
import cn.x.ac.kteasy.core.write.WriteSource
import cn.x.ac.kteasy.server.md.MetadataService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 步骤卡 M1-07 块 2 的 ⟨可逆基准⟩「1000 子项 / <3s」实测件（`KTEASY_PERF=true` 门控，不进 CI boot-smoke 白名单）。
 *
 * 与 [DetailsIT] 的分工：那张卡钉**语义正确性**（三集/原子/不碰），规模刻意缩到 60，因为千行会把共享库
 * 拉成分钟级；本件只做一件事——在**指定规模**下量两轮的墙钟耗时并落盘，供里程碑 DoD 与后续批式优化对照。
 *
 * 断言纪律：规模与轮次照卡面 GWT 跑（改 70% / 删 30% / 增 20%），但**断言只断正确性**（终存活数、被软删数、
 * 更新行 id 原样存活），不断耗时。首次跑是取基线，把未经环境标定的阈值写死成断言只会得到一条无信息量的红。
 *
 * 耗时不靠 `println`（convention plugin 刻意关 `showStandardStreams`，见 `KteasyKotlinJvmConventionPlugin`），
 * 而是追加写 `KTEASY_PERF_OUT`（默认 `build/perf/details-perf.txt`），并连库主机/方言/行数一起记，
 * 使数字可判读：跨网 RTT 下的墙钟不能直接当本机或现网基线用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KTEASY_PERF", matches = "true")
class DetailsPerfIT {
    @Autowired
    lateinit var meta: MetadataService

    @Autowired
    lateinit var writes: WriteService

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var dataSource: javax.sql.DataSource

    @Autowired
    lateinit var context: cn.x.ac.kteasy.core.kernel.KteasyContext

    private val zone = ZoneId.of("Asia/Shanghai")
    private val sfx = Ulid.next().lowercase().takeLast(8)
    private val parentApi = "ppar_$sfx"
    private val childApi = "pcli_$sfx"
    private val rows = (System.getenv("KTEASY_DETAIL_ROWS") ?: "1000").toInt()
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(zone).withNano(0) }
    private lateinit var childTable: String
    private lateinit var parentTable: String

    private fun jdbc() = JdbcTemplate(dataSource)

    private fun ctx(
        objectApi: String,
        recordId: String? = null,
        version: Long? = null,
    ) = WriteContext(objectApi, cn.x.ac.kteasy.core.write.WriteIntent.UPSERT, WriteSource.UI, WriteActor("perf-user", "perf-dept"), "tr-" + Ulid.next(), now(), recordId, version)

    private fun liveChildIds(parentId: String): List<String> = jdbc().queryForList("SELECT id FROM $childTable WHERE parent_id = ? AND deleted_at IS NULL", String::class.java, parentId).map { it!! }

    private fun row(
        id: String? = null,
        qty: Int = 1,
    ) = DetailRow(id, mapOf("qty" to DraftValue.Number(qty.toString())))

    private fun ensureTables() {
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                apiName = parentApi,
                label = "主单",
                kind = "PARENT",
                displayName = "{doc_no}",
                fields = listOf(MetadataService.FieldCmd("doc_no", "编号", "TEXT", required = true)),
            ),
        )
        awaitTable(parentApi)
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                apiName = childApi,
                label = "明细",
                kind = "CHILD",
                parentApi = parentApi,
                displayName = "{qty}",
                fields = listOf(MetadataService.FieldCmd("qty", "数量", "NUMBER", required = true)),
            ),
        )
        awaitTable(childApi)
        childTable = provider.namespace.qualified(LogicalArea.ENTITY, childApi)
        parentTable = provider.namespace.qualified(LogicalArea.ENTITY, parentApi)
    }

    private fun awaitTable(api: String) {
        val f = provider.introspection.tableExists(LogicalArea.ENTITY, api)
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val exists = (NamedParameterJdbcTemplate(dataSource).queryForObject(f.sql, MapSqlParameterSource(f.params), Long::class.java) ?: 0L) > 0
            if (exists) return
            TimeUnit.MILLISECONDS.sleep(200)
        }
        throw AssertionError("表 $api 未在 120s 内物化")
    }

    @Test
    fun `基准 千子项 一轮创建 一轮差量 耗时落盘`() {
        ensureTables()

        // 轮 1：单笔 write 建 rows 条子行（父新建）。
        val t0 = System.currentTimeMillis()
        val created = writes.write(ctx(parentApi), RecordDraft(mapOf("doc_no" to DraftValue.Text("PERF-$sfx")), mapOf(childApi to (1..rows).map { row(qty = 1) })))
        val createMs = System.currentTimeMillis() - t0
        val parentId = created.id
        assertThat(liveChildIds(parentId)).`as`("轮 1 应落地 $rows 子行").hasSize(rows)

        // 轮 2：差量——改 70%（带原 id）/ 删 30%（现存−载荷）/ 增 20%（无 id）。
        val keep = (rows * 7) / 10
        val add = (rows * 2) / 10
        val existing = liveChildIds(parentId).sorted()
        val updates = existing.take(keep).map { row(it, qty = 2) }
        val creates = (1..add).map { row(qty = 9) }
        val t1 = System.currentTimeMillis()
        writes.write(
            ctx(parentApi, recordId = parentId, version = created.version),
            RecordDraft(mapOf("doc_no" to DraftValue.Text("PERF-$sfx")), mapOf(childApi to updates + creates)),
        )
        val diffMs = System.currentTimeMillis() - t1

        val live = liveChildIds(parentId).toSet()
        assertThat(live).`as`("终存活＝改 $keep + 增 $add").hasSize(keep + add)
        assertThat(live).`as`("被更新的行 id 原样存活").containsAll(existing.take(keep))
        val deleted = jdbc().queryForObject("SELECT count(*) FROM $childTable WHERE parent_id = ? AND deleted_at IS NOT NULL", Int::class.java, parentId)
        assertThat(deleted).`as`("被软删＝现存−载荷").isEqualTo(rows - keep)

        writeReport(createMs, diffMs, keep, rows - keep, add)
    }

    private fun writeReport(
        createMs: Long,
        diffMs: Long,
        updated: Int,
        deleted: Int,
        added: Int,
    ) {
        val out = File(System.getenv("KTEASY_PERF_OUT") ?: "build/perf/details-perf.txt")
        out.parentFile?.mkdirs()
        val stamp = LocalDateTime.now(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val dialect = context.dialect.name.lowercase()
        out
            .appendText(
                """
                [$stamp] dialect=$dialect host=${System.getenv("KTEASY_DB_HOST") ?: "?"}:${System.getenv("KTEASY_DB_PORT") ?: "?"} rows=$rows
                  轮1 新建 $rows 子项 = ${createMs}ms
                  轮2 差量(改$updated/删$deleted/增$added，共 ${updated + added} 行载荷) = ${diffMs}ms
                  预算 ⟨1000 子项 <3000ms⟩ 判定：轮1 ${verdict(createMs)} / 轮2 ${verdict(diffMs)}

                """.trimIndent() + "\n",
            )
    }

    private fun verdict(ms: Long): String = if (ms < 3_000) "达标(${ms}ms)" else "未达标(${ms}ms)"
}
