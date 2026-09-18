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
package cn.x.ac.kteasy.server.md

import cn.x.ac.kteasy.core.kernel.Dialect
import cn.x.ac.kteasy.core.kernel.KteasyContext
import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.meta.LogicalType
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.server.md.MetadataService.FieldCmd
import cn.x.ac.kteasy.server.md.MetadataService.FieldUpdateCmd
import cn.x.ac.kteasy.server.md.MetadataService.ObjectCreateCmd
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * 步骤卡 M1-01 验收（GWT ①②③④，真起服 + 真库）：
 * ① 主+子项对象、10 标量+1 引用字段的行数/存储归属与图谱端点；
 * ② 事务回滚版本号不变 / 提交版本 +1 且新读含变更；
 * ③ 子项不挂主 → 420 + 人话 error_msg（重复 api_name 同证）；
 * ④ 复制对象连带字段。并发断言（GWT⑤）在 MetadataCacheConcurrencyTest。
 * 本机无库 skipped，CI service-matrix 双 Profile 真跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class MetadataGovernanceApiTest {
    @Autowired
    lateinit var environment: Environment

    @Autowired
    lateinit var context: KteasyContext

    @Autowired
    lateinit var service: MetadataService

    @Autowired
    lateinit var cache: MetadataGraphCache

    @Autowired
    lateinit var txTemplate: TransactionTemplate

    @Autowired
    lateinit var dataSource: DataSource

    private val dialect: String get() = if (context.dialect == Dialect.POSTGRESQL) "pg" else "mysql"

    private val client: MdTestClient by lazy {
        val port =
            requireNotNull(environment.getProperty("local.server.port")) { "RANDOM_PORT 未注入" }
                .toInt()
        MdTestClient(port)
    }

    private val s: String = MdTestSupport.suffix()
    private val custApi = "m01cust$s"
    private val parentApi = "m01main$s"
    private val childApi = "m01line$s"

    @AfterAll
    fun cleanup() {
        // 按本轮隔离后缀清理（先子后父：md_field.object_id ← md_object.id）
        val like = "%$s%"
        val physical = MdTestSupport.table(dialect, "md_object")
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    "DELETE FROM ${MdTestSupport.table(dialect, "md_field")} WHERE object_id IN (SELECT id FROM $physical WHERE api_name LIKE ?)",
                ).use { ps ->
                    ps.setString(1, like)
                    ps.executeUpdate()
                }
            conn.prepareStatement("DELETE FROM $physical WHERE api_name LIKE ?").use { ps ->
                ps.setString(1, like)
                ps.executeUpdate()
            }
        }
    }

    private fun field(
        api: String,
        type: String,
        extra: Map<String, Any?> = emptyMap(),
    ): String {
        // 键名引号必须单层：raw string 里多写一层会渲染成 ""key":（Jackson 在 'r' 处报 expect colon）
        val rest = extra.entries.joinToString(",") { ",\"${it.key}\":${it.value}" }
        return "{\"api_name\":\"$api\",\"label\":\"$api\",\"logical_type\":\"$type\"$rest}"
    }

    private fun createCust() {
        val resp =
            client.post(
                "/api/md/object",
                """
                {"api_name":"$custApi","label":"客户","kind":"PLAIN","name_field":"name",
                 "fields":[${field("name", "TEXT")},${field("credit", "NUMBER")}]}
                """.trimIndent(),
            )
        assertThat(resp.status).isEqualTo(200)
    }

    @Test
    @Order(1)
    fun `GWT1 主对象带 10 标量与 1 引用字段 - 存储归属与图谱端点正确`() {
        createCust()
        val scalars =
            listOf(
                field("name", "TEXT"),
                field("phone", "PHONE"),
                field("email", "EMAIL"),
                field("url", "URL"),
                field("age", "NUMBER"),
                field("amount", "DECIMAL"),
                field("birthday", "DATE"),
                field("created_ts", "DATETIME"),
                field("vip", "BOOL"),
                field("note", "TEXTAREA"),
            )
        val refField =
            field("cust", "REF", mapOf("ref_object" to "\"$custApi\""))
        val resp =
            client.post(
                "/api/md/object",
                """
                {"api_name":"$parentApi","label":"主对象","kind":"PARENT","name_field":"name",
                 "quick_search_fields":["name","phone"],
                 "fields":[${(scalars + refField).joinToString(",")}]}
                """.trimIndent(),
            )
        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.body).contains("\"error_code\":0")

        // 子项对象挂主
        val childResp =
            client.post(
                "/api/md/object",
                """
                {"api_name":"$childApi","label":"子项","kind":"CHILD","parent_object":"$parentApi",
                 "name_field":"name","fields":[${field("name", "TEXT")},${field("qty", "NUMBER")}]}
                """.trimIndent(),
            )
        assertThat(childResp.status).isEqualTo(200)

        // 图谱端点：完整图谱 + X-MD-Ver
        val graph = client.get("/api/md/object/$parentApi/graph")
        assertThat(graph.status).isEqualTo(200)
        assertThat(graph.header("X-MD-Ver")).isNotNull().isNotEmpty()
        assertThat(graph.body).contains("\"apiName\":\"$parentApi\"")
        // 11 字段（10 标量 + 1 引用）全在图谱中
        assertThat(graph.body).contains("\"cust\"")
        // 存储归属：引用=COLUMN、标量=EXT（服务层逐字段断言，键序随 Jackson 3 字母序不可作相邻子串）
        val graphFields = service.buildGraph(cache.snapshot(), parentApi).fields
        assertThat(graphFields.first { it.apiName == "cust" }.storageKind).isEqualTo(StorageKind.COLUMN)
        assertThat(graphFields.first { it.apiName == "cust" }.logicalType).isEqualTo(LogicalType.REF)
        assertThat(graphFields.filter { it.logicalType != LogicalType.REF })
            .allSatisfy { assertThat(it.storageKind).isEqualTo(StorageKind.EXT) }
        assertThat(graph.body).contains("\"logicalType\":\"REF\"")
        assertThat(graph.body).contains("\"storageKind\":\"COLUMN\"")
        assertThat(graph.body).contains("\"storageKind\":\"EXT\"")
        // 子项挂主：图谱 parent 指回主对象
        val childGraph = client.get("/api/md/object/$childApi/graph")
        assertThat(childGraph.body).contains("\"apiName\":\"$parentApi\"")

        // 行数：md_field 对主对象恰 11 行
        val fieldCount = countFields(parentApi)
        assertThat(fieldCount).isEqualTo(11)
    }

    @Test
    @Order(2)
    fun `GWT3 子项不挂主与重复 api_name - 420 加人话`() {
        // 子项不挂主
        val noParent =
            client.post(
                "/api/md/object",
                """
                {"api_name":"m01orphan$s","label":"孤儿","kind":"CHILD","name_field":"name",
                 "fields":[${field("name", "TEXT")}]}
                """.trimIndent(),
            )
        assertThat(noParent.status).isEqualTo(409)
        assertThat(noParent.body).contains("\"error_code\":420")
        assertThat(noParent.body).contains("必须挂主")
        // 重复 api_name
        val dup = client.post("/api/md/object", """{"api_name":"$custApi","label":"x","kind":"PLAIN","name_field":"name","fields":[${field("name", "TEXT")}]}""")
        assertThat(dup.status).isEqualTo(409)
        assertThat(dup.body).contains("\"error_code\":420")
        assertThat(dup.body).contains("已存在")
    }

    @Test
    @Order(3)
    fun `GWT2 回滚版本不变 - 提交版本加一且新读含变更`() {
        val before = cache.currentVersion()
        val fieldApi = "rollf" + s.takeLast(4)
        // 回滚：外层事务 setRollbackOnly，内层 mdWrite（REQUIRED）并入同一事务 → 事件丢弃
        txTemplate.execute { status ->
            service.createField(
                parentApi,
                FieldCmd(apiName = fieldApi, label = "回滚字段", logicalType = "TEXT"),
            )
            status.setRollbackOnly()
        }
        assertThat(cache.currentVersion()).isEqualTo(before)
        assertThat(client.get("/api/md/object/$parentApi/graph").body).doesNotContain(fieldApi)

        // 提交：版本 +1 且新读含变更
        service.createField(parentApi, FieldCmd(apiName = fieldApi, label = "新字段", logicalType = "TEXT"))
        assertThat(cache.currentVersion()).isEqualTo(before + 1)
        assertThat(client.get("/api/md/object/$parentApi/graph").body).contains(fieldApi)

        // 版本读取接口与缓存一致
        val vResp = client.get("/api/md/version")
        assertThat(vResp.body).contains("\"version\":${cache.currentVersion()}")

        // 字段 PATCH 与逻辑删除
        val fieldId =
            service
                .loadSnapshot()
                .fields
                .first { it.apiName == fieldApi && it.objectId == objectIdOf(parentApi) }
                .id
        val patched = client.patch("/api/md/field/$fieldId", """{"label":"改名字段","required":true}""")
        assertThat(patched.status).isEqualTo(200)
        assertThat(patched.body).contains("改名字段")
        val disabled = client.delete("/api/md/field/$fieldId")
        assertThat(disabled.status).isEqualTo(200)
        assertThat(disabled.body).contains("\"enabled\":false")
    }

    @Test
    @Order(4)
    fun `GWT4 复制对象连带字段`() {
        val copyApi = "m01copy$s"
        val resp = client.post("/api/md/object/$parentApi/copy", """{"api_name":"$copyApi","label":"副本"}""")
        assertThat(resp.status).isEqualTo(200)
        val graph = client.get("/api/md/object/$copyApi/graph")
        assertThat(graph.status).isEqualTo(200)
        // 副本 11 字段齐全，引用字段仍指向客户对象（存储归属经服务层断言）
        assertThat(countFields(copyApi)).isEqualTo(11)
        val copyFields = service.buildGraph(cache.snapshot(), copyApi).fields
        val sourceFields = service.buildGraph(cache.snapshot(), parentApi).fields
        assertThat(copyFields.first { it.apiName == "cust" }.storageKind).isEqualTo(StorageKind.COLUMN)
        assertThat(copyFields.first { it.apiName == "cust" }.refObjectId)
            .isEqualTo(sourceFields.first { it.apiName == "cust" }.refObjectId)
        assertThat(graph.body).contains("\"logicalType\":\"REF\"")
        // 名称字段重映射到副本自身字段
        assertThat(graph.body).contains("\"nameFieldId\"")
    }

    private fun countFields(objectApi: String): Int {
        val objectId = objectIdOf(objectApi)
        return service.loadSnapshot().fields.count { it.objectId == objectId }
    }

    private fun objectIdOf(objectApi: String): String =
        service
            .loadSnapshot()
            .objects
            .first { it.apiName == objectApi }
            .id
}
