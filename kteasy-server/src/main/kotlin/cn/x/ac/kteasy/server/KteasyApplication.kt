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
package cn.x.ac.kteasy.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * Kteasy 唯一常驻进程入口（【规格】§2）。
 *
 * 虚拟线程不在此处硬编码，而由 `spring.threads.virtual.enabled=true`（application.yml）开启；
 * `@ConfigurationPropertiesScan` 让 kteasy.* 配置树（KteasyProperties）自动注册，无需 @EnableConfigurationProperties。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class KteasyApplication {
    companion object {
        /** 供 `java -jar`（JarLauncher 找 KteasyApplication.main）与 IDE 直接运行的入口。 */
        @JvmStatic
        fun main(args: Array<String>) {
            runApplication<KteasyApplication>(*args)
        }
    }
}
