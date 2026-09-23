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
import cn.x.ac.kteasy.core.query.QueryContext
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.write.DraftValue
import cn.x.ac.kteasy.core.write.RecordDraft
import cn.x.ac.kteasy.core.write.StrictJsonExtCodec
import cn.x.ac.kteasy.core.write.WriteActor
import cn.x.ac.kteasy.core.write.WriteContext
import cn.x.ac.kteasy.core.write.WriteErrors
import cn.x.ac.kteasy.core.write.WriteIntent
import cn.x.ac.kteasy.core.write.WriteKind
import cn.x.ac.kteasy.core.write.WriteResult
import cn.x.ac.kteasy.core.write.WriteSource
import cn.x.ac.kteasy.core.write.WriteWarnings
import cn.x.ac.kteasy.server.md.MetadataGraphCache
import cn.x.ac.kteasy.server.md.MetadataService
import cn.x.ac.kteasy.server.query.QueryEngine
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 步骤卡 M1-06 块 5 · 通用写入通道双库端到端（真连，pg/mysql 各跑一次，两库命中**同一组期望值**）。
 *
 * 卡面 6 条 GWT 全部落在这里：后门反例集、并发 50 写同一字段的终值、ext 未注册键 410 回显、
 * 20 字段只变 1 个的 diff、事务内抛错的「无半行 + 无事件」、软删→查询不可见→恢复→可见闭环。
 * 另加两条本卡真正的地基断言：`row_version` 单调推进、PATCH 不得抹掉同一 ext 列里未触碰的字段。
 *
 * 前置：治理面建对象 → 物化作业真跑完（表结构由元数据生成，不手写 DDL）；测试结束**保留**对象，
 * 与既有 IT 同一处置口径（库是 disposable 的，见总纲 §A.7）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class WriteChannelIT {
    @Autowired
    lateinit var meta: MetadataService

    @Autowired
    lateinit var cache: MetadataGraphCache

    @Autowired
    lateinit var writes: WriteService

    @Autowired
    lateinit var journal: WriteEventJournal

    @Autowired
    lateinit var engine: QueryEngine

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var dataSource: javax.sql.DataSource

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val sfx = Ulid.next().lowercase().takeLast(8)
    private val deptApi = "wdept_$sfx"
    private val objApi = "wrec_$sfx"
    private val deptId = Ulid.next()
    private lateinit var objId: String
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(zone).withNano(0) }

    private fun jdbc() = JdbcTemplate(dataSource)

    private fun jdbcNamed() = NamedParameterJdbcTemplate(dataSource)

    private fun qual(
        area: LogicalArea,
        logical: String,
    ): String = provider.namespace.qualified(area, logical)

    private fun tableExists(
        area: LogicalArea,
        logical: String,
    ): Boolean {
        val f = provider.introspection.tableExists(area, logical)
        return (NamedParameterJdbcTemplate(dataSource).queryForObject(f.sql, MapSqlParameterSource(f.params), Long::class.java) ?: 0L) > 0
    }

    private fun await(
        poll: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (poll()) return true
            TimeUnit.MILLISECONDS.sleep(200)
        }
        return poll()
    }

    private fun createAll(
        fields: List<Pair<String, DraftValue>>,
    ): WriteResult = writes.write(ctx(), RecordDraft(fields.toMap()))

    private fun ctx(
        intent: WriteIntent = WriteIntent.UPSERT,
        recordId: String? = null,
        version: Long? = null,
        source: WriteSource = WriteSource.UI,
    ) = WriteContext(objApi, intent, source, WriteActor("it-user", "it-dept"), "tr-" + Ulid.next(), now(), recordId, version)

    private fun create(
        vararg kv: Pair<String, DraftValue>,
    ): WriteResult = writes.write(ctx(), RecordDraft(kv.toMap()))

    /** 整行载荷（GWT4 的「八字段回传只变一个」用）。 */
    @BeforeAll
    fun setup() {
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                apiName = deptApi,
                label = "部门",
                kind = "PLAIN",
                displayName = "{label}",
                fields = listOf(MetadataService.FieldCmd("label", "label", "TEXT")),
            ),
        )
        assertThat(await { tableExists(LogicalArea.ENTITY, deptApi) }).`as`("部门表应物化").isTrue()
        // ext 是 jsonb（PG）/json（MySQL）：裸字符串占位会被驱动当 varchar 送过去，必须走方言绑定片段
        jdbcNamed().update(
            "INSERT INTO ${qual(LogicalArea.ENTITY, deptApi)} (id, ext) VALUES (:id, ${provider.json.bindJson("ext")})",
            MapSqlParameterSource().addValue("id", deptId).addValue("ext", """{"label":"部门"}"""),
        )

        val obj =
            meta.createObject(
                MetadataService.ObjectCreateCmd(
                    apiName = objApi,
                    label = "写入验收",
                    kind = "PLAIN",
                    displayName = "{name}",
                    quickSearchFields = listOf("name"),
                    fields =
                        listOf(
                            MetadataService.FieldCmd("name", "name", "TEXT", required = true),
                            MetadataService.FieldCmd("memo", "memo", "TEXTAREA"),
                            MetadataService.FieldCmd("phone", "phone", "PHONE"),
                            MetadataService.FieldCmd("amount", "amount", "DECIMAL"),
                            MetadataService.FieldCmd("cnt", "cnt", "NUMBER"),
                            MetadataService.FieldCmd("vip", "vip", "BOOL"),
                            MetadataService.FieldCmd("born", "born", "DATE"),
                            MetadataService.FieldCmd("org", "org", "REF", refObjectApi = deptApi),
                            // 元数据只读位：阶段 4 的后门反例打在这个字段上
                            MetadataService.FieldCmd("code", "code", "TEXT").copy(writePolicy = "READONLY"),
                        ),
                ),
            )
        objId = obj.id
        assertThat(await { tableExists(LogicalArea.ENTITY, objApi) }).`as`("业务表应物化").isTrue()
        cache.invalidate()
    }

    @AfterAll
    fun teardown() {
        journal.clear()
    }

    // ---------- GWT1 后门反例（含 SYSTEM 来源，P3） ----------

    @Test
    fun `写系统列与只读字段被服务端硬拒 任何来源都没有豁免`() {
        listOf(WriteSource.UI, WriteSource.OPENAPI, WriteSource.SYSTEM, WriteSource.IMPORT).forEach { src ->
            assertThatThrownBy { writes.write(ctx(source = src), RecordDraft(mapOf("created_at" to DraftValue.Text("2026-01-01 00:00:00")))) }
                .isInstanceOfSatisfying(KnownKteasyException::class.java) {
                    assertThat(it.apiError).isEqualTo(ApiError.BUSINESS_RULE)
                    assertThat((it.data as Map<*, *>)["error_id"]).isEqualTo(WriteErrors.ID_SYSTEM_COLUMN_READONLY)
                }
            // 未注册键：410（改载荷可自救）
            assertThatThrownBy { writes.write(ctx(source = src), RecordDraft(mapOf("ghost_col" to DraftValue.Text("x")))) }
                .isInstanceOfSatisfying(KnownKteasyException::class.java) {
                    assertThat(it.apiError).isEqualTo(ApiError.INVALID_PARAM)
                    assertThat(fieldIds(it)).contains(WriteErrors.ID_EXT_UNKNOWN_KEY)
                }
        }
    }

    @Test
    fun `元数据只读字段拒手填 但服务端派生能落值`() {
        assertThatThrownBy { create("name" to DraftValue.Text("甲"), "code" to DraftValue.Text("C-1")) }
            .isInstanceOfSatisfying(KnownKteasyException::class.java) { assertThat(fieldIds(it)).contains(WriteErrors.ID_FIELD_READONLY) }
        // code 有默认值时由阶段 5 填上（本用例建字段时没给默认，故只验拒绝侧）
        val r = create("name" to DraftValue.Text("甲"))
        assertThat(r.kind).isEqualTo(WriteKind.CREATED)
    }

    // ---------- GWT3 ext 未注册键回显 ----------

    @Test
    fun `未注册键 410 且回显键名`() {
        assertThatThrownBy { create("name" to DraftValue.Text("乙"), "nickname2" to DraftValue.Text("野值")) }
            .isInstanceOfSatisfying(KnownKteasyException::class.java) {
                assertThat(it.apiError).isEqualTo(ApiError.INVALID_PARAM)
                assertThat(it.message).contains("nickname2")
                assertThat(fieldIds(it)).contains(WriteErrors.ID_EXT_UNKNOWN_KEY)
            }
    }

    // ---------- GWT4 diff 恰一项 + 事件载荷 ----------

    @Test
    fun `八字段回传只变一个时 diff 恰一项 事件带同一份 diff`() {
        val r = createAll(fullDraft("初始"))
        val version0 = r.version
        journal.clear()
        // 整行原样回传、只把 memo 换掉——这才是 GWT4 的场景（前端整单保存的形状）
        val same = fullDraft("初始").toMap().toMutableMap().apply { this["memo"] = DraftValue.Text("改成这个") }
        val u = writes.write(ctx(recordId = r.id, version = version0), RecordDraft(same))
        // 两库都必须只报 memo：MySQL 的 JSON 会把 12.50 规范化成 12.5，按字符串比 diff 就会多出一条
        assertThat(u.diff.keys).containsExactly("memo")
        assertThat(u.version).isEqualTo(version0 + 1)
        val events = journal.forRecord(r.id)
        assertThat(events).hasSize(1)
        assertThat(events.first().kind).isEqualTo(WriteKind.UPDATED)
        assertThat(events.first().diff.keys).containsExactly("memo")
        assertThat(events.first().traceId).isNotBlank()
    }

    // ---------- GWT2 并发 50 写同一字段：终值与无丢失更新 ----------

    @Test
    fun `并发五十写同一条同一字段 只有一个成功且无丢失更新`() {
        val r = createAll(fullDraft("基准"))
        val pool = Executors.newFixedThreadPool(16)
        try {
            val outcomes =
                (1..50)
                    .map { n ->
                        pool.submit(
                            Callable<String> {
                                try {
                                    writes.write(ctx(recordId = r.id, version = r.version), RecordDraft(mapOf("name" to DraftValue.Text("v$n"))))
                                    "OK:v$n"
                                } catch (e: KnownKteasyException) {
                                    when ((e.data as Map<*, *>)["error_id"]) {
                                        WriteErrors.ID_CONFLICT_RETRY -> "RETRY"
                                        WriteErrors.ID_LOCK_RETRY -> "LOCK"
                                        else -> "OTHER:" + e.message
                                    }
                                }
                            },
                        )
                    }.map { it.get(120, TimeUnit.SECONDS) }
            val ok = outcomes.filter { it.startsWith("OK:") }
            assertThat(ok).`as`("同一旧版本只有一个人能提交成功").hasSize(1)
            assertThat(outcomes.filter { it == "RETRY" || it == "LOCK" }).hasSize(49)
            assertThat(outcomes.filter { it.startsWith("OTHER:") }).isEmpty()
            // 终值＝成功者写入的值，且版本恰好 +1（无丢失更新、无空洞）
            val row = jdbc().queryForMap("SELECT * FROM ${qual(LogicalArea.ENTITY, objApi)} WHERE id = ?", r.id)
            assertThat(row["row_version"] as Long).isEqualTo(r.version + 1)
            assertThat(extOf(row)["name"]).isEqualTo(DraftValue.Text(ok.first().removePrefix("OK:")))
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------- GWT5 事务内抛错：无半行、无事件 ----------

    @Test
    fun `落库阶段抛错时既无半行也无事件`() {
        journal.clear()
        // 引用一个格式合法但不存在的部门 id → 外键在事务内部才炸（校验层放行、落库层拒绝）
        val ghost = Ulid.next()
        assertThatThrownBy { create("name" to DraftValue.Text("半行?"), "org" to DraftValue.Text(ghost)) }
            .isInstanceOfSatisfying(KnownKteasyException::class.java) { assertThat(it.apiError).isEqualTo(ApiError.BUSINESS_RULE) }
        assertThat(journal.snapshot()).`as`("回滚的事务不得留下提交事件").isEmpty()
        assertThat(jdbc().queryForList("SELECT id FROM ${qual(LogicalArea.ENTITY, objApi)} WHERE id = (?)", ghost)).isEmpty()
        val names = jdbc().queryForList("SELECT ext FROM ${qual(LogicalArea.ENTITY, objApi)}")
        assertThat(names.map { extOf(it) }.filter { it["name"] == DraftValue.Text("半行?") }).isEmpty()
    }

    // ---------- GWT6 软删 → 查询不可见 → 恢复 → 可见 ----------

    @Test
    fun `软删让查询层不可见 恢复后可见`() {
        val r = create("name" to DraftValue.Text("会消失"), "memo" to DraftValue.Text("m"))
        assertThat(visible(r.id)).`as`("新建即可查").isTrue()
        val d = writes.write(ctx(WriteIntent.DELETE, r.id, r.version), RecordDraft())
        assertThat(d.kind).isEqualTo(WriteKind.DELETED)
        assertThat(visible(r.id)).`as`("软删后查询层默认不可见（M1-05 恒注入软删谓词）").isFalse()
        assertThat(journal.forRecord(r.id).first().kind).isEqualTo(WriteKind.DELETED)
        val rest = writes.write(ctx(WriteIntent.RESTORE, r.id, d.version), RecordDraft())
        assertThat(rest.kind).isEqualTo(WriteKind.RESTORED)
        assertThat(visible(r.id)).`as`("恢复后重新可见").isTrue()
        // 重复删＝幂等无变化（重试不该变成事故）
        val again = writes.write(ctx(WriteIntent.DELETE, r.id, rest.version), RecordDraft())
        assertThat(again.diff).isEmpty()
    }

    // ---------- 本卡地基：版本推进与 ext 整列覆盖 ----------

    @Test
    fun `版本从一到N单调推进 不带版本的覆盖出告警`() {
        val r = createAll(fullDraft("v1"))
        assertThat(r.version).isEqualTo(1L)
        val blind = writes.write(ctx(recordId = r.id), RecordDraft(mapOf("memo" to DraftValue.Text("盲改"))))
        assertThat(blind.version).isEqualTo(2L)
        assertThat(blind.warnings.map { it.code }).containsExactly(WriteWarnings.OVERRIDE_WITHOUT_VERSION)
        val guarded = writes.write(ctx(recordId = r.id, version = 2L), RecordDraft(mapOf("memo" to DraftValue.Text("带版本"))))
        assertThat(guarded.version).isEqualTo(3L)
        assertThat(guarded.warnings).isEmpty()
    }

    @Test
    fun `PATCH 单字段不抹掉同一 ext 列里未触碰的字段`() {
        val r = create("name" to DraftValue.Text("留着"), "memo" to DraftValue.Text("别的字段"), "cnt" to DraftValue.Number("7"))
        writes.write(ctx(recordId = r.id, version = r.version), RecordDraft(mapOf("name" to DraftValue.Text("改名"))))
        val ext = extOf(jdbc().queryForMap("SELECT * FROM ${qual(LogicalArea.ENTITY, objApi)} WHERE id = ?", r.id))
        assertThat(ext["name"]).isEqualTo(DraftValue.Text("改名"))
        assertThat(ext["memo"]).`as`("ext 整列覆盖写入必须并回未触碰的旧键").isEqualTo(DraftValue.Text("别的字段"))
    }

    @Test
    fun `空载荷的更新不写库 不推版本 不发事件`() {
        val r = create("name" to DraftValue.Text("只改版本"))
        journal.clear()
        val u = writes.write(ctx(recordId = r.id, version = r.version), RecordDraft())
        assertThat(u.version).`as`("无变化即跳过（M3 结果一致跳过的地基）").isEqualTo(1L)
        assertThat(journal.snapshot()).`as`("跳过的写入不得产生提交事件").isEmpty()
        assertThat(extOf(jdbc().queryForMap("SELECT * FROM ${qual(LogicalArea.ENTITY, objApi)} WHERE id = ?", r.id))["name"]).isEqualTo(DraftValue.Text("只改版本"))
    }

    // ---------- 辅助 ----------

    private fun fullDraft(
        name: String,
    ): List<Pair<String, DraftValue>> =
        listOf(
            "name" to DraftValue.Text(name),
            "memo" to DraftValue.Text("备注"),
            "phone" to DraftValue.Text("13800001111"),
            "amount" to DraftValue.Number("12.50"),
            "cnt" to DraftValue.Number("7"),
            "vip" to DraftValue.Bool(true),
            "born" to DraftValue.Text("2026-03-18"),
            "org" to DraftValue.Text(deptId),
        )

    private fun extOf(
        row: Map<String, Any?>,
    ): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return cn.x.ac.kteasy.core.write
            .StrictJsonExtCodec()
            .decode(row["ext"]?.toString()) as Map<String, Any?>
    }

    private fun fieldIds(
        e: KnownKteasyException,
    ): List<String> = ((e.data as Map<*, *>)["fields"] as List<*>).map { (it as Map<*, *>)["error_id"] as String }

    private fun visible(
        id: String,
    ): Boolean {
        val r =
            engine.run(
                "SELECT name FROM $objApi WHERE id = '$id' LIMIT 5",
                QueryContext(userId = null, now = now()),
            )
        return r.rows.isNotEmpty()
    }
}
