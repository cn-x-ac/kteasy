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
package cn.x.ac.kteasy.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty

/**
 * `kteasy.*` 配置命名空间的唯一类型安全入口（【规格】§1.1）。
 *
 * 卡内红线：配置禁散读 `@Value`——所有 kteasy.* 键都收在这棵树里，
 * 由 `@ConfigurationPropertiesScan` 注册。本卡只用得到 dataDir 与 db.dialect；
 * storage.* 与 automation.js.enabled 是刻意预留的配置位（装配留给 M3b / M5-03）。
 */
@ConfigurationProperties(prefix = "kteasy")
class KteasyProperties {
    /** 数据目录根；附件等落 `{dataDir}/_files`。 */
    var dataDir: String = "./kteasy-data"

    @NestedConfigurationProperty
    var db: Db = Db()

    @NestedConfigurationProperty
    var md: Md = Md()

    @NestedConfigurationProperty
    var storage: Storage = Storage()

    @NestedConfigurationProperty
    var automation: Automation = Automation()

    /** 数据库方言配置：取值 pg | mysql，由 Profile 显式映射（见 application-*.yml）。 */
    class Db {
        var dialect: String = ""
    }

    /**
     * 元数据治理面配置（M1-01）。
     *
     * `bootToken`：md 区治理 API 的临时鉴权令牌（M2 换真权限，代码留 TODO(M2)）。
     * 留空 = 开发直通模式（启动打 WARN，仅限本地/受信环境；生产必须显式配置）。
     *
     * `cache.redisChannel`：跨节点失效总线通道名（【规格】§8-⑦：Redis pub/sub 预留）。
     * 留空 = 单节点进程内失效（默关），多节点部署在后续卡接线。
     */
    class Md {
        var bootToken: String = ""

        @NestedConfigurationProperty
        var cache: Cache = Cache()

        class Cache {
            var redisChannel: String = ""
        }
    }

    /**
     * 对象存储（S3/MinIO）连接位。本卡只声明不接线：私有桶 + HTTPS 的落地在 M5-03，
     * 届时 blob 一律存对象存储、DB 只存引用（【规格】§3）。
     */
    class Storage {
        var endpoint: String = ""
        var bucket: String = ""
        var region: String = ""
        var accessKey: String = ""
        var secretKey: String = ""
        var pathStyleAccess: Boolean = true
        var https: Boolean = true
    }

    /** 自动化相关开关；本卡只留 js.enabled 配置位（默认关，轻量部署友好）。 */
    class Automation {
        @NestedConfigurationProperty
        var js: Js = Js()

        class Js {
            var enabled: Boolean = false
        }
    }
}
