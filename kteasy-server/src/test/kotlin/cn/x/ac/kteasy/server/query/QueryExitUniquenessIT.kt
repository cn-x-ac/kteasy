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
package cn.x.ac.kteasy.server.query

import cn.x.ac.kteasy.core.kernel.Ulid
import cn.x.ac.kteasy.core.meta.SystemColumns
import cn.x.ac.kteasy.core.query.CmpOp
import cn.x.ac.kteasy.core.query.Literal
import cn.x.ac.kteasy.core.query.NoFilterCallSite
import cn.x.ac.kteasy.core.query.PrivilegeInjector
import cn.x.ac.kteasy.core.query.QueryContext
import cn.x.ac.kteasy.core.query.QueryPlan
import cn.x.ac.kteasy.core.query.RExpr
import cn.x.ac.kteasy.core.query.ROOT_ALIAS
import cn.x.ac.kteasy.core.query.Rhs
import cn.x.ac.kteasy.core.query.TypedOperand
import cn.x.ac.kteasy.core.query.ValueLocation
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.core.schema.dialect.ValueCast
import cn.x.ac.kteasy.server.md.MetadataGraphCache
import cn.x.ac.kteasy.server.md.MetadataService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * 块5 · 查询出口唯一性**红绿对**的红侧（卡面验收④）：把注入器替换为恒假后，同一出口的查询必须全空——
 * 若仍返回行即证明存在绕过注入的旁路（M2-02 插桩将无处可逃）。绿侧对照＝`EqlQueryIT`（默认透传下同形查询有行）。
 *
 * **对象级替换协作者**（`QueryEngine(cache, provider, jdbc, 恒假注入器)`），不走 `@TestConfiguration`：
 * 后者会新造 Spring 上下文＝多持一份 Hikari 池，CI 单 JVM 多上下文会打满 PG 100 连接（§E36；本 IT 首版即栽在
 * `flywayInitializer` 拿不到连接）。恒假谓词以「id = 永不匹配 ULID」实现，与 M2 真权限注入同形（追加最外层 AND）。
 *
 * profile 由 `SPRING_PROFILES_ACTIVE` 定、门控 `KTEASY_IT_DB=true`。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class QueryExitUniquenessIT {
    @Autowired
    lateinit var meta: MetadataService

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var cache: MetadataGraphCache

    @Autowired
    lateinit var dataSource: javax.sql.DataSource

    private val sfx = Ulid.next().lowercase().takeLast(8)
    private val api = "qdeny_$sfx"

    /** 恒假注入器：任何计划都 AND 一条永不匹配的最外层谓词。 */
    private val rejectAll =
        object : PrivilegeInjector {
            private val never = Ulid.next()

            override fun inject(
                plan: QueryPlan,
                ctx: QueryContext,
            ): QueryPlan {
                val deny =
                    RExpr.Cmp(
                        ValueLocation.Column(ROOT_ALIAS, SystemColumns.ID),
                        CmpOp.EQ,
                        Rhs.Val(TypedOperand(ValueCast.TEXT, Literal.Str(never))),
                    )
                return plan.copy(where = plan.where?.let { RExpr.And(listOf(it, deny)) } ?: deny)
            }
        }

    private fun jdbc() = NamedParameterJdbcTemplate(dataSource)

    private fun engineWith(injector: PrivilegeInjector) = QueryEngine(cache, provider, jdbc(), injector)

    private fun tableExists(): Boolean {
        val f = provider.introspection.tableExists(LogicalArea.ENTITY, api)
        return (
            jdbc().queryForObject(
                f.sql,
                org.springframework.jdbc.core.namedparam
                    .MapSqlParameterSource(f.params),
                Long::class.java,
            ) ?: 0L
        ) > 0
    }

    @AfterAll
    fun cleanup() {
        runCatching {
            jdbc().update("DROP TABLE IF EXISTS ${provider.namespace.qualified(LogicalArea.ENTITY, api)} CASCADE", emptyMap<String, Any?>())
        }
    }

    @Test
    fun `注入器换恒假后 唯一出口零行 且免过滤通道 403`() {
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                apiName = api,
                label = "拒全部",
                kind = "PLAIN",
                displayName = "{name}",
                fields = listOf(MetadataService.FieldCmd("name", "name", "TEXT")),
            ),
        )
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && !tableExists()) TimeUnit.MILLISECONDS.sleep(200)
        assertThat(tableExists()).`as`("对象表应物化").isTrue()

        jdbc().update(
            "INSERT INTO ${provider.namespace.qualified(LogicalArea.ENTITY, api)} (id, ext) VALUES (:id, ${provider.json.bindJson("ext")})",
            mapOf("id" to Ulid.next(), "ext" to "{\"name\":\"张三\"}"),
        )

        val ctx = QueryContext(userId = null, now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")))
        // 绿：默认透传注入器 → 有行
        val green =
            engineWith(
                cn.x.ac.kteasy.core.query
                    .PassthroughPrivilegeInjector(),
            ).run("select name from $api", ctx)
        assertThat(green.rows).`as`("透传注入器下同出口应查到行").isNotEmpty()
        // 红：恒假注入器 → 零行（出口唯一，绕不过注入）
        val red = engineWith(rejectAll).run("select name from $api", ctx)
        assertThat(red.rows).`as`("注入器拒绝全部后不得有任何行（否则存在旁路）").isEmpty()

        // 免过滤通道：白名单为空 → 任何调用点 403
        assertThatThrownBy { engineWith(rejectAll).runNoFilter("select name from $api", ctx, NoFilterCallSite.DASHBOARD_ROLLOUT) }
            .isInstanceOf(cn.x.ac.kteasy.core.kernel.KnownKteasyException::class.java)
            .matches { (it as cn.x.ac.kteasy.core.kernel.KnownKteasyException).apiError == cn.x.ac.kteasy.core.kernel.ApiError.FORBIDDEN }
    }
}
