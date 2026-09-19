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
 * 方言能力位（台账枚举，封闭）。上层据「能力标志」而非「isMySQL」走分支——
 * 把【清单】S8 表逐行搬成可被 CI/健康端点核对的标志（红线⑤的正解：不假装两库等价）。
 *
 * 每个能力在两库上的档位见各 [SchemaProvider] 实现；档位语义由 [CapabilityLevel] 表达。
 */
enum class Capability {
    /** JSON 整体 GIN 索引（PG jsonb_path_ops）；MySQL 无对等物 → [CapabilityLevel.ABSENT]。 */
    JSON_GIN_INDEX,

    /** JSON 数组多值索引（MySQL 8.0.17+ CAST AS ARRAY）；PG 用 GIN 承载包含 → DEGRADED。 */
    JSON_MULTI_VALUED_INDEX,

    /** 在线加/删索引不锁写（PG CONCURRENTLY / MySQL INPLACE,LOCK=NONE）。 */
    ONLINE_INDEX_NO_LOCK,

    /** 事务性 DDL（失败可回滚）。PG 有；MySQL 隐式提交 → ABSENT，物化引擎据此强制作业断点。 */
    TRANSACTIONAL_DDL,

    /** VIRTUAL 生成列上建索引（热字段物理化档二）。 */
    VIRTUAL_COLUMN_INDEX,

    /** INSTANT 加可空列（仅表末尾、不可混操作）。 */
    INSTANT_ADD_COLUMN,

    /** `FOR UPDATE SKIP LOCKED`。 */
    SKIP_LOCKED,
}

/**
 * 能力档位。三件套之一，健康端点与 `GET /api/md/capabilities` 据此对「此后端下哪些功能弱化」
 * 如实标注（【清单】S8：对用户诚实，不假装等价）。
 */
enum class CapabilityLevel {
    /** 原生支持，无功能损失。 */
    SUPPORTS,

    /** 有对应处理但有取舍/降级形态（[CapabilityReport.note] 说明降级方式）。 */
    DEGRADED,

    /** 该库根本不具备，上层须改走兜底路径。 */
    ABSENT,
}

/**
 * 一条能力的完整台账记录：档位 + 面向用户/评审的说明。golden-file 测试比对整表（含档位与说明），
 * 任何人「顺手加/翻能力标志」都会触发快照漂移 → 失败并要求评审（防漂移即防「悄悄假装等价」）。
 */
data class CapabilityReport(
    val capability: Capability,
    val level: CapabilityLevel,
    val note: String,
)
