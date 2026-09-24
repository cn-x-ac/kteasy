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

    // ---------- DateOps（M1-05 EQL 循环日期 token） ----------

    @Test
    fun `DateOps 星期几两库皆 ISO 周一序 且无参`() {
        val dowPg = pg.date.dayOfWeek("born")
        val dowMy = my.date.dayOfWeek("born")
        assertThat(dowPg.sql).isEqualTo("EXTRACT(ISODOW FROM (born))::int")
        assertThat(dowMy.sql).isEqualTo("(WEEKDAY(born) + 1)") // WEEKDAY 周一=0 → +1 = ISO 周一=1
        assertThat(dowPg.params).isEmpty()
        assertThat(dowMy.params).isEmpty()
        assertThat(pg.date.dayOfMonth("born").sql).isEqualTo("EXTRACT(DAY FROM (born))::int")
        assertThat(my.date.dayOfMonth("born").sql).isEqualTo("DAYOFMONTH(born)")
        assertThat(pg.date.monthOfYear("born").sql).isEqualTo("EXTRACT(MONTH FROM (born))::int")
        assertThat(my.date.monthOfYear("born").sql).isEqualTo("MONTH(born)")
    }

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

    @Test
    fun `removeKey 清 ext 键 PG 用井号连字符 MySQL 用 JSON_REMOVE 且无绑定参数`() {
        assertThat(pg.json.removeKey("ext", JsonPath.of("amount")).sql).isEqualTo("(ext #- '{amount}'::text[])")
        assertThat(my.json.removeKey("ext", JsonPath.of("amount")).sql).isEqualTo("JSON_REMOVE(ext, '$.amount')")
        // 嵌套：PG text[] 路径、MySQL $.a.b；均无绑定参数。
        val nested = JsonPath.of("addr", "city")
        assertThat(pg.json.removeKey("ext", nested).sql).isEqualTo("(ext #- '{addr,city}'::text[])")
        assertThat(pg.json.removeKey("ext", nested).params).isEmpty()
        assertThat(my.json.removeKey("ext", nested).sql).isEqualTo("JSON_REMOVE(ext, '$.addr.city')")
        assertThat(my.json.removeKey("ext", nested).params).isEmpty()
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
        assertThat(myStmts.single().sql)
            .startsWith("ALTER TABLE e_customer ADD INDEX ix_amount ((")
            .contains(", ALGORITHM=INPLACE, LOCK=NONE")
            .doesNotContain("CONCURRENTLY")
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

    /** M1-07 取号计数推进：PG 单语句 RETURNING；MySQL ODKU + LAST_INSERT_ID 技巧 + 同连接取回语句。 */
    @Test
    fun `取号计数推进 PG 单语句RETURNING MySQL 走LAST_INSERT_ID加取回`() {
        val pgSql = pg.upsert.buildCounterBump("kteasy.kteasy_autonum_seq", listOf("rule_id", "period_key"), "seq_value")
        assertThat(pgSql)
            .contains("INSERT INTO kteasy.kteasy_autonum_seq AS t (rule_id, period_key, seq_value)")
            .contains("VALUES (:rule_id, :period_key, :seq_value)")
            .contains("ON CONFLICT (rule_id, period_key) DO UPDATE SET seq_value = t.seq_value + 1")
            .contains("RETURNING seq_value")
        assertThat(pg.upsert.counterFollowUp()).isNull()

        val mySql = my.upsert.buildCounterBump("kteasy_autonum_seq", listOf("rule_id", "period_key"), "seq_value")
        assertThat(mySql)
            .contains("INSERT INTO kteasy_autonum_seq (rule_id, period_key, seq_value)")
            .contains("VALUES (:rule_id, :period_key, LAST_INSERT_ID(:seq_value))")
            .contains("ON DUPLICATE KEY UPDATE seq_value = LAST_INSERT_ID(seq_value + 1)")
            .doesNotContain("RETURNING")
        assertThat(my.upsert.counterFollowUp()).isEqualTo("SELECT LAST_INSERT_ID()")
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

    // ---------- TableOps（M1-03 建表/删表/外键） ----------

    private val idCol = PhysicalColumn("id", ColumnType.VARCHAR, 32, nullable = false, primaryKey = true)

    private val extCol = PhysicalColumn("ext", ColumnType.JSON)

    @Test
    fun `建对象表 PG 先确保 app schema 存在 MySQL 单表带引擎子句`() {
        val spec = TableSpec(LogicalArea.ENTITY, "customer", listOf(idCol, extCol))
        val pgStmts = pg.table.createTable(spec)
        assertThat(pgStmts.first().sql).isEqualTo("CREATE SCHEMA IF NOT EXISTS app")
        assertThat(pgStmts[1].sql)
            .contains("CREATE TABLE app.customer (")
            .contains("id varchar(32)")
            .contains("ext jsonb")
            .contains("PRIMARY KEY (id)")
        // MySQL 无 schema：单条建表，前缀 e_，带 InnoDB/utf8mb4。
        val myStmts = my.table.createTable(spec)
        assertThat(myStmts).hasSize(1)
        assertThat(myStmts.single().sql)
            .doesNotContain("CREATE SCHEMA")
            .contains("CREATE TABLE e_customer (")
            .contains("id varchar(32)")
            .contains("ext json")
            .endsWith(") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4")
    }

    @Test
    fun `建 N2N 关联表 双方 FK 与唯一对经命名空间分叉`() {
        val spec =
            TableSpec(
                area = LogicalArea.RELATION,
                name = "cust_orders",
                columns = listOf(idCol, PhysicalColumn("cust_id", ColumnType.VARCHAR, 32), PhysicalColumn("order_id", ColumnType.VARCHAR, 32)),
                foreignKeys =
                    listOf(
                        ForeignKeySpec("fk_rel_cust", LogicalArea.RELATION, "cust_orders", "cust_id", LogicalArea.ENTITY, "customer"),
                        ForeignKeySpec("fk_rel_order", LogicalArea.RELATION, "cust_orders", "order_id", LogicalArea.ENTITY, "orders"),
                    ),
                uniques = listOf(UniqueSpec("uk_rel_pair", listOf("cust_id", "order_id"))),
            )
        val pgSql =
            pg.table
                .createTable(spec)
                .last()
                .sql
        assertThat(pgSql)
            .contains("app.r_cust_orders")
            .contains("REFERENCES app.customer (id)")
            .contains("REFERENCES app.orders (id)")
            .contains("UNIQUE (cust_id, order_id)")
        val mySql =
            my.table
                .createTable(spec)
                .single()
                .sql
        assertThat(mySql)
            .contains("r_cust_orders")
            .contains("REFERENCES e_customer (id)")
            .contains("REFERENCES e_orders (id)")
            .contains("UNIQUE INDEX uk_rel_pair")
    }

    @Test
    fun `删表与补外键 各自限定名`() {
        assertThat(pg.table.dropTable(LogicalArea.ENTITY, "customer").sql).isEqualTo("DROP TABLE IF EXISTS app.customer")
        assertThat(my.table.dropTable(LogicalArea.ENTITY, "customer").sql).isEqualTo("DROP TABLE IF EXISTS e_customer")
        val fk = ForeignKeySpec("fk_emp_dept", LogicalArea.ENTITY, "employee", "dept_id", LogicalArea.ENTITY, "department")
        assertThat(pg.table.addForeignKey(fk).sql).isEqualTo("ALTER TABLE app.employee ADD CONSTRAINT fk_emp_dept FOREIGN KEY (dept_id) REFERENCES app.department (id)")
        assertThat(my.table.addForeignKey(fk).sql).isEqualTo("ALTER TABLE e_employee ADD CONSTRAINT fk_emp_dept FOREIGN KEY (dept_id) REFERENCES e_department (id)")
    }

    @Test
    fun `默认值与列类型 双库各按自身语法`() {
        val spec =
            TableSpec(
                LogicalArea.ENTITY,
                "order",
                listOf(
                    idCol,
                    PhysicalColumn("created_at", ColumnType.TIMESTAMP, nullable = false, default = ColumnDefault.Now),
                    PhysicalColumn("row_version", ColumnType.BIGINT, nullable = false, default = ColumnDefault.Zero),
                    PhysicalColumn("approval_state", ColumnType.VARCHAR, 16, nullable = false, default = ColumnDefault.Literal("DRAFT")),
                ),
            )
        val pgSql =
            pg.table
                .createTable(spec)
                .last()
                .sql
        assertThat(pgSql).contains("created_at timestamptz NOT NULL DEFAULT now()").contains("row_version bigint NOT NULL DEFAULT 0").contains("approval_state varchar(16) NOT NULL DEFAULT 'DRAFT'")
        val mySql =
            my.table
                .createTable(spec)
                .single()
                .sql
        assertThat(mySql).contains("created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)").contains("row_version bigint NOT NULL DEFAULT 0").contains("approval_state varchar(16) NOT NULL DEFAULT 'DRAFT'")
    }

    // ---------- IndexOps 普通/删索引（M1-03 追加能力） ----------

    @Test
    fun `普通索引在线 PG 走 CONCURRENTLY 事务外 唯一索引则忽略在线`() {
        val online = pg.index.createIndex("app.customer", listOf("amount"), "ix_amt", unique = false, online = true)
        assertThat(online.single().sql).isEqualTo("CREATE INDEX CONCURRENTLY ix_amt ON app.customer (amount)")
        assertThat(online.single().runOutsideTransaction).isTrue()
        val uniq = pg.index.createIndex("app.customer", listOf("code"), "ux_code", unique = true, online = true)
        assertThat(uniq.single().sql).isEqualTo("CREATE UNIQUE INDEX ux_code ON app.customer (code)").doesNotContain("CONCURRENTLY")
        assertThat(uniq.single().runOutsideTransaction).isFalse()
        // MySQL 用 ALTER ADD INDEX + INPLACE,LOCK=NONE，无事务外标志。
        val myOnline = my.index.createIndex("e_customer", listOf("amount"), "ix_amt", unique = false, online = true)
        assertThat(myOnline.single().sql).isEqualTo("ALTER TABLE e_customer ADD INDEX ix_amt (amount), ALGORITHM=INPLACE, LOCK=NONE")
        assertThat(myOnline.single().runOutsideTransaction).isFalse()
    }

    @Test
    fun `删索引 PG 按 schema 限定名 MySQL 按表 ADD 或 DROP`() {
        assertThat(pg.index.dropIndex("app.customer", "ix_amt", online = true).sql).isEqualTo("DROP INDEX CONCURRENTLY IF EXISTS app.ix_amt")
        assertThat(my.index.dropIndex("e_customer", "ix_amt", online = false).sql).isEqualTo("ALTER TABLE e_customer DROP INDEX ix_amt")
    }

    // ---------- TableOps：id/引用列钉 binary 排序规则（M1-03 设计要点 7） ----------

    @Test
    fun `binary 列 PG 钉 C 排序规则 MySQL 钉 utf8mb4_bin 普通列不吃`() {
        val idCol = PhysicalColumn("id", ColumnType.VARCHAR, 32, nullable = false, primaryKey = true, binaryCollation = true)
        val plain = PhysicalColumn("label", ColumnType.VARCHAR, 191)
        val spec = TableSpec(LogicalArea.ENTITY, "customer", listOf(idCol, plain, extCol))
        val pgSql =
            pg.table
                .createTable(spec)
                .last()
                .sql
        assertThat(pgSql).contains("id varchar(32) COLLATE \"C\"")
        assertThat(pgSql).doesNotContain("label varchar(191) COLLATE")
        val myStmts = my.table.addColumn("e_customer", idCol)
        assertThat(myStmts.first().sql).contains("id varchar(32) COLLATE utf8mb4_bin")
        assertThat(
            my.table
                .addColumn("e_customer", plain)
                .first()
                .sql,
        ).doesNotContain("COLLATE")
    }

    // ---------- IntrospectionOps（precheck 探测：count>0 判存在） ----------

    @Test
    fun `存在性探测 PG 用 schema 参数 MySQL 用 DATABASE 函数`() {
        val pgTable = pg.introspection.tableExists(LogicalArea.ENTITY, "customer")
        assertThat(pgTable.sql).contains("information_schema.tables").doesNotContain("DATABASE()")
        assertThat(pgTable.params).containsEntry("__kteasy_s", "app").containsEntry("__kteasy_t", "customer")
        val myTable = my.introspection.tableExists(LogicalArea.ENTITY, "customer")
        assertThat(myTable.sql).contains("DATABASE()")
        assertThat(myTable.params).containsEntry("__kteasy_t", "e_customer")

        // PG 索引在 pg_indexes；MySQL 在 information_schema.statistics。
        assertThat(pg.introspection.indexExists(LogicalArea.ENTITY, "customer", "ix").sql).contains("pg_indexes")
        assertThat(my.introspection.indexExists(LogicalArea.ENTITY, "customer", "ix").sql).contains("information_schema.statistics")
        // 外键两侧都查 table_constraints，但 schema 定位方式不同。
        assertThat(pg.introspection.foreignKeyExists(LogicalArea.ENTITY, "customer", "fk").sql).contains("constraint_schema = :__kteasy_s")
        assertThat(my.introspection.foreignKeyExists(LogicalArea.ENTITY, "customer", "fk").sql).contains("table_schema = DATABASE()")
    }
}
