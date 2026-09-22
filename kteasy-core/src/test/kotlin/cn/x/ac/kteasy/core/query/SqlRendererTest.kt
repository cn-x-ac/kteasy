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
package cn.x.ac.kteasy.core.query

import cn.x.ac.kteasy.core.meta.LogicalType
import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.MdObject
import cn.x.ac.kteasy.core.meta.ObjectKind
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.schema.dialect.MySqlSchemaProvider
import cn.x.ac.kteasy.core.schema.dialect.PostgresSchemaProvider
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 块4 渲染器 L1：QueryPlan → 双方言参数化 SQL。核心不变式＝**任何字面量不进 SQL 串**（值只以 `:pN` 绑定）、
 * 软删恒在、REF→LEFT JOIN、N2N→EXISTS、日期→半开区间参数或 DateOps 分量谓词、空值恒末、未写 LIMIT 强制 100。
 */
class SqlRendererTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 3, 18, 10, 0, 0, 0, zone)
    private val pg = PostgresSchemaProvider()
    private val my = MySqlSchemaProvider()

    private val customer = MdObject(id = "C1", apiName = "customer", label = "客户", kind = ObjectKind.PLAIN, quickSearchJson = "[\"name\"]")
    private val emp = MdObject(id = "E1", apiName = "emp", label = "员工", kind = ObjectKind.PLAIN)

    private fun f(
        id: String,
        obj: String,
        api: String,
        type: LogicalType,
        storage: StorageKind,
        ref: String? = null,
    ): MdField = MdField(id = id, objectId = obj, apiName = api, label = api, logicalType = type, storageKind = storage, refObjectId = ref)

    private val lookup: MetadataLookup =
        object : MetadataLookup {
            private val objs = listOf(customer, emp)
            private val flds =
                listOf(
                    f("n1", "C1", "name", LogicalType.TEXT, StorageKind.EXT),
                    f("n2", "C1", "amount", LogicalType.NUMBER, StorageKind.EXT),
                    f("n3", "C1", "born", LogicalType.DATE, StorageKind.EXT),
                    f("n4", "C1", "tags", LogicalType.MULTISELECT, StorageKind.EXT),
                    f("n5", "C1", "manager", LogicalType.REF, StorageKind.COLUMN, "E1"),
                    f("n6", "C1", "accounts", LogicalType.N2N, StorageKind.N2N, "E1"),
                    f("e1", "E1", "name", LogicalType.TEXT, StorageKind.EXT),
                )

            override fun objectByApi(api: String) = objs.firstOrNull { it.apiName == api }

            override fun objectById(id: String) = objs.firstOrNull { it.id == id }

            override fun fieldsByObject(objectId: String) = flds.filter { it.objectId == objectId }
        }

    private fun render(
        p: SchemaProvider,
        eql: String,
    ): cn.x.ac.kteasy.core.schema.dialect.Fragment = SqlRenderer(p, now).render(EqlCompiler.compile(EqlParser.parse(eql), lookup))

    @Test
    fun `值只进绑定参数 字面量绝不进 SQL 串`() {
        val f = render(pg, "from customer where amount > 50000")
        assertThat(f.sql).doesNotContain("50000")
        assertThat(f.params.values).contains(50000L)
        assertThat(f.sql).contains(":p0")
    }

    @Test
    fun `软删恒注入 且缺省强制 LIMIT 100`() {
        val f = render(pg, "from customer where amount > 1")
        assertThat(f.sql).contains("t0.deleted_at IS NULL")
        assertThat(f.sql).contains("LIMIT 100")
        assertThat(f.sql).startsWith("SELECT t0.* FROM")
    }

    @Test
    fun `PG 走 hash 箭头 MySQL 走 json 函数 且都无字面量`() {
        val pgf = render(pg, "from customer where name = 'A'")
        assertThat(pgf.sql).contains("(t0.ext #>> '{name}')::text = :p0")
        assertThat(pgf.sql).doesNotContain("'A'")
        val myf = render(my, "from customer where name = 'A'")
        assertThat(myf.sql).contains("JSON_UNQUOTE(JSON_EXTRACT(t0.ext, '$.name'))")
        assertThat(myf.sql).doesNotContain("'A'")
        assertThat(myf.params.values).containsExactly("A")
    }

    @Test
    fun `REF 点链渲染 LEFT JOIN 到目标表`() {
        val f = render(pg, "from customer where manager.name = 'x'")
        assertThat(f.sql).contains("LEFT JOIN app.emp t1 ON t0.manager = t1.id")
        assertThat(f.sql).contains("(t1.ext #>> '{name}')::text = :p0")
        assertThat(f.sql).doesNotContain("'x'")
        assertThat(render(my, "from customer where manager.name = 'x'").sql).contains("LEFT JOIN e_emp t1 ON t0.manager = t1.id")
    }

    @Test
    fun `N2N has 渲染 EXISTS`() {
        val f = render(pg, "from customer where has(accounts)")
        assertThat(f.sql).contains("EXISTS(SELECT 1 FROM app.r_customer_accounts rs_e1")
        assertThat(f.sql).contains("rs_e1.src_customer_id = t0.id")
    }

    @Test
    fun `日期区间绑定为无字面量的两个参数`() {
        val f = render(pg, "from customer where born within last7d")
        assertThat(f.sql).contains(">= :p")
        assertThat(f.sql).doesNotContain("2026-03") // 日期字面量不得进串
        assertThat(f.params.values).contains(LocalDate.of(2026, 3, 12), LocalDate.of(2026, 3, 19))
    }

    @Test
    fun `循环日期走 DateOps 分量谓词`() {
        val pgf = render(pg, "from customer where born within everywed:3")
        assertThat(pgf.sql).contains("EXTRACT(ISODOW FROM")
        assertThat(pgf.params.values).contains(3)
        assertThat(render(my, "from customer where born within everywed:3").sql).contains("WEEKDAY(")
    }

    @Test
    fun `模糊匹配命中拼音伴生列`() {
        val f = render(pg, "from customer where name ~ 'kehu'")
        assertThat(f.sql).contains("lower(t0.name_pinyin) LIKE lower(:p0)")
        assertThat(f.params.values).contains("kehu%")
    }

    @Test
    fun `聚合与空值恒末排序`() {
        val agg = render(pg, "select count(*) from customer")
        assertThat(agg.sql).contains("SELECT COUNT(*)")
        val ord = render(pg, "from customer where amount > 1 order by amount desc")
        assertThat(ord.sql).contains("(t0.deleted_at IS NULL)")
        assertThat(ord.sql).contains("ORDER BY ((t0.ext #>> '{amount}')::bigint IS NULL) ASC, (t0.ext #>> '{amount}')::bigint DESC")
    }
}
