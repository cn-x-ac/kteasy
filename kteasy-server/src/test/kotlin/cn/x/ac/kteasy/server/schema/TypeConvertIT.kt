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
package cn.x.ac.kteasy.server.schema

import cn.x.ac.kteasy.core.kernel.KnownKteasyException
import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.meta.StorageKind
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.server.md.MetadataRepository
import cn.x.ac.kteasy.server.md.MetadataService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * 步骤卡 M1-04 块 5 · 字段物理化（`type-convert`）双库集成。
 *
 * 三个独立用例各建对象、各**一次**物理化（执行器按对象串行队列，同对象连提多字段有重入队缺口 D6，故每方法只转一个字段）：
 * ① NUMBER→bigint 落真列 + storage_kind 翻 COLUMN + FILE 非法边拒绝不入队 + 后果告知；
 * ② DECIMAL→decimal(30,8) native 真列（D4）；③ DATE→date native 真列（D4）。makeIndex=false（D5/D7：ext 表达式索引对 temporal cast 两库皆不可用，策略归 M1-05）。
 * profile 由 `SPRING_PROFILES_ACTIVE` 定、门控 `KTEASY_IT_DB=true`；执行器异步，断言轮询；测试区字面量豁免红线⑤。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class TypeConvertIT {
    @Autowired
    lateinit var meta: MetadataService

    @Autowired
    lateinit var repo: MetadataRepository

    @Autowired
    lateinit var physicalize: PhysicalizeService

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var jobRepo: SchemaJobRepository

    private val suffix = Ulid.next().lowercase().takeLast(8)
    private val created = mutableListOf<String>()

    private fun name(base: String): String = "${base}_$suffix".also { created += it }

    private fun jdbc() = NamedParameterJdbcTemplate(dataSource)

    private fun columnExists(
        logical: String,
        column: String,
    ): Boolean {
        val f = provider.introspection.columnExists(LogicalArea.ENTITY, logical, column)
        return (jdbc().queryForObject(f.sql, MapSqlParameterSource(f.params), Long::class.java) ?: 0L) > 0
    }

    private fun await(
        timeoutMs: Long = 45_000,
        poll: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (poll()) return true
            TimeUnit.MILLISECONDS.sleep(200)
        }
        return poll()
    }

    @AfterEach
    fun cleanup() {
        val jt = JdbcTemplate(dataSource)
        created.reversed().forEach { logical ->
            runCatching { jt.execute("DROP TABLE IF EXISTS ${provider.namespace.qualified(LogicalArea.ENTITY, logical)} CASCADE") }
        }
    }

    private fun fieldCmd(
        api: String,
        type: String,
    ) = MetadataService.FieldCmd(apiName = api, label = api, logicalType = type)

    /** 建一个含 name(TEXT,主显) + 目标标量字段的对象，等建表物化到终态，返回 (对象, 目标字段)。 */
    private fun createWith(
        base: String,
        fieldApi: String,
        fieldType: String,
    ): Pair<cn.x.ac.kteasy.core.meta.MdObject, cn.x.ac.kteasy.core.meta.MdField> {
        val api = name(base)
        val obj =
            meta.createObject(
                MetadataService.ObjectCreateCmd(
                    apiName = api,
                    label = "客户",
                    kind = "PLAIN",
                    displayName = "{name}",
                    fields = listOf(fieldCmd("name", "TEXT"), fieldCmd(fieldApi, fieldType)),
                ),
            )
        assertThat(await { columnExists(api, "ext") }).`as`("$api 表应物化").isTrue()
        assertThat(await { !jobRepo.hasUnfinished(obj.id) }).`as`("$api 建表步应终态").isTrue()
        return obj to repo.findFieldByApi(obj.id, fieldApi)!!
    }

    @Test
    fun `NUMBER物理化落bigint真列且FILE非法边拒绝`() {
        val (obj, amount) = createWith("tnum", "amount", "NUMBER")
        assertThat(amount.storageKind).`as`("物理化前 EXT").isEqualTo(StorageKind.EXT)
        val r = physicalize.physicalize(amount.id)
        assertThat(r.targetStorageKind).isEqualTo(StorageKind.COLUMN.name)
        assertThat(r.consequence).contains("不丢数据")
        val api = obj.apiName
        assertThat(await { columnExists(api, "amount") && !jobRepo.hasUnfinished(obj.id) })
            .`as`("amount 应落 bigint 真列且作业终态；诊断=%s", jobRepo.listByObject(obj.id))
            .isTrue()
        assertThat(repo.findFieldById(amount.id)?.storageKind).`as`("读切换应 COLUMN").isEqualTo(StorageKind.COLUMN)

        // 非法边：FILE 不可物理化 → 抛错、不入队。
        val (obj2, attachment) = createWith("tfile", "attachment", "FILE")
        val before = jobRepo.listByObject(obj2.id).size
        assertThatThrownBy { physicalize.physicalize(attachment.id) }
            .isInstanceOf(KnownKteasyException::class.java)
        assertThat(jobRepo.listByObject(obj2.id).size).`as`("非法边不得入队").isEqualTo(before)
    }

    @Test
    fun `DECIMAL物理化落native decimal真列`() {
        val (obj, price) = createWith("tdec", "price", "DECIMAL")
        physicalize.physicalize(price.id)
        assertThat(await { columnExists(obj.apiName, "price") && !jobRepo.hasUnfinished(obj.id) })
            .`as`("price 应落 decimal(30,8) native 真列且终态；诊断=%s", jobRepo.listByObject(obj.id))
            .isTrue()
        assertThat(repo.findFieldById(price.id)?.storageKind).`as`("price 读切换应 COLUMN").isEqualTo(StorageKind.COLUMN)
    }

    @Test
    fun `DATE物理化落native date真列`() {
        val (obj, birthday) = createWith("tdt", "birthday", "DATE")
        physicalize.physicalize(birthday.id)
        assertThat(await { columnExists(obj.apiName, "birthday") && !jobRepo.hasUnfinished(obj.id) })
            .`as`("birthday 应落 date native 真列且终态；诊断=%s", jobRepo.listByObject(obj.id))
            .isTrue()
        assertThat(repo.findFieldById(birthday.id)?.storageKind).`as`("birthday 读切换应 COLUMN").isEqualTo(StorageKind.COLUMN)
    }
}
