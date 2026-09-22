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
import cn.x.ac.kteasy.core.query.PrivilegeInjector
import cn.x.ac.kteasy.core.query.QueryContext
import cn.x.ac.kteasy.core.query.QueryPlan
import cn.x.ac.kteasy.core.schema.dialect.LogicalArea
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.server.md.MetadataService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 块5 · 查询出口唯一性**红绿对**的红侧（卡面验收④）：把注入器换成「拒绝全部」后，同一出口的查询必须全空——
 * 若仍有行返回即证明存在绕过注入的旁路（M2-02 插桩将无处可逃）。
 *
 * 绿侧对照＝`EqlQueryIT`（默认透传下同形查询有行）。这里的"拒绝"用「id = 永不匹配的 ULID」谓词实现恒假，
 * 与 M2-02 真权限谓词同形（追加最外层 AND）。另证 `runNoFilter` 白名单为空时任何调用点 403。
 *
 * profile 由 `SPRING_PROFILES_ACTIVE` 定、门控 `KTEASY_IT_DB=true`。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = [QueryExitUniquenessIT.RejectAllInjectorCfg::class, cn.x.ac.kteasy.server.KteasyApplication::class],
    // 允许本测试上下文以同名 bean 覆盖默认 PrivilegeInjector（仅此测试域生效，生产装配仍禁覆盖）
    properties = ["spring.main.allow-bean-definition-overriding=true"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "KTEASY_IT_DB", matches = "true")
class QueryExitUniquenessIT {
    @TestConfiguration
    class RejectAllInjectorCfg {
        /** 恒假注入器：追加「id = 永不匹配」最外层 AND——任何计划都查不出行。 */
        @Bean
        fun privilegeInjector(): PrivilegeInjector =
            object : PrivilegeInjector {
                private val never = Ulid.next()

                override fun inject(
                    plan: QueryPlan,
                    ctx: QueryContext,
                ): QueryPlan {
                    val deny =
                        cn.x.ac.kteasy.core.query.RExpr.Cmp(
                            cn.x.ac.kteasy.core.query.ValueLocation
                                .Column(cn.x.ac.kteasy.core.query.ROOT_ALIAS, cn.x.ac.kteasy.core.meta.SystemColumns.ID),
                            cn.x.ac.kteasy.core.query.CmpOp.EQ,
                            cn.x.ac.kteasy.core.query.Rhs
                                .Val(
                                    cn.x.ac.kteasy.core.query
                                        .TypedOperand(
                                            cn.x.ac.kteasy.core.schema.dialect.ValueCast.TEXT,
                                            cn.x.ac.kteasy.core.query.Literal
                                                .Str(never),
                                        ),
                                ),
                        )
                    val where =
                        plan.where?.let {
                            cn.x.ac.kteasy.core.query.RExpr
                                .And(listOf(it, deny))
                        } ?: deny
                    return plan.copy(where = where)
                }
            }
    }

    @Autowired
    lateinit var meta: MetadataService

    @Autowired
    lateinit var provider: SchemaProvider

    @Autowired
    lateinit var engine: QueryEngine

    @Autowired
    lateinit var dataSource: javax.sql.DataSource

    private val sfx = Ulid.next().lowercase().takeLast(8)
    private val api = "qdeny_$sfx"

    @AfterAll
    fun cleanup() {
        runCatching {
            JdbcTemplate(dataSource).execute("DROP TABLE IF EXISTS ${provider.namespace.qualified(LogicalArea.ENTITY, api)} CASCADE")
        }
    }

    @Test
    fun `注入器拒绝全部后 同出口查询全空 且免过滤通道 403`() {
        meta.createObject(
            MetadataService.ObjectCreateCmd(
                apiName = api,
                label = "拒全部",
                kind = "PLAIN",
                displayName = "{name}",
                fields = listOf(MetadataService.FieldCmd("name", "name", "TEXT")),
            ),
        )
        // 参数化插入必须走 NamedParameterJdbcTemplate（裸 JdbcTemplate 会把参数表当单值 → 驱动 setMap/hstore）
        val jt =
            org.springframework.jdbc.core.namedparam
                .NamedParameterJdbcTemplate(dataSource)
        // 等表落成（存在性探测的方言口）
        val qual = provider.namespace.qualified(LogicalArea.ENTITY, api)
        var ready = false
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && !ready) {
            ready =
                runCatching {
                    jt.queryForObject("SELECT count(*) FROM $qual", emptyMap<String, Any?>(), Long::class.java)
                }.getOrNull() != null
            if (!ready) Thread.sleep(200)
        }
        assertThat(ready).`as`("对象表应物化").isTrue()
        jt.update(
            "INSERT INTO $qual (id, ext) VALUES (:id, ${provider.json.bindJson("ext")})",
            mapOf("id" to Ulid.next(), "ext" to "{\"name\":\"张三\"}"),
        )
        // 红侧：拒绝全部 → 唯一出口查不出任何行
        val ctx = QueryContext(userId = null, now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")))
        val result = engine.run("select name from $api where name ~ 'zhang'", ctx)
        assertThat(result.rows).`as`("注入器拒绝全部后不得有任何行（否则存在旁路）").isEmpty()

        // 免过滤通道在白名单为空时必 403
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                engine.runNoFilter("select name from $api", ctx, cn.x.ac.kteasy.core.query.NoFilterCallSite.DASHBOARD_ROLLOUT)
            }.isInstanceOf(cn.x.ac.kteasy.core.kernel.KnownKteasyException::class.java)
            .matches { (it as cn.x.ac.kteasy.core.kernel.KnownKteasyException).apiError == cn.x.ac.kteasy.core.kernel.ApiError.FORBIDDEN }
    }
}
