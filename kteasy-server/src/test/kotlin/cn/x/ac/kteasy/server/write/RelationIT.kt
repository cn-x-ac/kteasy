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
import cn.x.ac.kteasy.core.schema.SchemaDiff
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
import java.util.concurrent.TimeUnit

/**
 * 步骤卡 M1-07 块 3A · N2N 关联集合差量双库端到端（真连，pg/mysql 各一次，同一组期望）。
 *
 * 锁卡面 §2 的红线：更新关联集合＝**加/删差集、保留行整行不碰**——故"未变行的 ext 附加列存活"是核心断言：
 * 先手工插一条带 `ext={"note":"keep"}` 的关联行，再发一次把它仍在载荷里的 N2N 写，若实现走了全删重插，
 * 该行的 ext 会被抹成空 → 断言红。另测集合读回（查询侧 EXISTS 同源）、增删、空数组清空。
 *
 * r_ 表/列名一律经 [SchemaDiff] 命名口 + 快照解析（与物化、查询侧同源），本 IT 不拼裸方言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class RelationIT {
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
    private val hostApi = "rhost_$sfx"
    private val targetApi = "rtgt_$sfx"
    private val linkApi = "links"
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(zone).withNano(0) }
    private lateinit var relTable: String
    private lateinit var targetTable: String
    private lateinit var srcCol: String
    private lateinit var dstCol: String

    private fun jdbc() = JdbcTemplate(dataSource)

    private fun ctx(
        objectApi: String,
        recordId: String? = null,
        version: Long? = null,
    ) = WriteContext(objectApi, WriteIntent.UPSERT, WriteSource.UI, WriteActor("it-user", "it-dept"), "tr-" + Ulid.next(), now(), recordId, version)

    @BeforeAll
    fun setup() {
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                apiName = targetApi, label = "标签", kind = "PLAIN", displayName = "{name}",
                fields = listOf(MetadataService.FieldCmd("name", "名称", "TEXT", required = true)),
            ),
        )
        awaitTable(LogicalArea.ENTITY, targetApi)
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                apiName = hostApi, label = "单据", kind = "PARENT", displayName = "{code}",
                fields =
                    listOf(
                        MetadataService.FieldCmd("code", "编码", "TEXT", required = true),
                        MetadataService.FieldCmd(linkApi, "关联", "N2N", refObjectApi = targetApi),
                    ),
            ),
        )
        awaitTable(LogicalArea.ENTITY, hostApi)
        awaitTable(LogicalArea.RELATION, "${hostApi}_$linkApi")
        relTable = provider.namespace.qualified(LogicalArea.RELATION, "${hostApi}_$linkApi")
        targetTable = provider.namespace.qualified(LogicalArea.ENTITY, targetApi)
        srcCol = SchemaDiff.relationSourceColumn(hostApi)
        dstCol = SchemaDiff.relationTargetColumn(targetApi)
    }

    private fun awaitTable(
        area: LogicalArea,
        logical: String,
    ) {
        val f = provider.introspection.tableExists(area, logical)
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            val exists = (NamedParameterJdbcTemplate(dataSource).queryForObject(f.sql, MapSqlParameterSource(f.params), Long::class.java) ?: 0L) > 0
            if (exists) return
            TimeUnit.MILLISECONDS.sleep(200)
        }
        throw AssertionError("表 $area.$logical 未在 90s 内物化")
    }

    private fun newTarget(): String {
        val id = Ulid.next()
        NamedParameterJdbcTemplate(dataSource).update(
            "INSERT INTO $targetTable (id, ext) VALUES (:id, ${provider.json.bindJson("ext")})",
            MapSqlParameterSource().addValue("id", id).addValue("ext", """{"name":"T-$id"}"""),
        )
        return id
    }

    /** 该主机记录当前关联的目标 id 集。 */
    private fun linked(hostId: String): Set<String> =
        jdbc().queryForList("SELECT $dstCol FROM $relTable WHERE $srcCol = ?", String::class.java, hostId).map { it!! }.toSet()

    @Test
    fun `写关联集合可增删且保留行 ext 不丢`() {
        val t1 = newTarget()
        val t2 = newTarget()
        val t3 = newTarget()

        // 初次：关联 {t1,t2}（N2N 载荷走 fields=DraftValue.Many(目标 id)，不经 details）
        val created =
            writes.write(
                ctx(hostApi),
                RecordDraft(values = mapOf("code" to DraftValue.Text("H-$sfx"), linkApi to DraftValue.Many(listOf(t1, t2)))),
            )
        val hostId = created.id
        assertThat(linked(hostId)).`as`("初始关联 {t1,t2}").isEqualTo(setOf(t1, t2))

        // 给 t1 的关联行塞附加列 ext（模拟"已有附加列的保留行"）
        seedExt(hostId, t1, """{"note":"keep"}""")

        // 一次保存：保留 t1、移除 t2、新增 t3
        writes.write(
            ctx(hostApi, recordId = hostId, version = created.version),
            RecordDraft(values = mapOf("code" to DraftValue.Text("H-$sfx"), linkApi to DraftValue.Many(listOf(t1, t3)))),
        )
        assertThat(linked(hostId)).`as`("三集：保留 t1 + 新增 t3，删 t2").isEqualTo(setOf(t1, t3))
        val keptExt = jdbc().queryForObject("SELECT ext FROM $relTable WHERE $srcCol = ? AND $dstCol = ?", String::class.java, hostId, t1)?.toString()
        assertThat(keptExt).`as`("保留行未重插 → ext 附加列存活（全删重插会红）").contains("keep")
    }

    private fun seedExt(
        hostId: String,
        targetId: String,
        json: String,
    ) {
        NamedParameterJdbcTemplate(dataSource).update(
            "UPDATE $relTable SET ext = ${provider.json.bindJson("e")} WHERE $srcCol = :__s AND $dstCol = :__d",
            MapSqlParameterSource().addValue("e", json).addValue("__s", hostId).addValue("__d", targetId),
        )
    }

    @Test
    fun `空数组即清空全部关联`() {
        val a = newTarget()
        val b = newTarget()
        val created = writes.write(ctx(hostApi), RecordDraft(values = mapOf("code" to DraftValue.Text("CLR-$sfx"), linkApi to DraftValue.Many(listOf(a, b)))))
        assertThat(linked(created.id)).hasSize(2)
        val v = writes.write(ctx(hostApi, recordId = created.id, version = created.version), RecordDraft(values = mapOf("code" to DraftValue.Text("CLR-$sfx"), linkApi to DraftValue.Many(emptyList()))))
        assertThat(linked(created.id)).`as`("空载荷＝清空该关联集").isEmpty()
        // 恢复非空
        writes.write(ctx(hostApi, recordId = created.id, version = v.version), RecordDraft(values = mapOf("code" to DraftValue.Text("CLR-$sfx"), linkApi to DraftValue.Many(listOf(a)))))
        assertThat(linked(created.id)).isEqualTo(setOf(a))
    }
}
