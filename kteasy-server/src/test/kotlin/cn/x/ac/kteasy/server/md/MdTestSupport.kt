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

import cn.x.ac.kteasy.core.kernel.Ulid
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DatabaseMetaData
import javax.sql.DataSource

/** md 区集成测试共用件：JSON HTTP 客户端、列结构读取、api_name 隔离后缀与清理。 */
object MdTestSupport {
    /** 本轮测试的 api_name 隔离后缀（Ulid 小写尾段，保证跨次重跑不撞唯一约束）。 */
    fun suffix(): String = Ulid.next().lowercase().takeLast(8)

    /** md 表物理名（PG schema md / MySQL 前缀无——本六表恰好同名，仅 PG 带 schema 限定）。 */
    fun table(
        dialect: String,
        logical: String,
    ): String = if (dialect == "pg") "md.$logical" else logical

    fun columns(
        dataSource: DataSource,
        dialect: String,
        logicalTable: String,
    ): Map<String, Int> {
        val out = linkedMapOf<String, Int>()
        dataSource.connection.use { conn ->
            val meta: DatabaseMetaData = conn.metaData
            val schema = if (dialect == "pg") "md" else null
            meta.getColumns(conn.catalog, schema, logicalTable, "%").use { rs ->
                while (rs.next()) out[rs.getString("COLUMN_NAME")] = rs.getInt("DATA_TYPE")
            }
        }
        return out
    }
}

/** 真 HTTP JSON 客户端（JDK HttpClient，沿用 M0-02 §E18 口径：不依赖被 Boot 4 搬动的测试工具类）。 */
class MdTestClient(
    port: Int,
) {
    private val base = "http://127.0.0.1:$port"

    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .executor(
                java.util.concurrent.Executors
                    .newVirtualThreadPerTaskExecutor(),
            ).build()

    data class Response(
        val status: Int,
        val body: String,
        val headers: Map<String, List<String>>,
    ) {
        fun header(name: String): String? =
            headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()
    }

    fun get(path: String): Response = send("GET", path, null)

    fun post(
        path: String,
        json: String,
    ): Response = send("POST", path, json)

    fun patch(
        path: String,
        json: String,
    ): Response = send("PATCH", path, json)

    fun delete(path: String): Response = send("DELETE", path, null)

    private fun send(
        method: String,
        path: String,
        json: String?,
    ): Response {
        val builder =
            HttpRequest
                .newBuilder(URI.create("$base$path"))
                .header("Content-Type", "application/json")
        val request =
            if (json != null) {
                builder.method(method, HttpRequest.BodyPublishers.ofString(json)).build()
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
            }
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), response.body().orEmpty(), response.headers().map())
    }
}
