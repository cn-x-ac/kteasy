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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * L1 纯函数单测（【框架文档 02】金字塔第 1 层）：断言两方言实现**产出的 SQL/DDL/参数**正确，
 * 且互不串味（PG 产物不含 MySQL 语法、反之亦然）。双库真连等价性归集成矩阵测试，这里只锁接口产物。
 */
class DialectSqlTest {
    private val pg = PostgresSchemaProvider()

    private val my = MySqlSchemaProvider()

    // ---------- NamespaceMapper ----------

    @Test
    fun `元数据区命名空间保持 M1-01 物理名 逐字不破`() {
        assertThat(pg.namespace.qualified(LogicalArea.METADATA, "md_object")).isEqualTo("md.md_object")
        assertThat(my.namespace.qualified(LogicalArea.METADATA, "md_object")).isEqualTo("md_object")
    }

    @Test
    fun `动态区 实体与N2N 按 §1_2 分叉`() {
        assertThat(pg.namespace.qualified(LogicalArea.ENTITY, "customer")).isEqualTo("app.customer")
        assertThat(my.namespace.qualified(LogicalArea.ENTITY, "customer")).isEqualTo("e_customer")
        // N2N：PG 在 app schema 内仍加 r_ 前缀，MySQL 同库前缀 r_
        assertThat(pg.namespace.qualified(LogicalArea.RELATION, "cust_orders")).isEqualTo("app.r_cust_orders")
        assertThat(my.namespace.qualified(LogicalArea.RELATION, "cust_orders")).isEqualTo("r_cust_orders")
    }

    // ---------- JsonOps ----------

    @Test
    fun `JsonOps 取值 PG 用 hash 箭头 MySQL 用 json 函数`() {
        val path = JsonPath.of("amount")
        assertThat(pg.json.extractText("ext", path).sql).isEqualTo("(ext #>> '{amount}')")
        assertThat(my.json.extractText("ext", path).sql)
            .isEqualTo("JSON_UNQUOTE(JSON_EXTRACT(ext, '$.amount'))")
    }

    @Test
    fun `JsonOps 嵌套路径 两库各自成型 且无占位符泄漏`() {
        val nested = JsonPath.of("addr", "city")
        assertThat(pg.json.extractTyped(nestedCol(), nested, ValueCast.TEXT).sql).doesNotContain("$")
        assertThat(pg.json.extractTyped("ext", nested, ValueCast.LONG).sql).isEqualTo("(ext #>> '{addr,city}')::bigint")
        assertThat(my.json.extractTyped("ext", nested, ValueCast.LONG).sql)
            .isEqualTo("CAST(JSON_UNQUOTE(JSON_EXTRACT(ext, '$.addr.city')) AS SIGNED)")
    }

    private fun nestedCol(): String = "ext"

    @Test
    fun `bindJson 是唯一承载 JSON 写入方言差异之处`() {
        assertThat(pg.json.bindJson("ui_json")).isEqualTo("CAST(:ui_json AS jsonb)")
        assertThat(my.json.bindJson("ui_json")).isEqualTo(":ui_json")
    }

    @Test
    fun `arrayContains 结果等价但执行路径不同 且各带其绑定参数`() {
        val pgFrag = pg.json.arrayContains("ext", JsonPath.of("tags"), "vip")
        val myFrag = my.json.arrayContains("ext", JsonPath.of("tags"), "vip")
        assertThat(pgFrag.sql).contains("@>").doesNotContain("MEMBER OF")
        assertThat(pgFrag.params.values.single()).isEqualTo("""{"tags":["vip"]}""")
        assertThat(myFrag.sql).contains("MEMBER OF").doesNotContain("@>")
        assertThat(myFrag.params.values.single()).isEqualTo("vip")
    }

    // ---------- IndexOps（PG CONCURRENTLY 强制事务外） ----------

    @Test
    fun `在线表达式索引 PG 标 runOutsideTransaction MySQL 无此前缀`() {
        val expr = "(ext #>> '{amount}')::numeric"
        val pgStmts = pg.index.createExpressionIndex("app.customer", expr, "ix_amount", online = true)
        assertThat(pgStmts).hasSize(1)
        assertThat(pgStmts[0].sql).isEqualTo("CREATE INDEX CONCURRENTLY ix_amount ON app.customer (($expr))")
        assertThat(pgStmts[0].runOutsideTransaction).isTrue()

        val myStmts = my.index.createExpressionIndex("e_customer", "(CAST(ext->>'$.amount' AS DECIMAL))", "ix_amount", online = true)
        assertThat(myStmts.single().sql).contains("ALGORITHM=INPLACE, LOCK=NONE").doesNotContain("CONCURRENTLY")
        assertThat(myStmts.single().runOutsideTransaction).isFalse()
    }

    @Test
    fun `离线表达式索引 PG 不事务外`() {
        val stmts = pg.index.createExpressionIndex("app.customer", "(lower(name))", "ix_name", online = false)
        assertThat(stmts.single().sql).startsWith("CREATE INDEX ix_name ON ").doesNotContain("CONCURRENTLY")
        assertThat(stmts.single().runOutsideTransaction).isFalse()
    }

    @Test
    fun `json 数组索引 PG 用 gin MySQL 用多值索引`() {
        assertThat(
            pg.index
                .createJsonArrayIndex("app.customer", "ext", JsonPath.of("tags"), "ix_tags", true)
                .single()
                .sql,
        ).contains("USING gin (ext jsonb_path_ops)")
        assertThat(
            my.index
                .createJsonArrayIndex("e_customer", "ext", JsonPath.of("tags"), "ix_tags", true)
                .single()
                .sql,
        ).contains("CAST(ext -> '$.tags' AS CHAR(255) ARRAY)")
    }

    // ---------- ColumnOps（MySQL INSTANT→INPLACE 回退候选） ----------

    @Test
    fun `加可空列 MySQL 给两条候选 INSTANT 优先 PG 单条`() {
        val pgStmts = pg.column.addNullableColumn("app.customer", "x", ValueCast.LONG)
        assertThat(pgStmts).hasSize(1)
        assertThat(pgStmts.single().sql).isEqualTo("ALTER TABLE app.customer ADD COLUMN x bigint NULL")
        val myStmts = my.column.addNullableColumn("e_customer", "x", ValueCast.LONG)
        assertThat(myStmts).hasSize(2)
        assertThat(myStmts[0].sql).endsWith(", ALGORITHM=INSTANT")
        assertThat(myStmts[1].sql).contains("ALGORITHM=INPLACE, LOCK=NONE")
    }

    @Test
    fun `加虚拟列带索引时追加索引语句`() {
        val stmts = pg.column.addVirtualColumn("app.customer", "amt", ValueCast.LONG, "(ext #>> '{amount}')::bigint", index = true)
        assertThat(stmts.first().sql).contains("GENERATED ALWAYS AS").contains("VIRTUAL")
        assertThat(stmts[1].sql).contains("CREATE INDEX amt_ix")
    }

    // ---------- Upsert ----------

    @Test
    fun `upsert PG 用 ON CONFLICT 支持 RETURNING MySQL 用 ODKU 不支持`() {
        assertThat(pg.upsert.supportsReturning()).isTrue()
        assertThat(my.upsert.supportsReturning()).isFalse()
        val pgSql = pg.upsert.build("app.customer", listOf("id", "code", "n"), listOf("code"), listOf("n"), returning = "id")
        assertThat(pgSql).contains("ON CONFLICT (code) DO UPDATE SET n = EXCLUDED.n").contains("RETURNING id")
        val mySql = my.upsert.build("e_customer", listOf("id", "code", "n"), listOf("code"), listOf("n"))
        assertThat(mySql).contains("ON DUPLICATE KEY UPDATE n = VALUES(n)").doesNotContain("RETURNING")
    }

    // ---------- Lock / LockingRead / DdlTx ----------

    @Test
    fun `命名锁键稳定 advisory 用 bigint GET_LOCK 用名字`() {
        val key = LockKey("KTEASY:WRITE:cust:42")
        assertThat(LockKey("KTEASY:WRITE:cust:42").id).isEqualTo(key.id) // 可复现
        assertThat(pg.lock.lock(key).sql).contains("pg_advisory_lock")
        assertThat(my.lock.lock(key).sql).contains("GET_LOCK(:__kteasy_lock_name, -1)")
    }

    @Test
    fun `锁键超 64 字符即拒 MySQL GET_LOCK 上限`() {
        assertThatThrownBy { LockKey("x".repeat(65)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `forUpdate 双方言 SKIP LOCKED 文本一致 DdlTx 相反`() {
        assertThat(pg.lockingRead.forUpdate(true)).isEqualTo("FOR UPDATE SKIP LOCKED")
        assertThat(my.lockingRead.forUpdate(true)).isEqualTo("FOR UPDATE SKIP LOCKED")
        assertThat(pg.ddlTx.supportsTransactionalDDL()).isTrue()
        assertThat(my.ddlTx.supportsTransactionalDDL()).isFalse()
        assertThat(my.ddlTx.stepCheckpointRequired()).isTrue() // MySQL 需作业断点（M1-03 地基）
        assertThat(pg.ddlTx.stepCheckpointRequired()).isFalse()
    }
}
