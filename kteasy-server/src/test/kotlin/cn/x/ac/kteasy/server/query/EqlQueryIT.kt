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
package cn.x.ac.kteasy.server.query

import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.query.DateIntervals
import cn.x.ac.kteasy.core.query.DateResolution
import cn.x.ac.kteasy.core.query.QueryContext
import cn.x.ac.kteasy.core.query.SqlRenderer
import cn.x.ac.kteasy.core.schema.SchemaDiff
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.server.md.MetadataGraphCache
import cn.x.ac.kteasy.server.md.MetadataService
import cn.x.ac.kteasy.server.md.PinyinCodeGenerator
import cn.x.ac.kteasy.server.schema.SchemaJobRepository
import org.assertj.core.api.Assertions.assertThat
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * 步骤卡 M1-05 块5 · EQL 查询层双库端到端（真连，pg/mysql 各跑一次；两库各自命中**同一组期望值**＝跨库一致性）。
 *
 * 样本（确定性）：5 行 customer（r1..r5），r4 软删、r5 amount 键缺失。期望由**同一 [DateIntervals]**（块3 oracle）
 * 内存筛出——SQL 区间参数与内存逐条判定共用同一 resolver，本测证「参数化/时区/粒度落库正确」。
 *
 * 边界（证据 §2）：① EXPLAIN 只断言计划正常产出——ext 表达式索引两库边界见 M1-04 D7（makeIndex=false），
 * 跨方言加速（虚拟列桥）另立卡，本测不谎称命中索引；② ext-DATETIME 跨库窗口比较带 M1-04 §S8 时区遗留，
 * 本卡日期用例走 DATE 型；③ 注入反例的表名字面量属测试区豁免（同 PinyinColumnIT 口径）。
 *
 * profile 由 `SPRING_PROFILES_ACTIVE` 定、门控 `KTEASY_IT_DB=true`。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class EqlQueryIT {
    @Autowired
    lateinit var meta: MetadataService

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var cache: MetadataGraphCache

    @Autowired
    lateinit var engine: QueryEngine

    @Autowired
    lateinit var jobRepo: SchemaJobRepository

    @Autowired
    lateinit var dataSource: javax.sql.DataSource

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 3, 18, 10, 0, 0, 0, zone)
    private val sfx = Ulid.next().lowercase().takeLast(8)
    private val deptApi = "qdept_$sfx"
    private val custApi = "qcust_$sfx"

    /** id → (name, born, amount, status, tags) 的确定性样本。 */
    private data class Row(
        val id: String,
        val name: String,
        val born: LocalDate,
        val amount: String?,
        val status: String,
        val tags: List<String>,
        val deleted: Boolean = false,
        val orgId: String? = null,
    )

    private val deptId = Ulid.next()
    private lateinit var rows: List<Row>
    private var relTable = ""

    private fun jdbc() = NamedParameterJdbcTemplate(dataSource)

    private fun qual(
        area: LogicalArea,
        logical: String,
    ): String = provider.namespace.qualified(area, logical)

    private fun tableExists(
        area: LogicalArea,
        logical: String,
    ): Boolean {
        val f = provider.introspection.tableExists(area, logical)
        return (jdbc().queryForObject(f.sql, MapSqlParameterSource(f.params), Long::class.java) ?: 0L) > 0
    }

    private fun await(poll: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (poll()) return true
            TimeUnit.MILLISECONDS.sleep(200)
        }
        return poll()
    }

    private fun ext(
        vararg kv: Pair<String, String>,
        tags: List<String>? = null,
    ): String {
        val parts = kv.map { (k, v) -> "\"$k\":\"$v\"" }.toMutableList()
        if (tags != null) parts += "\"tags\":[" + tags.joinToString(",") { "\"$it\"" } + "]"
        return "{" + parts.joinToString(",") + "}"
    }

    @BeforeAll
    fun setup() {
        // 部门对象（REF/N2N 目标，须先物化以承载 FK）
        meta.createObject(
            MetadataService.ObjectCreateCmd(apiName = deptApi, label = "部门", kind = "PLAIN", displayName = "{label}", fields = listOf(MetadataService.FieldCmd("label", "label", "TEXT"))),
        )
        assertThat(await { tableExists(LogicalArea.ENTITY, deptApi) }).`as`("部门表应物化").isTrue()

        val cust =
            meta.createObject(
                MetadataService.ObjectCreateCmd(
                    apiName = custApi,
                    label = "客户",
                    kind = "PLAIN",
                    displayName = "{name}",
                    quickSearchFields = listOf("name"),
                    fields =
                        listOf(
                            MetadataService.FieldCmd("name", "name", "TEXT"),
                            MetadataService.FieldCmd("amount", "amount", "NUMBER"),
                            MetadataService.FieldCmd("status", "status", "TEXT"),
                            MetadataService.FieldCmd("born", "born", "DATE"),
                            MetadataService.FieldCmd("tags", "tags", "FILE"),
                            MetadataService.FieldCmd("org", "org", "REF", refObjectApi = deptApi),
                            MetadataService.FieldCmd("accounts", "accounts", "N2N", refObjectApi = deptApi),
                        ),
                ),
            )
        assertThat(await { tableExists(LogicalArea.ENTITY, custApi) }).`as`("客户表应物化").isTrue()
        relTable = "${custApi}_accounts"
        assertThat(await { tableExists(LogicalArea.RELATION, relTable) }).`as`("N2N 关联表应物化").isTrue()
        assertThat(await { !jobRepo.hasUnfinished(cust.id) }).`as`("物化步应全终态").isTrue()

        val ids = List(5) { Ulid.next() }
        rows =
            listOf(
                Row(ids[0], "张三", LocalDate.of(2026, 3, 18), "60000", "A", listOf("vip"), orgId = deptId),
                Row(ids[1], "李四", LocalDate.of(2026, 2, 16), "100", "B", emptyList()),
                Row(ids[2], "王五", LocalDate.of(2026, 3, 19), "70000", "A", listOf("vip", "big"), orgId = deptId),
                Row(ids[3], "赵六", LocalDate.of(2026, 3, 18), "99999", "A", emptyList(), deleted = true),
                Row(ids[4], "钱七", LocalDate.of(2026, 3, 17), null, "A", emptyList()),
            )
        val jt = JdbcTemplate(dataSource)
        // 部门行（REF/N2N 目标记录；displayName 模板渲染归后续，行值落 ext.label）
        jdbc().update(
            "INSERT INTO ${qual(LogicalArea.ENTITY, deptApi)} (id, ext) VALUES (:id, ${provider.json.bindJson("ext")})",
            mapOf("id" to deptId, "ext" to ext("label" to "部门")),
        )
        rows.forEach { r ->
            val kv =
                buildList {
                    add("name" to r.name)
                    r.amount?.let { add("amount" to it) }
                    add("status" to r.status)
                    add("born" to r.born.toString())
                }
            jdbc().update(
                "INSERT INTO ${qual(LogicalArea.ENTITY, custApi)} (id, org, name_pinyin, deleted_at, ext) " +
                    "VALUES (:id, :org, :pinyin, :deleted, ${provider.json.bindJson("ext")})",
                mapOf(
                    "id" to r.id,
                    "org" to r.orgId,
                    "pinyin" to PinyinCodeGenerator.generate(r.name),
                    "deleted" to if (r.deleted) java.sql.Timestamp.from(now.toInstant()) else null,
                    "ext" to ext(*kv.toTypedArray(), tags = r.tags),
                ),
            )
        }
        // N2N 关联行：r1→dept、r3→dept
        listOf(rows[0], rows[2]).forEach { r ->
            jdbc().update(
                "INSERT INTO ${qual(LogicalArea.RELATION, relTable)} (id, ${SchemaDiff.relationSourceColumn(custApi)}, ${SchemaDiff.relationTargetColumn(deptApi)}) VALUES (:id, :src, :dst)",
                mapOf("id" to Ulid.next(), "src" to r.id, "dst" to deptId),
            )
        }
    }

    @AfterAll
    fun cleanup() {
        val jt = JdbcTemplate(dataSource)
        listOf(relTable, custApi, deptApi).forEach {
            runCatching { jt.execute("DROP TABLE IF EXISTS ${qual(LogicalArea.ENTITY, it)} CASCADE") }
            runCatching { jt.execute("DROP TABLE IF EXISTS ${qual(LogicalArea.RELATION, it)} CASCADE") }
        }
    }

    private fun run(eql: String): QueryResult = engine.run(eql, QueryContext(userId = null, now = now))

    /** 命中行名集合（oracle：按 token 用同一 DateIntervals 内存筛）。 */
    private fun expectedNames(token: cn.x.ac.kteasy.core.query.DateToken): Set<String> =
        rows
            .filter { !it.deleted }
            .filter { r ->
                when (val res = DateIntervals.resolve(token, now)) {
                    is DateResolution.Window -> {
                        val v = r.born.atStartOfDay(zone)
                        DateIntervals.windowContains(res.from, res.to, v)
                    }

                    is DateResolution.Recurrence -> {
                        when (res.scale) {
                            cn.x.ac.kteasy.core.query.CycleScale.WEEKDAY -> r.born.dayOfWeek.value == res.index
                            cn.x.ac.kteasy.core.query.CycleScale.MONTH_DAY -> r.born.dayOfMonth == res.index
                            cn.x.ac.kteasy.core.query.CycleScale.YEAR_MONTH -> r.born.monthValue == res.index
                        }
                    }
                }
            }.map { it.name }
            .toSet()

    private fun namesOf(result: QueryResult): Set<String> = result.rows.map { it["c0"] as String }.toSet()

    @Test
    fun `比较与逻辑组合 双库与期望一致且软删恒过滤`() {
        // status='A' and amount>50000 → r1,r3（r4 软删、r2 B、r5 无 amount）
        val r = run("select name from $custApi where status = 'A' and amount > 50000 order by id")
        assertThat(namesOf(r)).isEqualTo(setOf("张三", "王五"))
    }

    @Test
    fun `日期区间 token 与 oracle 逐条一致`() {
        listOf(
            cn.x.ac.kteasy.core.query.DateToken
                .Relative(cn.x.ac.kteasy.core.query.RelativeDirection.LAST, 7, cn.x.ac.kteasy.core.query.CalendarUnit.DAY),
            cn.x.ac.kteasy.core.query.DateToken
                .This(cn.x.ac.kteasy.core.query.ThisPeriod.WEEK),
            cn.x.ac.kteasy.core.query.DateToken
                .Exact(cn.x.ac.kteasy.core.query.ExactKind.DAY, 0),
        ).forEach { token ->
            val eql = "select name from $custApi where born within ${dateWord(token)} order by id"
            assertThat(namesOf(run(eql))).`as`("token=%s", token).isEqualTo(expectedNames(token))
        }
    }

    @Test
    fun `每周3 循环谓词经 DateOps 与 oracle 一致`() {
        assertThat(namesOf(run("select name from $custApi where born within everywed:3 order by id"))).isEqualTo(
            expectedNames(
                cn.x.ac.kteasy.core.query.DateToken
                    .Every(cn.x.ac.kteasy.core.query.CycleScale.WEEKDAY, 3),
            ),
        )
    }

    @Test
    fun `拼音前缀命中伴生列`() {
        assertThat(namesOf(run("select name from $custApi where name ~ 'zhang'"))).isEqualTo(setOf("张三"))
        assertThat(namesOf(run("select name from $custApi where name ~ 'wang'"))).isEqualTo(setOf("王五"))
    }

    @Test
    fun `has 数组含值与 N2N 关联存在`() {
        assertThat(namesOf(run("select name from $custApi where has(tags, 'vip')"))).isEqualTo(setOf("张三", "王五"))
        assertThat(namesOf(run("select name from $custApi where has(accounts)"))).isEqualTo(setOf("张三", "王五"))
    }

    @Test
    fun `REF 点链 join 命中目标字段`() {
        assertThat(namesOf(run("select name from $custApi where org.label = '部门' order by id"))).isEqualTo(setOf("张三", "王五"))
    }

    @Test
    fun `ext 空值键语义 双库一致`() {
        // amount = null ≡ 键不存在 → 仅 r5（r1-r4 均有键，含软删 r4 被过滤）
        assertThat(namesOf(run("select name from $custApi where amount = null"))).isEqualTo(setOf("钱七"))
    }

    @Test
    fun `强制 LIMIT 与 truncated 语义`() {
        val r = run("select name from $custApi where status = 'A' order by id limit 2")
        assertThat(r.rows).hasSize(2)
        assertThat(r.truncated).isTrue()
        val full = run("select name from $custApi where status = 'B'")
        assertThat(full.truncated).isFalse()
    }

    @Test
    fun `注入串被整体拒绝 410 且不破坏表`() {
        // 恶意输入的正确行为＝拒绝（卡面验收：全部 410/403，无一条原样进 SQL）。SQL 风格双写引号非法 → 语法拒绝。
        val payload = "select name from $custApi where name = 'x''; DROP TABLE ${qual(LogicalArea.ENTITY, custApi)};--'"
        val ex =
            try {
                run(payload)
                throw AssertionError("注入串应被拒绝")
            } catch (e: cn.x.ac.kteasy.core.kernel.KnownKteasyException) {
                e
            }
        assertThat(ex.apiError).isEqualTo(cn.x.ac.kteasy.core.kernel.ApiError.INVALID_PARAM)
        assertThat(tableExists(LogicalArea.ENTITY, custApi)).`as`("注入不得破坏物理表").isTrue()
        // 反斜杠转义的同形载荷是合法字面量 → 仅当普通字符串匹配（0 行，不报错）。
        val benign = run("select name from $custApi where name = 'x\\'; DROP TABLE anything;--'")
        assertThat(benign.rows).isEmpty()
        assertThat(tableExists(LogicalArea.ENTITY, custApi)).isTrue()
    }

    @Test
    fun `EXPLAIN 计划正常产出 不谎称索引命中`() {
        val plan =
            cn.x.ac.kteasy.core.query.EqlCompiler.compile(
                cn.x.ac.kteasy.core.query.EqlParser
                    .parse("from $custApi where amount > 1"),
                SnapshotMetadataLookup(cache.snapshot()),
            )
        val frag = SqlRenderer(provider, now).render(plan)
        val plans = jdbc().query("EXPLAIN ${frag.sql}", MapSqlParameterSource(frag.params)) { rs, _ -> rs.getString(1) }
        assertThat(plans).isNotEmpty()
    }

    /** 测试内把 token 转回字面词形（与 DateTokens 的紧凑词形一致）。 */
    private fun dateWord(token: cn.x.ac.kteasy.core.query.DateToken): String =
        when (token) {
            is cn.x.ac.kteasy.core.query.DateToken.Relative -> {
                when (token.direction) {
                    cn.x.ac.kteasy.core.query.RelativeDirection.LAST -> "last${token.n}${unitWord(token.unit)}"
                    else -> throw IllegalArgumentException("用例只覆盖 last")
                }
            }

            is cn.x.ac.kteasy.core.query.DateToken.This -> {
                when (token.period) {
                    cn.x.ac.kteasy.core.query.ThisPeriod.WEEK -> "thisw"
                    else -> throw IllegalArgumentException("用例只覆盖 thisw")
                }
            }

            is cn.x.ac.kteasy.core.query.DateToken.Exact -> {
                "exactday${if (token.signedOffset >= 0) "+${token.signedOffset}" else token.signedOffset}"
            }

            else -> {
                throw IllegalArgumentException("不支持词形转换: $token")
            }
        }

    private fun unitWord(u: cn.x.ac.kteasy.core.query.CalendarUnit): String =
        when (u) {
            cn.x.ac.kteasy.core.query.CalendarUnit.DAY -> "d"
            cn.x.ac.kteasy.core.query.CalendarUnit.MONTH -> "m"
            cn.x.ac.kteasy.core.query.CalendarUnit.YEAR -> "y"
            cn.x.ac.kteasy.core.query.CalendarUnit.HOUR -> "h"
        }
}
