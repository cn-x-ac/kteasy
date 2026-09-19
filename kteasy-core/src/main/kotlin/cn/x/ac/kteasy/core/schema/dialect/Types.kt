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
package cn.x.ac.kteasy.core.schema.dialect

/**
 * 参数化 SQL 片段对象（步骤卡 M1-02 设计要点 1）。
 *
 * 方言实现只负责「产出带具名占位符的 SQL 文本 + 绑定参数」，绝不触碰 JDBC 驱动与执行——
 * 执行归 query/schema 两模块（【规格】§8-④）。`params` 的键与 `sql` 里的 `:key` 一一对应，
 * 由 Spring `NamedParameterJdbcTemplate` 消费；这样 [Fragment] 本身可在纯 core 里构造、可 L1 单测。
 *
 * @property sql 带 `:name` 具名占位符的 SQL 片段
 * @property params 占位符绑定值（可含 null；键不含前导冒号）
 */
data class Fragment(
    val sql: String,
    val params: Map<String, Any?> = emptyMap(),
) {
    /** 拼接另一片段（共享参数表）；用于把取值片段并入更大的 WHERE/SELECT。 */
    infix fun and(
        other: Fragment,
    ): Fragment {
        val clash = params.keys intersect other.params.keys
        require(clash.isEmpty()) { "片段参数名冲突：$clash" }
        return Fragment("$sql AND ${other.sql}", params + other.params)
    }
}

/**
 * 一条 DDL 语句及其事务约束。
 *
 * [runOutsideTransaction]＝true 时该语句**禁止进事务**（PG `CREATE INDEX CONCURRENTLY` 的硬约束），
 * 由物化引擎（M1-03）的执行器在事务边界外单独执行并做作业断点。接口层面强制该标志，
 * 让「误把 CONCURRENTLY 塞进事务」变成编译/契约层拦得住的问题，而非运行期才炸。
 *
 * @property sql 单条 DDL 语句（无占位符；表/列名已由命名空间层限定）
 * @property runOutsideTransaction 是否必须脱离事务执行
 */
data class DdlStatement(
    val sql: String,
    val runOutsideTransaction: Boolean = false,
)

/**
 * 指向 `ext`（或任一 JSON 列）内部的一条路径，仅由对象键段构成。
 *
 * 刻意做成「方言无关」的纯路径数据：PG 侧格式化为 `#>` 文本数组路径、MySQL 侧格式化为
 * `$.a.b` JSON 路径，都由各方言实现负责（避免把某库的路径语法泄漏进上层，触红线⑤）。
 * 段值来源于元数据 `api_name`（已受存储命名校验约束），实现据此构造路径字面量。
 */
data class JsonPath(
    val segments: List<String>,
) {
    init {
        require(segments.isNotEmpty()) { "JSON 路径至少一段" }
        require(segments.all { it.isNotBlank() }) { "JSON 路径段不得为空白" }
    }

    companion object {
        fun of(vararg segments: String): JsonPath = JsonPath(segments.toList())
    }
}

/**
 * 方言无关的取值/落库类型枚举：把「从 JSON 抽出后按什么类型解释」收敛到这一小组，
 * 屏蔽 `::bigint` 与 `CAST(... AS SIGNED)` 的写法差异。⟨可逆⟩——M1-04 的 26 型注册表
 * 会把每个 `FieldType` 映射到本枚举之一（M1-02 先落地可测的核心几档）。
 */
enum class ValueCast {
    BOOL,
    LONG,
    DOUBLE,
    TEXT,
    DATE,
    TIMESTAMP,
}

/**
 * 命名锁的键（PG advisory 用 bigint、MySQL `GET_LOCK` 用字符串）。
 *
 * 一个逻辑键同时算出两种形态：[id] 是把 [name] 稳定哈希到 64 位有符号整数（供 PG advisory），
 * [name] 原样作 MySQL 锁名。哈希只用于映射、不承担密码学安全；跨方言「同一逻辑键 → 同一把锁」
 * 由同一 [name] 保证，测试据此验证两库互斥语义等价。
 */
data class LockKey(
    val name: String,
) {
    init {
        require(name.isNotBlank()) { "锁键不能为空" }
        // MySQL GET_LOCK 锁名上限 64 字符（含终止符前）；提前拦住，避免运行期静默截断致两库锁名不一致。
        require(name.length <= 64) { "锁键长度须 ≤ 64（MySQL GET_LOCK 锁名上限）：$name" }
    }

    /** PG advisory 锁使用的 64 位整数键（由锁名稳定派生）。 */
    val id: Long = deriveId(name)

    companion object {
        /** 与 String.hashCode 无关的 64 位 FNV-1a 派生，跨 JVM 稳定（advisory 键须可复现）。 */
        fun deriveId(name: String): Long {
            var hash = 0xcbf29ce484222325UL // FNV-1a 64 位标准偏移基准
            val prime = 0x100000001b3UL // FNV-1a 64 位标准素数
            for (ch in name) {
                hash = hash xor ch.code.toULong()
                hash *= prime
            }
            return hash.toLong()
        }
    }
}
