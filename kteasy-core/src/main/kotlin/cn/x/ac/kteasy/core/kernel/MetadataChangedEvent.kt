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
package cn.x.ac.kteasy.core.kernel

/**
 * 元数据变更事件（【规格】§8-⑦ 缓存失效唯一实现处的载体）。
 *
 * md 区写路径在**事务内**发布本事件；由装配层（kteasy-server）以 Spring
 * `@TransactionalEventListener(AFTER_COMMIT)` 监听——事务回滚则事件静默丢弃，
 * 缓存版本号不动（防脏读的根基）。内核侧只定义载荷，不接触 Spring。
 *
 * @property action 变更类别
 * @property objectApi 受影响对象的 api_name（字典/选项集级变更可为 null）
 * @property occurredAt 发布时刻（毫秒）
 */
data class MetadataChangedEvent(
    val action: MetadataAction,
    val objectApi: String?,
    val occurredAt: Long = System.currentTimeMillis(),
)

/** md 区写路径的变更类别（M1-01 只覆盖对象/字段/复制；后续卡按需追加枚举值）。 */
enum class MetadataAction {
    /** 对象创建（含连带字段的内联创建）。 */
    OBJECT_CREATED,

    /** 对象更新（label/status/disabled/名称字段/快查字段）。 */
    OBJECT_UPDATED,

    /** 对象复制（连带字段）。 */
    OBJECT_COPIED,

    /** 字段创建/更新/逻辑删除。 */
    FIELD_CHANGED,
}
