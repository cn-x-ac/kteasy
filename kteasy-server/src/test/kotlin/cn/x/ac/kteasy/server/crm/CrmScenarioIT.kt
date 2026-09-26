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
package cn.x.ac.kteasy.server.crm

import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.query.QueryContext
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.write.DraftValue
import cn.x.ac.kteasy.core.write.RecordDraft
import cn.x.ac.kteasy.core.write.WriteActor
import cn.x.ac.kteasy.core.write.WriteContext
import cn.x.ac.kteasy.core.write.WriteIntent
import cn.x.ac.kteasy.core.write.WriteSource
import cn.x.ac.kteasy.server.md.MetadataService
import cn.x.ac.kteasy.server.query.QueryEngine
import cn.x.ac.kteasy.server.query.QueryResult
import cn.x.ac.kteasy.server.write.WriteService
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
import java.util.concurrent.TimeUnit

/**
 * **CRM 剧本 v0**（M1 合闸交付物；总纲 §03「建对象→字段→写→EQL 查」，此后每里程碑末重跑）。
 *
 * 一个迷你 CRM 把 M1-01~07 全链在**双库真连**下端到端跑通、彼此咬合：
 * 建对象(主+子)→加字段（零 DDL 复测）→写（子项 details 三集 + N2N 关联 + ANYREF 软校验）→
 * rollup recalc（子金额汇总回写父字段）→ EQL 查询（属性谓词 + has 关联 + 汇总值过滤）。
 * 单条小数据量、非 50w/1000 重负载，本机秒级可跑；重负载基线归 PerfSmokeIT/专用环境。
 *
 * 对象关系：Account(主) —< Opp(子, 金额) 且 Account.annual=SUM(Opp.amount)；
 * Account —N2N→ Industry(行业)；Account.owner —ANYREF→ Contact(联系人)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class CrmScenarioIT {
    @Autowired
    lateinit var meta: MetadataService

    @Autowired
    lateinit var writes: WriteService

    @Autowired
    lateinit var engine: QueryEngine

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var dataSource: javax.sql.DataSource

    private val zone = ZoneId.of("Asia/Shanghai")
    private val sfx = Ulid.next().lowercase().takeLast(8)
    private val accApi = "crm_acc_$sfx"
    private val oppApi = "crm_opp_$sfx"
    private val indApi = "crm_ind_$sfx"
    private val conApi = "crm_con_$sfx"
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(zone).withNano(0) }

    private fun jdbc() = JdbcTemplate(dataSource)

    private fun ctx(
        objectApi: String,
        recordId: String? = null,
        version: Long? = null,
    ) = WriteContext(objectApi, WriteIntent.UPSERT, WriteSource.UI, WriteActor("crm-user", "crm-dept"), "crm-" + Ulid.next(), now(), recordId, version)

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

    private fun run(eql: String): QueryResult = engine.run(eql, QueryContext(userId = null, now = now()))

    private fun namesOf(r: QueryResult): Set<String> = r.rows.map { (it["c0"] ?: it["name"]) as String }.toSet()

    @BeforeAll
    fun buildCrm() {
        // 独立对象：行业、联系人
        meta.createObject(MetadataService.ObjectCreateCmd(indApi, "行业", "PLAIN", displayName = "{name}", fields = listOf(MetadataService.FieldCmd("name", "名称", "TEXT", required = true))))
        awaitTable(LogicalArea.ENTITY, indApi)
        meta.createObject(MetadataService.ObjectCreateCmd(conApi, "联系人", "PLAIN", displayName = "{name}", fields = listOf(MetadataService.FieldCmd("name", "名称", "TEXT", required = true))))
        awaitTable(LogicalArea.ENTITY, conApi)
        // 主对象 Account：名称 + N2N 行业 + ANYREF 负责人（annual 汇总字段待 Opp 建好后用 createField 追加，避免源对象先于其存在的鸡生蛋）
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                accApi,
                "客户",
                "PARENT",
                displayName = "{name}",
                fields =
                    listOf(
                        MetadataService.FieldCmd("name", "名称", "TEXT", required = true),
                        MetadataService.FieldCmd("industry", "行业", "N2N", refObjectApi = indApi),
                        MetadataService.FieldCmd("owner", "负责人", "ANYREF", refAnyObjsJson = """["$conApi"]"""),
                    ),
            ),
        )
        awaitTable(LogicalArea.ENTITY, accApi)
        // 子对象 Opp（金额）
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                oppApi,
                "商机",
                "CHILD",
                parentApi = accApi,
                displayName = "{amount}",
                fields = listOf(MetadataService.FieldCmd("amount", "金额", "DECIMAL", required = true)),
            ),
        )
        awaitTable(LogicalArea.ENTITY, oppApi)
        awaitTable(LogicalArea.RELATION, "${accApi}_industry")
        // rollup 声明（源 Opp 此刻已存在）：Account.annual = SUM(Opp.amount)，经治理面 createField 落 md_dep
        meta.createField(
            accApi,
            MetadataService.FieldCmd(
                apiName = "annual",
                label = "年度总额",
                logicalType = "DECIMAL",
                rollup = MetadataService.RollupCmd(sourceObjectApi = oppApi, sourceFieldApi = "amount", op = "SUM"),
            ),
        )
    }

    @Test
    fun `迷你CRM端到端_建字段写子项关联负责人汇总查询全链咬合`() {
        // ① 加字段零 DDL 复测：向既有 Account 追加一个备注字段，不重建表即可读写。
        meta.createField(accApi, MetadataService.FieldCmd("note", "备注", "TEXT"))
        // ② 关联种子：两个行业 + 一个联系人
        val ind1 = writes.write(ctx(indApi), RecordDraft(values = mapOf("name" to DraftValue.Text("制造-$sfx")))).id
        val ind2 = writes.write(ctx(indApi), RecordDraft(values = mapOf("name" to DraftValue.Text("零售-$sfx")))).id
        val con = writes.write(ctx(conApi), RecordDraft(values = mapOf("name" to DraftValue.Text("张三-$sfx")))).id

        // ③ 写客户：带子项 Opp×2（details 三集新建）+ N2N 行业 + ANYREF 负责人 + 备注 → recalc annual=SUM(30,20)=50
        val acc =
            writes.write(
                ctx(accApi),
                RecordDraft(
                    values =
                        mapOf(
                            "name" to DraftValue.Text("_ACME-$sfx"),
                            "note" to DraftValue.Text("重点客户"),
                            "industry" to DraftValue.Many(listOf(ind1, ind2)),
                            "owner" to DraftValue.Text("$conApi:$con"),
                        ),
                    details =
                        mapOf(
                            oppApi to
                                listOf(
                                    cn.x.ac.kteasy.core.write
                                        .DetailRow(fields = mapOf("amount" to DraftValue.Number("30"))),
                                    cn.x.ac.kteasy.core.write
                                        .DetailRow(fields = mapOf("amount" to DraftValue.Number("20"))),
                                ),
                        ),
                ),
            )
        assertThat(acc.diff).`as`("客户创建成功")

        // ④ recalc：子金额汇总回写父 annual=50，零 DDL 新字段 note 亦在
        val ext = jdbc().queryForObject("SELECT ext FROM ${provider.namespace.qualified(LogicalArea.ENTITY, accApi)} WHERE id = ?", String::class.java, acc.id).toString()
        assertThat(ext)
            .`as`("recalc annual + 零DDL note：$ext")
            .contains("annual")
            .contains("50")
            .contains("note")

        // ⑤ EQL 查：汇总值过滤 + has(N2N 关联) + ANYREF 读回 {hint}:{id}
        assertThat(namesOf(run("select name from $accApi where annual > 40"))).`as`("annual>40 命中 ACME").contains("_ACME-$sfx")
        assertThat(namesOf(run("select name from $accApi where annual > 80"))).`as`("annual>80 不命中").doesNotContain("_ACME-$sfx")
        assertThat(namesOf(run("select name from $accApi where has(industry)"))).`as`("has(industry) N2N 关联可读回").contains("_ACME-$sfx")

        // ⑥ 改子项（details 三集：留 1、删 1、增 1）→ recalc 重算 annual
        val oppIds = jdbc().queryForList("SELECT id FROM ${provider.namespace.qualified(LogicalArea.ENTITY, oppApi)} WHERE parent_id = ? AND deleted_at IS NULL", String::class.java, acc.id).map { it!! }
        val keepId = oppIds.first()
        writes.write(
            ctx(accApi, recordId = acc.id, version = acc.version),
            RecordDraft(
                values = mapOf("name" to DraftValue.Text("_ACME-$sfx")),
                details =
                    mapOf(
                        oppApi to
                            listOf(
                                cn.x.ac.kteasy.core.write
                                    .DetailRow(keepId, mapOf("amount" to DraftValue.Number("30"))), // 留原值
                                cn.x.ac.kteasy.core.write
                                    .DetailRow(fields = mapOf("amount" to DraftValue.Number("100"))), // 新增
                            ),
                    ),
            ),
        )
        // 删了 oppIds.last()（amount 20）、增了 100 → annual = 30+100 = 130；EQL 复验
        assertThat(namesOf(run("select name from $accApi where annual > 100"))).`as`("改子项后 annual 重算至 130").contains("_ACME-$sfx")
    }
}
