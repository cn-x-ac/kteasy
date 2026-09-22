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

import cn.x.ac.kteasy.core.query.DEFAULT_LIMIT
import cn.x.ac.kteasy.core.query.EqlCompiler
import cn.x.ac.kteasy.core.query.EqlErrors
import cn.x.ac.kteasy.core.query.EqlParser
import cn.x.ac.kteasy.core.query.NoFilterCallSite
import cn.x.ac.kteasy.core.query.NoFilterWhitelist
import cn.x.ac.kteasy.core.query.PrivilegeInjector
import cn.x.ac.kteasy.core.query.QueryContext
import cn.x.ac.kteasy.core.query.SelectPlan
import cn.x.ac.kteasy.core.query.SqlRenderer
import cn.x.ac.kteasy.core.schema.dialect.SchemaProvider
import cn.x.ac.kteasy.server.md.MetadataGraphCache
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet

/**
 * 查询层**唯一出口**（卡面 §3：查询出口唯一化）。所有数据查询必须经 `run`/`runNoFilter` 两扇门之一，
 * 共用同一条「解析→编译→（受权限门时）注入→渲染→执行」管线。红绿对测（块5）把注入器换成"拒绝全部"，
 * 若任一查询仍能返回行即证明存在旁路 → 全红。M2-02 只需替换 [PrivilegeInjector] bean，本类无感。
 */
@Component
class QueryEngine(
    private val cache: MetadataGraphCache,
    private val provider: SchemaProvider,
    private val jdbc: NamedParameterJdbcTemplate,
    private val injector: PrivilegeInjector,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 受权限过滤的公开查询入口。 */
    fun run(
        eql: String,
        ctx: QueryContext,
    ): QueryResult = execute(eql, ctx, applyPrivilege = true)

    /**
     * kernel 内免权限过滤通道——仅 [NoFilterWhitelist] 登记的调用点可用，否则 [EqlErrors.injectForbidden]（403）。
     *
     * 白名单为空（M1-05）时任何调用即拒，确保「免过滤」是显式、可评审、可审计的例外而非常态。
     */
    fun runNoFilter(
        eql: String,
        ctx: QueryContext,
        callSite: NoFilterCallSite,
    ): QueryResult {
        if (!NoFilterWhitelist.isAllowed(callSite)) throw EqlErrors.injectForbidden(callSite.name)
        return execute(eql, ctx, applyPrivilege = false)
    }

    private fun execute(
        eql: String,
        ctx: QueryContext,
        applyPrivilege: Boolean,
    ): QueryResult {
        val startNanos = System.nanoTime()
        val lookup = SnapshotMetadataLookup(cache.snapshot())
        val ast = EqlParser.parse(eql)
        val compiled = EqlCompiler.compile(ast, lookup)
        val filtered = if (applyPrivilege) injector.inject(compiled, ctx) else compiled
        val effectiveLimit = filtered.limit ?: DEFAULT_LIMIT
        // 以 limit+1 探量 → 精确判 truncated，再裁回 effectiveLimit
        val fetch = filtered.copy(limit = effectiveLimit + 1)
        val frag = SqlRenderer(provider, ctx.now).render(fetch)
        val allBusiness = filtered.select.size == 1 && filtered.select[0] is SelectPlan.AllBusiness
        val rows = jdbc.query(frag.sql, frag.params, RowExtractor(positional = !allBusiness))
        val truncated = rows.size > effectiveLimit
        val out = if (truncated) rows.subList(0, effectiveLimit).toList() else rows
        val ms = (System.nanoTime() - startNanos) / 1_000_000
        if (ms > SLOW_MS) log.warn("EQL 慢查询 {}ms :: {}", ms, summarize(eql))
        return QueryResult(out, truncated, ms)
    }

    private fun summarize(
        eql: String,
    ): String {
        val oneLine = eql.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= 200) oneLine else oneLine.take(200) + "…"
    }

    private companion object {
        const val SLOW_MS = 200L
    }
}

/**
 * 行抽取：显式投影按位置键 `c0..cN`（跨方言稳定）；`SELECT *`（全业务列）按物理列名，内部列（ext/deleted_at/…）原样带出，
 * 由上层按需裁剪——列全集裁剪依赖写入通道语义，归 M1-06（见证据 §2）。
 */
private class RowExtractor(
    private val positional: Boolean,
) : ResultSetExtractor<List<Map<String, Any?>>> {
    override fun extractData(
        rs: ResultSet,
    ): List<Map<String, Any?>> {
        val res = ArrayList<Map<String, Any?>>()
        val md = rs.metaData
        val n = md.columnCount
        while (rs.next()) {
            val m = LinkedHashMap<String, Any?>()
            for (i in 1..n) {
                val key = if (positional) "c${i - 1}" else md.getColumnLabel(i)
                m[key] = rs.getObject(i)
            }
            res += m
        }
        return res
    }
}
