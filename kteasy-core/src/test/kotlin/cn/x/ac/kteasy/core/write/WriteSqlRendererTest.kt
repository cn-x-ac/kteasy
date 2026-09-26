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
package cn.x.ac.kteasy.core.write

import cn.x.ac.kteasy.core.meta.DepAggOp
import cn.x.ac.kteasy.core.meta.LogicalType
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.schema.dialect.MySqlSchemaProvider
import cn.x.ac.kteasy.core.schema.dialect.PostgresSchemaProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 写入 SQL 渲染器 golden（块 3）。两库并排断言的意义只有两个：
 * ① 除表名限定与 `ext` 绑定片段外，语句**逐字同形**（两库比较轴一致的证明）；
 * ② 标识符白名单真的能拦住脏列名——列名进不了参数占位，只能靠字符集约束（纵深防御）。
 */
class WriteSqlRendererTest {
    private val pg = WriteSqlRenderer(PostgresSchemaProvider())
    private val my = WriteSqlRenderer(MySqlSchemaProvider())

    private val cols =
        linkedMapOf(
            "id" to "01ARZ3NDEKTSV4RRFFQ69G5FAV",
            "owner_user" to "u1",
            "row_version" to 1L,
            "ext" to """{"name":"甲"}""",
        )

    @Test
    fun `新建语句两库仅表名与 JSON 绑定不同`() {
        val p = pg.insert("account", cols)
        val m = my.insert("account", cols)
        assertEquals("INSERT INTO app.account (id, owner_user, row_version, ext) VALUES (:v_id, :v_owner_user, :v_row_version, CAST(:ext AS jsonb))", p.sql)
        assertEquals("INSERT INTO e_account (id, owner_user, row_version, ext) VALUES (:v_id, :v_owner_user, :v_row_version, :ext)", m.sql)
        val want = mapOf("v_id" to cols["id"], "v_owner_user" to "u1", "v_row_version" to 1L, "ext" to cols["ext"])
        assertEquals(want, p.params, "普通列参数名带 v_ 前缀；ext 必须与方言绑定片段同名")
        assertEquals(want, m.params)
    }

    @Test
    fun `更新语句带乐观并发条件 且主键不进 SET`() {
        val b = pg.update("account", linkedMapOf("name" to "乙", "row_version" to 5L), "R1", 4L)
        assertEquals("UPDATE app.account SET name = :v_name, row_version = :v_row_version WHERE id = :__id AND row_version = :__ver", b.sql)
        assertEquals(mapOf("v_name" to "乙", "v_row_version" to 5L, "__id" to "R1", "__ver" to 4L), b.params)
        assertFailsWith<IllegalArgumentException> { pg.update("account", linkedMapOf("id" to "R1"), "R1", 1L) }
        assertFailsWith<IllegalArgumentException> { pg.update("account", emptyMap(), "R1", 1L) }
    }

    @Test
    fun `行锁子句由方言给 本模块不写库名`() {
        val b = pg.selectForUpdate("account", "R1")
        assertTrue(b.sql.endsWith(PostgresSchemaProvider().lockingRead.forUpdate(false)), b.sql)
        assertTrue(my.selectForUpdate("account", "R1").sql.endsWith(MySqlSchemaProvider().lockingRead.forUpdate(false)))
        assertTrue(" FOR UPDATE" in b.sql && " for update" !in b.sql)
        // 无锁读（恢复路径看墓碑用）不带行锁子句
        assertTrue("FOR UPDATE" !in pg.select("account", "R1").sql)
    }

    @Test
    fun `脏标识符一律拒绝`() {
        // 正常路径下这些名字根本进不了元数据（治理面有 api_name 校验），此处拦的是绕过治理面的那条路
        listOf("a-b", "A", "1x", "x;DROP TABLE y", "x ").forEach { bad ->
            assertFailsWith<IllegalArgumentException> { pg.insert(bad, linkedMapOf("id" to "1")) }
            assertFailsWith<IllegalArgumentException> { pg.insert("account", linkedMapOf(bad to "v")) }
        }
    }

    private val amountField =
        MdField(
            id = "F_amount",
            objectId = "O1",
            apiName = "amount",
            label = "金额",
            logicalType = LogicalType.DECIMAL,
            storageKind = StorageKind.EXT,
        )

    @Test
    fun `recalc 聚合 SQL 走方言 JSON 抽取且软删行不计`() {
        val p = pg.aggregateSql("order_line", amountField, DepAggOp.SUM, "parent_id")
        val m = my.aggregateSql("order_line", amountField, DepAggOp.SUM, "parent_id")
        assertTrue(p.startsWith("SELECT SUM("), p)
        assertTrue(p.endsWith("FROM app.order_line WHERE parent_id = :__pid AND deleted_at IS NULL"), p)
        assertTrue(m.contains("e_order_line") && m.startsWith("SELECT SUM("), m)
        // ext 值抽取各走方言：PG #>> 路径、MySQL JSON 抽取——表达式不同但都裹进 SUM()
        assertTrue(p.contains("#>>"), "PG 走 jsonb 路径抽取：$p")
        assertTrue(m.contains("JSON_EXTRACT") || m.contains("->"), "MySQL 走 JSON 抽取：$m")
        assertTrue(pg.aggregateSql("order_line", amountField, DepAggOp.COUNT, "parent_id").contains("COUNT("))
    }

    @Test
    fun `FIRST LAST 聚合留桩拒 不跨库猜`() {
        assertFailsWith<UnsupportedOperationException> { pg.aggregateSql("order_line", amountField, DepAggOp.FIRST, "parent_id") }
        assertFailsWith<UnsupportedOperationException> { my.aggregateSql("order_line", amountField, DepAggOp.LAST, "parent_id") }
    }
}
