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
package cn.x.ac.kteasy.core.write

import cn.x.ac.kteasy.core.schema.dialect.LockKey

/**
 * 写入命名锁的键位（卡面 §5：防两请求交错读改写）。
 *
 * **为什么用对象 id 而不是 api_name 拼锁名**：`LockKey` 对锁名有 64 字符硬校验（源自 MySQL
 * 命名锁的锁名上限，越界即运行期异常），而 api_name 本身允许到 64 字符——卡面原式
 * `KTEASY:WRITE:<object>:<id>` 用 api_name 拼必然越界。改用 26 位 ULID 后长度恒
 * `9 + 26 + 1 + 26 = 62`，且 id 全局唯一、跨重命名稳定（改 api_name 不会分裂出第二把锁）。
 *
 * PG 侧 advisory 键由 `LockKey` 从同一锁名派生，故两库「同一逻辑键→同一把锁」仍然成立。
 */
object WriteLocks {
    /** 记录级写锁前缀（与元数据变更锁 `KTEASY:SCHEMA:` 同族但不同命名空间，互不误伤）。 */
    const val RECORD_PREFIX = "KTEASY:W:"

    /** 对象级写闸（批量/导入的跨记录串行化用，M1-07/M5 消费；本卡只锁形状）。 */
    const val OBJECT_PREFIX = "KTEASY:WO:"

    /** 记录级写锁：`KTEASY:W:<object_id>:<record_id>`。 */
    fun of(
        objectId: String,
        recordId: String,
    ): LockKey = lock(RECORD_PREFIX, objectId, recordId)

    /** 对象级写闸：`KTEASY:WO:<object_id>`。 */
    fun ofObject(
        objectId: String,
    ): LockKey = LockKey(OBJECT_PREFIX + objectId)

    private fun lock(
        prefix: String,
        objectId: String,
        recordId: String,
    ): LockKey = LockKey(prefix + objectId + ':' + recordId)
}
