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

import cn.x.ac.kteasy.core.kernel.ApiError
import cn.x.ac.kteasy.core.kernel.KnownKteasyException
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * 步骤卡 M1-07 块 2 · 子项差量双库端到端（真连，pg/mysql 各跑一次，两库同一组期望）。
 *
 * **断言只用普通列**（`id`/`parent_id`/`deleted_at`/`row_version`）与行数，绝不写 `->>`/`#>>` 这类
 * 跨方言异义 JSON 算子（红线⑤：连测试也不拼裸方言 SQL）。三集正确性用「行身份是否保留」证明——
 * 更新＝原 id 仍在存活集（若实现走全删重插，id 会变、断言即红），这比读 ext 里的值更能钉住语义。
 *
 * 锁三件事（A1 定稿 + 卡面 §1）：
 * ① 三集正确：60 现存→改 40（带 id 保留身份）+ 删 20（现存−载荷＝软删）+ 增 30（无 id）＝终存活 70；
 * ② 主单事务原子：更新既有父时夹带一缺必填子行 → 整笔回滚，父 `row_version` 不动、子行数不变；
 * ③ 缺子对象键＝不碰该子表：只改父字段、不带 details 键，子表纹丝不动。
 *
 * 卡面 ⟨可逆基准⟩「1000 / <3s」是性能项，不钉进本 IT（per-row 单事务循环是卡面 §1 规定的实现，
 * 正确性优先；千行批处理与 <3s 归 M1 合闸复测，见测试①内注释）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class DetailsIT {
    @Autowired
    lateinit var meta: MetadataService

    @Autowired
    lateinit var writes: WriteService

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var dataSource: javax.sql.DataSource

    private val zone = ZoneId.of("Asia/Shanghai")
    private val sfx = Ulid.next().lowercase().takeLast(8)
    private val parentApi = "dpar_$sfx"
    private val childApi = "dcli_$sfx"
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(zone).withNano(0) }
    private lateinit var childTable: String
    private lateinit var parentTable: String

    private fun jdbc() = JdbcTemplate(dataSource)

    private fun ctx(
        objectApi: String,
        recordId: String? = null,
        version: Long? = null,
    ) = WriteContext(objectApi, cn.x.ac.kteasy.core.write.WriteIntent.UPSERT, WriteSource.UI, WriteActor("it-user", "it-dept"), "tr-" + Ulid.next(), now(), recordId, version)

    private fun liveChildIds(parentId: String): List<String> = jdbc().queryForList("SELECT id FROM $childTable WHERE parent_id = ? AND deleted_at IS NULL", String::class.java, parentId).map { it!! }

    private fun parentVersion(id: String): Long = jdbc().queryForObject("SELECT row_version FROM $parentTable WHERE id = ?", Long::class.java, id)!!

    private fun row(
        id: String? = null,
        qty: Int = 1,
    ) = DetailRow(id, mapOf("qty" to DraftValue.Number(qty.toString())))

    @BeforeAll
    fun setup() {
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
        // 行为证明 parent_id 列存在且被引擎注入：写一条子行、能按 parent_id 读回即为证（不碰 information_schema）。
        val p = writes.write(ctx(parentApi), RecordDraft(mapOf("doc_no" to DraftValue.Text("SMOKE-$sfx")), mapOf(childApi to listOf(row(qty = 1)))))
        assertThat(liveChildIds(p.id)).`as`("子表 parent_id 列存在且注入主 id").hasSize(1)
    }

    private fun awaitTable(api: String) {
        val f = provider.introspection.tableExists(LogicalArea.ENTITY, api)
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            val exists = (NamedParameterJdbcTemplate(dataSource).queryForObject(f.sql, MapSqlParameterSource(f.params), Long::class.java) ?: 0L) > 0
            if (exists) return
            TimeUnit.MILLISECONDS.sleep(200)
        }
        throw AssertionError("表 $api 未在 90s 内物化")
    }

    @Test
    fun `三集正确 改40删20增30 终存活70且更新保行身份`() {
        // 代表性规模（60→改40/删20/增30=终存活70）。卡面 ⟨可逆基准⟩ 的「1000 / <3s」是性能项：
        // 当前实现按卡面 §1「子项写全部入同一事务＝writeAllInTx 循环单条」逐行落库，正确性优先；
        // 千行批处理（批量 INSERT、单事务仍成立）属可逆优化，其 <3s 基准与批式改写归 M1 合闸 50w 复测，
        // 不把分钟级共享库耗时钉进 CI boot-smoke（§E52：多轮往返 × 1000 在 LAN 上是分钟级）。
        val createPayload = (1..60).map { row(qty = 1) }
        val created = writes.write(ctx(parentApi), RecordDraft(mapOf("doc_no" to DraftValue.Text("P1-$sfx")), mapOf(childApi to createPayload)))
        val parentId = created.id
        assertThat(liveChildIds(parentId)).`as`("初始 60 子行").hasSize(60)

        val existing = liveChildIds(parentId).sorted()
        val kept = existing.take(40) // 更新：带原 id
        val updates = kept.map { row(it, qty = 2) }
        val creates = (1..30).map { row(qty = 9) }
        val t0 = System.currentTimeMillis()
        writes.write(
            ctx(parentApi, recordId = parentId, version = created.version),
            RecordDraft(mapOf("doc_no" to DraftValue.Text("P1-$sfx")), mapOf(childApi to updates + creates)),
        )
        val ms = System.currentTimeMillis() - t0

        val live = liveChildIds(parentId).toSet()
        assertThat(live).`as`("终存活＝40 保留 + 30 新建").hasSize(70)
        assertThat(live).`as`("40 更新行 id 原样存活（全删重插会让此断言红）").containsAll(kept)
        val deleted = jdbc().queryForObject("SELECT count(*) FROM $childTable WHERE parent_id = ? AND deleted_at IS NOT NULL", Int::class.java, parentId)
        assertThat(deleted).`as`("被软删＝现存−载荷＝20").isEqualTo(20)
        assertThat(existing.drop(40).toSet() - live).`as`("未保留的 20 id 全部不在存活集").hasSize(20)
        println("[DetailsIT] 60→(改40/删20/增30) 耗时 ${ms}ms（共享库仅观测）")
    }

    @Test
    fun `主单事务原子 夹带缺必填子行则父与子全回滚`() {
        val created = writes.write(ctx(parentApi), RecordDraft(mapOf("doc_no" to DraftValue.Text("ATOM-$sfx")), mapOf(childApi to (1..5).map { row(qty = 1) })))
        val parentId = created.id
        val vBefore = parentVersion(parentId)
        val bad = DetailRow(null, emptyMap()) // 缺必填 qty → 阶段 4 违规
        assertThatThrownBy {
            writes.write(ctx(parentApi, recordId = parentId, version = vBefore), RecordDraft(mapOf("doc_no" to DraftValue.Text("ATOM2-$sfx")), mapOf(childApi to listOf(row(qty = 1), bad, row(qty = 1)))))
        }.isInstanceOfSatisfying(KnownKteasyException::class.java) {
            assertThat(it.apiError).isEqualTo(ApiError.INVALID_PARAM)
        }

        assertThat(parentVersion(parentId)).`as`("回滚：父 row_version 不动").isEqualTo(vBefore)
        assertThat(liveChildIds(parentId)).`as`("回滚：子行数不变，无半行残留").hasSize(5)
    }

    @Test
    fun `父更新不带 details 键即不碰子表`() {
        val created = writes.write(ctx(parentApi), RecordDraft(mapOf("doc_no" to DraftValue.Text("KEEP-$sfx")), mapOf(childApi to listOf(row(qty = 1)))))
        val parentId = created.id
        assertThat(liveChildIds(parentId)).hasSize(1)
        writes.write(ctx(parentApi, recordId = parentId, version = created.version), RecordDraft(mapOf("doc_no" to DraftValue.Text("KEEP2-$sfx"))))
        assertThat(liveChildIds(parentId)).`as`("缺子对象键＝不碰该子表").hasSize(1)
    }
}
