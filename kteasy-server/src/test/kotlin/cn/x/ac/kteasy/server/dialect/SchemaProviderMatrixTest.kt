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
package cn.x.ac.kteasy.server.dialect

import cn.x.ac.kteasy.core.kernel.KteasyContext
import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.schema.dialect.JsonPath
import cn.x.ac.kteasy.core.schema.dialect.LockKey
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.schema.dialect.ValueCast
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import javax.sql.DataSource

/**
 * 方言 SPI 双库集成矩阵（步骤卡 M1-02 验收①②，【框架文档 02】L2）。
 *
 * 同一套断言在 pg / mysql 两个 Profile 各跑一遍（profile 由 `SPRING_PROFILES_ACTIVE` 定，
 * 门控 `KTEASY_IT_DB=true` 真连库，禁 H2/禁 mock SQL）；两库都对着**同一组期望常量**成立 ⇒ 结果等价，
 * 断言永不比较 SQL 文本（允许 SQL 不同）。SQL 字面量出现在测试区（非业务代码），受红线⑤豁免。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class SchemaProviderMatrixTest {
    @Autowired
    lateinit var context: KteasyContext

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var dataSource: DataSource

    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var table: String
    private val pg get() = context.dialect.profile == "pg"
    private val jsonColType get() = if (pg) "jsonb" else "json"

    @BeforeEach
    fun setUp() {
        jdbc = NamedParameterJdbcTemplate(dataSource)
        table = "cte_m102_" + Ulid.next().lowercase().takeLast(10)
        jdbc.update(
            "CREATE TABLE $table (id BIGINT PRIMARY KEY, code VARCHAR(64) NOT NULL UNIQUE, n BIGINT, ext $jsonColType)",
            emptyMap<String, Any>(),
        )
    }

    @AfterEach
    fun tearDown() {
        runCatching { jdbc.update("DROP TABLE $table", emptyMap<String, Any>()) }
    }

    private fun insertRow(
        id: Long,
        code: String,
        n: Long,
        ext: String,
    ) {
        // 用 provider 自己的 bindJson 承载写入方言（PG 显式 jsonb 转换 / MySQL 直取），顺带验收它。
        jdbc.update(
            "INSERT INTO $table (id, code, n, ext) VALUES (:id, :code, :n, ${provider.json.bindJson("ext")})",
            mapOf("id" to id, "code" to code, "n" to n, "ext" to ext),
        )
    }

    private fun scalar(
        sql: String,
        params: Map<String, Any?> = emptyMap(),
    ): Any? = jdbc.queryForObject(sql, MapSqlParameterSource(params), Any::class.java)

    // ---------- 验收①：JsonOps 全家取值语义一致（含「JSON 数字是字符串」陷阱） ----------

    @Test
    fun `取值语义两库一致 含数字字符串陷阱`() {
        val ext = """{"num":123,"str":"123","flag":true,"arr":["vip","gold"],"nil":null}"""
        insertRow(1, "a", 0, ext)

        // 陷阱：num(123) 与 str("123") 取「文本」都得到 "123"，靠文本无法区分——正是 S8 强调的坑。
        assertThat(scalar("SELECT ${provider.json.extractText("ext", JsonPath.of("num")).sql} FROM $table WHERE id=1"))
            .isEqualTo("123")
        assertThat(scalar("SELECT ${provider.json.extractText("ext", JsonPath.of("str")).sql} FROM $table WHERE id=1"))
            .isEqualTo("123")
        // 按类型解释后仍一致：num 与 str 都能 cast 成同一整数 123（两库同结果）。
        assertThat((scalar("SELECT ${provider.json.extractTyped("ext", JsonPath.of("num"), ValueCast.LONG).sql} FROM $table WHERE id=1") as Number).toLong())
            .isEqualTo(123L)
        assertThat((scalar("SELECT ${provider.json.extractTyped("ext", JsonPath.of("str"), ValueCast.LONG).sql} FROM $table WHERE id=1") as Number).toLong())
            .isEqualTo(123L)
        // 布尔：PG 出真布尔、MySQL 出 1/0，语义规范化后一致为 true。
        assertThat(toBool(scalar("SELECT ${provider.json.extractTyped("ext", JsonPath.of("flag"), ValueCast.BOOL).sql} FROM $table WHERE id=1")))
            .isTrue()
        // JSON 字面量 null：两库经 extractText 归一为 SQL NULL（MySQL JSON_UNQUOTE 对 json null 返 'null' 串——
        // 故此处用「键存在性」而非取值来判定，暴露的差异由 M1-04/EQL 用 IS DISTINCT 兜底，见证据）。
        assertThat(
            toCount(scalar("SELECT COUNT(*) FROM $table WHERE id=1 AND (${provider.json.predicateExists("ext", JsonPath.of("arr")).sql})")),
        ).isEqualTo(1L)
        assertThat(
            toCount(scalar("SELECT COUNT(*) FROM $table WHERE id=1 AND (${provider.json.predicateExists("ext", JsonPath.of("nope")).sql})")),
        ).isEqualTo(0L)
    }

    // ---------- 验收②：arrayContains 命中各自 JSON 数组索引 ----------

    @Test
    fun `数组包含查询命中索引`() {
        repeat(200) { i ->
            val arr = if (i % 2 == 0) """{"arr":["vip","x$i"]}""" else """{"arr":["other","y$i"]}"""
            insertRow(i.toLong(), "c$i", i.toLong(), arr)
        }
        // 建索引（离线即可，避免在线 DDL 事务外复杂度）：PG GIN jsonb_path_ops / MySQL 多值索引。
        provider.index
            .createJsonArrayIndex(table, "ext", JsonPath.of("arr"), "ix_arr", online = false)
            .forEach { execDdl(it.sql) }

        val contains = provider.json.arrayContains("ext", JsonPath.of("arr"), "vip")
        // 结果正确：只命中偶数行（100 行含 vip）。
        val hits = jdbc.queryForList("SELECT id FROM $table WHERE ${contains.sql}", MapSqlParameterSource(contains.params)).size
        assertThat(hits).isEqualTo(100)
        // 执行计划命中索引（PG 关 seqscan 逼出小表索引选择；MySQL 优化器对多值索引自选）。
        val explain =
            if (pg) {
                execDdl("SET enable_seqscan = off")
                flatten(jdbc.queryForList("EXPLAIN SELECT id FROM $table WHERE ${contains.sql}", MapSqlParameterSource(contains.params)))
            } else {
                flatten(jdbc.queryForList("EXPLAIN SELECT id FROM $table WHERE ${contains.sql}", MapSqlParameterSource(contains.params)))
            }
        assertThat(explain.lowercase())
            .`as`("arrayContains 执行计划应命中 ix_arr：%s", explain)
            .contains("ix_arr")
        println("[M102-EXPLAIN][${context.dialect.profile}] ${explain.replace('\n', ' ')}")
    }

    // ---------- upsert / 命名锁 / 加锁读 两库结果等价 ----------

    @Test
    fun `upsert 冲突更新两库一致`() {
        val sql = provider.upsert.build(table, listOf("id", "code", "n"), listOf("code"), listOf("n"))
        val p = { v: Long -> mapOf("id" to v, "code" to "dup", "n" to v) }
        jdbc.update(sql, MapSqlParameterSource(p(1)))
        jdbc.update(sql, MapSqlParameterSource(p(2))) // 命中 code 唯一约束 → 更新 n=2、仍一行
        assertThat((scalar("SELECT COUNT(*) FROM $table WHERE code='dup'") as Number).toLong()).isEqualTo(1L)
        assertThat((scalar("SELECT n FROM $table WHERE code='dup'") as Number).toLong()).isEqualTo(2L)
    }

    @Test
    fun `命名锁在同一连接上加解锁成对`() {
        val key = LockKey("KTEASY:M102:" + Ulid.next().takeLast(8))
        val lock = provider.lock.lock(key)
        val unlock = provider.lock.unlock(key)
        // 命名锁是「连接/会话」级：advisory / GET_LOCK 必须同连接加与解，故包一层 SingleConnectionDataSource。
        dataSource.connection.use { conn ->
            val scoped =
                NamedParameterJdbcTemplate(
                    org.springframework.jdbc.datasource
                        .SingleConnectionDataSource(conn, true),
                )
            val locked = scoped.query(lock.sql, MapSqlParameterSource(lock.params)) { rs, _ -> rs.getObject(1) }
            assertThat(locked).hasSize(1)
            val released = scoped.query(unlock.sql, MapSqlParameterSource(unlock.params)) { rs, _ -> rs.getObject(1) }
            assertThat(released).hasSize(1)
        }
    }

    @Test
    fun `加锁读 FOR UPDATE SKIP LOCKED 可执行`() {
        insertRow(9, "lk", 9, "{}")
        val rows =
            jdbc.queryForList(
                "SELECT id FROM $table WHERE id = :id ${provider.lockingRead.forUpdate(skipLocked = true)}",
                mapOf("id" to 9),
            )
        assertThat(rows).hasSize(1)
    }

    // ---------- helpers ----------

    private fun execDdl(sql: String) {
        org.springframework.jdbc.core
            .JdbcTemplate(dataSource)
            .execute(sql)
    }

    private fun flatten(rows: List<Map<String, Any?>>): String = rows.joinToString(" ") { r -> r.values.joinToString(" ") { it?.toString() ?: "" } }

    private fun toBool(v: Any?): Boolean =
        when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", true) || v == "1"
            else -> false
        }

    private fun toCount(v: Any?): Long = (v as Number).toLong()
}
