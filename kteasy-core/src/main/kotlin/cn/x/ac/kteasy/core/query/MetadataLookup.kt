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
package cn.x.ac.kteasy.core.query

import cn.x.ac.kteasy.core.meta.MdField
import cn.x.ac.kteasy.core.meta.MdObject

/**
 * 查询编译器所需的**最小元数据读面**（块2 的心脏接口，让编译器留在纯 core、可离线 L1 测）。
 *
 * server 侧由 `MetadataGraphCache` 的快照投影实现（一次查询取一次快照、编译期只读、零打库）；
 * L1 测用内存 fake。编译器只经此口拿对象/字段，绝不 import 仓储或 JDBC（红线④）。
 */
interface MetadataLookup {
    /** 按 api_name 取对象（含禁用；启用性由调用方按需过滤）。 */
    fun objectByApi(
        api: String,
    ): MdObject?

    /** 按对象 id 取对象（解析引用目标）。 */
    fun objectById(
        id: String,
    ): MdObject?

    /** 取对象全部字段（含停用；编译器按 [MdField.enabled] 自行判「未知/禁用」）。 */
    fun fieldsByObject(
        objectId: String,
    ): List<MdField>
}
