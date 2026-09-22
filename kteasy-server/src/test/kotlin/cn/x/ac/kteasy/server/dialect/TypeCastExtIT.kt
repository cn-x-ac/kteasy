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
import cn.x.ac.kteasy.core.meta.LogicalType
import cn.x.ac.kteasy.core.meta.TypeRegistry
import cn.x.ac.kteasy.core.schema.dialect.JsonPath
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
import java.time.Instant
import javax.sql.DataSource

/**
 * 步骤卡 M1-04 块 4 · 类型 `cast` × ext 编解码两库四象限（手写 SQL 直查 ext，不经 EQL）。
 *
 * 契约：类型编解码器把值规范为 **JSON 字符串**存进 `ext`（如 `{"v":"123"}`）；查询期由 [TypeRegistry] 的 `cast`
 * 经方言 `extractTyped` 读回并解释类型。本测对 6 种 [ValueCast] 逐一：同一份规范串写入 → 按 cast 读回 → 断言跨库回原值。
 * profile 由 `SPRING_PROFILES_ACTIVE` 定、门控 `KTEASY_IT_DB=true`，pg/mysql 各跑一遍；断言永不比 SQL 文本。
 *
 * **TIMESTAMP 例外（S8 不假装等价）**：PG `timestamptz` 按会话时区归一、MySQL `DATETIME` 存裸值无时区，typed 读回跨库
 * 不保证严格相等——本测只断两库 `extractText` 原样回读（encode/decode 恒等）+ PG typed 往返到同一瞬间；MySQL typed cast 不强判、留端到端时区语义归 M1-05。测试区字面量受红线⑤豁免。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class TypeCastExtIT {
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
        table = "cte_m104_" + Ulid.next().lowercase().takeLast(10)
        jdbc.update("CREATE TABLE $table (id BIGINT PRIMARY KEY, ext $jsonColType)", emptyMap<String, Any>())
    }

    @AfterEach
    fun tearDown() {
        runCatching { jdbc.update("DROP TABLE $table", emptyMap<String, Any>()) }
    }

    private fun put(
        id: Long,
        canonical: String,
    ) {
        jdbc.update(
            "INSERT INTO $table (id, ext) VALUES (:id, ${provider.json.bindJson("ext")})",
            mapOf("id" to id, "ext" to """{"v":"$canonical"}"""),
        )
    }

    private fun raw(path: JsonPath): Any? = jdbc.queryForObject("SELECT ${provider.json.extractText("ext", path).sql} FROM $table WHERE id = 1", MapSqlParameterSource(), Any::class.java)

    private fun typed(
        path: JsonPath,
        cast: ValueCast,
    ): Any? = jdbc.queryForObject("SELECT ${provider.json.extractTyped("ext", path, cast).sql} FROM $table WHERE id = 1", MapSqlParameterSource(), Any::class.java)

    @Test
    fun `TEXT 规范串两库回原值`() {
        assertThat(TypeRegistry.of(LogicalType.TEXT).cast).isEqualTo(ValueCast.TEXT)
        put(1, "客户甲")
        assertThat(raw(JsonPath.of("v"))).isEqualTo("客户甲")
        assertThat(typed(JsonPath.of("v"), ValueCast.TEXT)).isEqualTo("客户甲")
    }

    @Test
    fun `LONG 规范串两库回原值`() {
        assertThat(TypeRegistry.of(LogicalType.NUMBER).cast).isEqualTo(ValueCast.LONG)
        put(1, "-42")
        assertThat((typed(JsonPath.of("v"), ValueCast.LONG) as Number).toLong()).isEqualTo(-42L)
    }

    @Test
    fun `DOUBLE 规范串两库回原值`() {
        assertThat(TypeRegistry.of(LogicalType.DECIMAL).cast).isEqualTo(ValueCast.DOUBLE)
        put(1, "-3.14")
        assertThat((typed(JsonPath.of("v"), ValueCast.DOUBLE) as Number).toDouble()).isCloseTo(
            -3.14,
            org.assertj.core.data.Offset
                .offset(1e-9),
        )
    }

    @Test
    fun `BOOL 规范串两库回真值`() {
        assertThat(TypeRegistry.of(LogicalType.BOOL).cast).isEqualTo(ValueCast.BOOL)
        put(1, "true")
        assertThat(toBool(typed(JsonPath.of("v"), ValueCast.BOOL))).isTrue()
    }

    @Test
    fun `DATE 规范串两库回同一天`() {
        assertThat(TypeRegistry.of(LogicalType.DATE).cast).isEqualTo(ValueCast.DATE)
        put(1, "2026-09-20")
        assertThat(typed(JsonPath.of("v"), ValueCast.DATE).toString()).isEqualTo("2026-09-20")
    }

    @Test
    fun `TIMESTAMP 原样回读恒等 PG typed 往返 MySQL 不强判跨库`() {
        assertThat(TypeRegistry.of(LogicalType.DATETIME).cast).isEqualTo(ValueCast.TIMESTAMP)
        val iso = "2026-09-20T10:20:30Z"
        put(1, iso)
        // encode/decode 恒等：两库都能原样回读规范串（typed cast 的跨库差异不在此判）。
        assertThat(raw(JsonPath.of("v"))).isEqualTo(iso)
        if (pg) {
            val ts = typed(JsonPath.of("v"), ValueCast.TIMESTAMP) as java.util.Date
            assertThat(ts.toInstant()).isEqualTo(Instant.parse(iso))
        } else {
            println("[M104-S8][mysql] TIMESTAMP typed cast 跨库时区语义不等价（DATETIME 存裸值无 tz），不强判；端到端归 M1-05")
        }
    }

    private fun toBool(v: Any?): Boolean =
        when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", true) || v == "1"
            else -> false
        }
}
