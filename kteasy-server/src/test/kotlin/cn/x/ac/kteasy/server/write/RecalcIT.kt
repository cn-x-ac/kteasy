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
import cn.x.ac.kteasy.core.write.DraftValue
import cn.x.ac.kteasy.core.write.RecordDraft
import cn.x.ac.kteasy.core.write.WriteActor
import cn.x.ac.kteasy.core.write.WriteContext
import cn.x.ac.kteasy.core.write.WriteIntent
import cn.x.ac.kteasy.core.write.WriteSource
import cn.x.ac.kteasy.server.md.MetadataService
import org.assertj.core.api.Assertions.assertThat
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
 * 步骤卡 M1-07 块4 · recalc 端到端（真连，pg/mysql 各一次，同一组期望）。
 *
 * rollup：`Customer.balance ← SUM(Order.amount)`（源＝子对象 Order，link＝Order.parent_id）。
 * 锁 recalc 执行体：写一条 Order（子）→ 同事务沿 md_dep 边把父 Customer.balance 重算并经写通道回写
 * （版本推进/DERIVED 字段服务端落值/软删不计/无变化跳过全生效）；并发多子写→同一父汇总串行、终值正确。
 *
 * 边由测试直插 `md_dep`（rollup 字段的治理面声明保存是块4 单元④，此处先验引擎）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class RecalcIT {
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
    private val custApi = "rcust_$sfx"
    private val ordApi = "rord_$sfx"
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(zone).withNano(0) }
    private lateinit var custTable: String
    private var custObjId = ""
    private var ordObjId = ""
    private var balanceFieldId = ""
    private var amountFieldId = ""

    private fun jdbc() = JdbcTemplate(dataSource)

    private fun ctx(
        objectApi: String,
        recordId: String? = null,
        version: Long? = null,
    ) = WriteContext(objectApi, WriteIntent.UPSERT, WriteSource.UI, WriteActor("it-user", "it-dept"), "tr-" + Ulid.next(), now(), recordId, version)

    private fun awaitTable(
        area: LogicalArea,
        logical: String,
    ) {
        val f = provider.introspection.tableExists(area, logical)
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            val ok = (NamedParameterJdbcTemplate(dataSource).queryForObject(f.sql, MapSqlParameterSource(f.params), Long::class.java) ?: 0L) > 0
            if (ok) return
            TimeUnit.MILLISECONDS.sleep(200)
        }
        throw AssertionError("表 $area.$logical 未物化")
    }

    private fun objId(api: String) = jdbc().queryForObject("SELECT id FROM ${provider.namespace.qualified(LogicalArea.METADATA, "md_object")} WHERE api_name = ?", String::class.java, api)!!

    private fun fieldId(
        objApi: String,
        api: String,
    ): String = jdbc().queryForObject("SELECT f.id FROM ${provider.namespace.qualified(LogicalArea.METADATA, "md_field")} f JOIN ${provider.namespace.qualified(LogicalArea.METADATA, "md_object")} o ON f.object_id=o.id WHERE o.api_name=? AND f.api_name=?", String::class.java, objApi, api)!!

    private fun insertEdge() {
        NamedParameterJdbcTemplate(dataSource).update(
            "INSERT INTO ${provider.namespace.qualified(LogicalArea.METADATA, "md_dep")} (id, target_field_id, source_object_id, source_field_id, op) VALUES (:id, :t, :so, :sf, :op)",
            MapSqlParameterSource()
                .addValue("id", Ulid.next())
                .addValue("t", balanceFieldId)
                .addValue("so", ordObjId)
                .addValue("sf", amountFieldId)
                .addValue("op", "SUM"),
        )
    }

    @BeforeAll
    fun setup() {
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                apiName = custApi,
                label = "客户",
                kind = "PARENT",
                displayName = "{name}",
                fields =
                    listOf(
                        MetadataService.FieldCmd("name", "名称", "TEXT", required = true),
                        MetadataService.FieldCmd("balance", "余额", "DECIMAL", writePolicy = "DERIVED"),
                    ),
            ),
        )
        awaitTable(LogicalArea.ENTITY, custApi)
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                apiName = ordApi,
                label = "订单",
                kind = "CHILD",
                parentApi = custApi,
                displayName = "{amount}",
                fields = listOf(MetadataService.FieldCmd("amount", "金额", "DECIMAL", required = true)),
            ),
        )
        awaitTable(LogicalArea.ENTITY, ordApi)
        custTable = provider.namespace.qualified(LogicalArea.ENTITY, custApi)
        custObjId = objId(custApi)
        ordObjId = objId(ordApi)
        balanceFieldId = fieldId(custApi, "balance")
        amountFieldId = fieldId(ordApi, "amount")
        // 边三元组唯一，setup 插一次即可（各 @Test 共用）。
        insertEdge()
    }

    /** 读客户 balance（DERIVED 存 ext，两库都取原文判含值；此处只要子串命中金额字面量即证回写）。 */
    private fun balance(custId: String): String = jdbc().queryForObject("SELECT ext FROM $custTable WHERE id = ?", String::class.java, custId).toString()

    @Test
    fun `写子订单同事务重算父 balance`() {
        val cust = writes.write(ctx(custApi), RecordDraft(values = mapOf("name" to DraftValue.Text("C-$sfx")))).id
        writes.write(ctx(ordApi).copy(parentId = cust), RecordDraft(values = mapOf("amount" to DraftValue.Number("10"))))
        writes.write(ctx(ordApi).copy(parentId = cust), RecordDraft(values = mapOf("amount" to DraftValue.Number("5"))))
        assertThat(balance(cust)).`as`("recalc：balance=SUM(10,5)=15").contains("15")
    }

    @Test
    fun `软删子订单不计入父聚合`() {
        val cust = writes.write(ctx(custApi), RecordDraft(values = mapOf("name" to DraftValue.Text("S-$sfx")))).id
        val o1 = writes.write(ctx(ordApi).copy(parentId = cust), RecordDraft(values = mapOf("amount" to DraftValue.Number("8"))))
        writes.write(ctx(ordApi).copy(parentId = cust), RecordDraft(values = mapOf("amount" to DraftValue.Number("2"))))
        assertThat(balance(cust)).contains("10")
        // 软删 o1 → recalc 重算 balance=2（deleted_at IS NULL 过滤）
        writes.write(ctx(ordApi, recordId = o1.id, version = o1.version).copy(intent = WriteIntent.DELETE), RecordDraft())
        assertThat(balance(cust)).`as`("软删后 balance 应为 2").contains("2")
    }

    @Test
    fun `治理面 createField 声明 rollup 落 md_dep 并驱动 recalc`() {
        // 单元④路径：不直插 md_dep，改用 createField + RollupCmd 在既有 custApi 上加"ordercount ← COUNT(ordApi.amount)"
        // 声明（Order 元数据父就是 custApi，链路合法）。保存后写订单，recalc 应命中声明边并回写。
        val newFieldApi = "ordercount_$sfx"
        meta.createField(
            custApi,
            MetadataService.FieldCmd(
                apiName = newFieldApi,
                label = "订单数",
                logicalType = "NUMBER",
                rollup = MetadataService.RollupCmd(sourceObjectApi = ordApi, sourceFieldApi = "amount", op = "COUNT"),
            ),
        )
        val depCount =
            jdbc().queryForObject(
                "SELECT count(*) FROM ${provider.namespace.qualified(LogicalArea.METADATA, "md_dep")} d " +
                    "JOIN ${provider.namespace.qualified(LogicalArea.METADATA, "md_field")} f ON d.target_field_id = f.id " +
                    "JOIN ${provider.namespace.qualified(LogicalArea.METADATA, "md_object")} o ON f.object_id = o.id " +
                    "WHERE o.api_name = ? AND f.api_name = ?",
                Int::class.java,
                custApi,
                newFieldApi,
            )
        assertThat(depCount).`as`("createField+RollupCmd 应写一条 md_dep 边").isEqualTo(1)
        val cust = writes.write(ctx(custApi), RecordDraft(values = mapOf("name" to DraftValue.Text("CNT-$sfx")))).id
        writes.write(ctx(ordApi).copy(parentId = cust), RecordDraft(values = mapOf("amount" to DraftValue.Number("3"))))
        writes.write(ctx(ordApi).copy(parentId = cust), RecordDraft(values = mapOf("amount" to DraftValue.Number("5"))))
        val ext = jdbc().queryForObject("SELECT ext FROM $custTable WHERE id = ?", String::class.java, cust).toString()
        assertThat(ext).`as`("recalc 命中声明边、回写 $newFieldApi=2：$ext").contains(newFieldApi)
    }

    @Test
    fun `并发多子写两父汇总无死锁终值正确`() {
        // 2 父 × 各 3 子（对齐卡面"两父交叉改"摊薄单锁竞争）。并发写同一父的汇总锁经 recalc 串行；
        // advisory 锁是"等待超时"非 DB 死锁 → 遇 420 LOCK_RETRY 重试即可，终值应各 =7+7+7=21。
        val custs = (1..2).map { writes.write(ctx(custApi), RecordDraft(values = mapOf("name" to DraftValue.Text("K$it-$sfx")))).id }
        val tasks = mutableListOf<Callable<Unit>>()
        for (c in custs) {
            repeat(3) {
                tasks +=
                    Callable {
                        var attempt = 0
                        while (true) {
                            try {
                                writes.write(ctx(ordApi).copy(parentId = c), RecordDraft(values = mapOf("amount" to DraftValue.Number("7"))))
                                return@Callable
                            } catch (e: cn.x.ac.kteasy.core.kernel.KnownKteasyException) {
                                if ((e.data as? Map<*, *>)?.get("error_id") != "LOCK_RETRY" || ++attempt >= 8) throw e
                            }
                        }
                    }
            }
        }
        val pool = Executors.newFixedThreadPool(6)
        try {
            tasks.map { pool.submit(it) }.forEach { it.get(120, TimeUnit.SECONDS) }
            for (c in custs) assertThat(balance(c)).`as`("父 $c balance=21").contains("21")
        } finally {
            pool.shutdownNow()
        }
    }
}
