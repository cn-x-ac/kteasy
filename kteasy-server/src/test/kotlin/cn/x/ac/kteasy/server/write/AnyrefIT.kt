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

import cn.x.ac.kteasy.core.kernel.ApiError
import cn.x.ac.kteasy.core.kernel.KnownKteasyException
import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.write.DraftValue
import cn.x.ac.kteasy.core.write.RecordDraft
import cn.x.ac.kteasy.core.write.WriteActor
import cn.x.ac.kteasy.core.write.WriteContext
import cn.x.ac.kteasy.core.write.WriteErrors
import cn.x.ac.kteasy.core.write.WriteIntent
import cn.x.ac.kteasy.core.write.WriteSource
import cn.x.ac.kteasy.server.md.MetadataService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
 * 步骤卡 M1-07 块 3B · ANYREF 伴生列软校验双库端到端（真连，pg/mysql 各一次，同一组期望）。
 *
 * 锁两件事：① 合法 `{hint}:{id}` → 主列存 id、`<field>_obj` 列存 hint（对象 api_name）；
 * ② hint ∉ 允许对象集 → 410 `ANYREF_OBJ_MISMATCH`（ANYREF 无真 FK，这是唯一引用完整性闸门）。
 * **软校验只判 hint 归属、不判目标行是否真实存在**——这是 ANYREF 刻意"无外键"的语义（硬存在性归带 FK 的 REF）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class AnyrefIT {
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
    private val hostApi = "ahost_$sfx"
    private val objA = "aobj_a_$sfx"
    private val objB = "aobj_b_$sfx"
    private val refApi = "polymorph"
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(zone).withNano(0) }
    private lateinit var hostTable: String

    private fun jdbc() = JdbcTemplate(dataSource)

    private fun ctx(
        objectApi: String,
        recordId: String? = null,
        version: Long? = null,
    ) = WriteContext(objectApi, WriteIntent.UPSERT, WriteSource.UI, WriteActor("it-user", "it-dept"), "tr-" + Ulid.next(), now(), recordId, version)

    @BeforeAll
    fun setup() {
        meta.createObject(MetadataService.ObjectCreateCmd(objA, "A对象", "PLAIN", displayName = "{name}", fields = listOf(MetadataService.FieldCmd("name", "名称", "TEXT", required = true))))
        awaitTable(LogicalArea.ENTITY, objA)
        meta.createObject(MetadataService.ObjectCreateCmd(objB, "B对象", "PLAIN", displayName = "{name}", fields = listOf(MetadataService.FieldCmd("name", "名称", "TEXT", required = true))))
        awaitTable(LogicalArea.ENTITY, objB)
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                apiName = hostApi,
                label = "宿主",
                kind = "PARENT",
                displayName = "{code}",
                fields =
                    listOf(
                        MetadataService.FieldCmd("code", "编码", "TEXT", required = true),
                        MetadataService.FieldCmd(refApi, "多态引用", "ANYREF", refAnyObjsJson = """["$objA","$objB"]"""),
                    ),
            ),
        )
        awaitTable(LogicalArea.ENTITY, hostApi)
        hostTable = provider.namespace.qualified(LogicalArea.ENTITY, hostApi)
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

    @Test
    fun `ANYREF 合法归属拆主列 id 与 _obj 伴生列`() {
        val targetId = Ulid.next()
        val created = writes.write(ctx(hostApi), RecordDraft(values = mapOf("code" to DraftValue.Text("AR-$sfx"), refApi to DraftValue.Text("$objA:$targetId"))))
        val row = jdbc().queryForMap("SELECT $refApi, ${refApi}_obj FROM $hostTable WHERE id = ?", created.id)
        assertThat(row[refApi]).`as`("主列存 id（非整串，ID_LEN 容得下）").isEqualTo(targetId)
        assertThat(row["${refApi}_obj"]).`as`("伴生列存被引用对象 api").isEqualTo(objA)
    }

    @Test
    fun `ANYREF 目标对象不在允许集出 410 ANYREF_OBJ_MISMATCH`() {
        assertThatThrownBy {
            writes.write(ctx(hostApi), RecordDraft(values = mapOf("code" to DraftValue.Text("BAD-$sfx"), refApi to DraftValue.Text("ghost:" + Ulid.next()))))
        }.isInstanceOfSatisfying(KnownKteasyException::class.java) {
            assertThat(it.apiError).isEqualTo(ApiError.INVALID_PARAM)
            val fields = (it.data as Map<*, *>)["fields"] as List<*>
            assertThat((fields[0] as Map<*, *>)["error_id"]).isEqualTo(WriteErrors.ID_ANYREF_OBJ_MISMATCH)
        }
    }

    @Test
    fun `ANYREF 切值与清值往返不产生假变更`() {
        val t1 = Ulid.next()
        val t2 = Ulid.next()
        val created = writes.write(ctx(hostApi), RecordDraft(values = mapOf("code" to DraftValue.Text("RT-$sfx"), refApi to DraftValue.Text("$objB:$t1"))))
        // 原样回传同一 ANYREF（读回应是 {hint}:{id}）→ 无变化跳过、不推版本
        val sameAgain = writes.write(ctx(hostApi, recordId = created.id, version = created.version), RecordDraft(values = mapOf("code" to DraftValue.Text("RT-$sfx"), refApi to DraftValue.Text("$objB:$t1"))))
        assertThat(sameAgain.version).`as`("同值 ANYREF 提交不推 row_version（读回对称）").isEqualTo(created.version)
        // 换引用到 A 对象的另一 id → 版本推进、_obj 变 A
        val moved = writes.write(ctx(hostApi, recordId = created.id, version = created.version), RecordDraft(values = mapOf("code" to DraftValue.Text("RT-$sfx"), refApi to DraftValue.Text("$objA:$t2"))))
        assertThat(moved.version).isEqualTo(created.version + 1)
        val row = jdbc().queryForMap("SELECT $refApi, ${refApi}_obj FROM $hostTable WHERE id = ?", created.id)
        assertThat(row[refApi]).isEqualTo(t2)
        assertThat(row["${refApi}_obj"]).isEqualTo(objA)
    }
}
