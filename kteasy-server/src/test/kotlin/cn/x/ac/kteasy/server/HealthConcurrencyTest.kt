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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.Executors

/**
 * 验收④：50 并发打 `/api/health` 全部 200，且虚拟线程开启下并发压测不带来「每请求一条平台线程」的爆炸。
 *
 * 客户端用 `newVirtualThreadPerTaskExecutor` 发压；服务端 `spring.threads.virtual.enabled=true`
 * 让 Tomcat 每请求跑在虚拟线程上。主断言是确定性「全 200」；线程维度做近似回归：本 JVM 内平台线程数
 * 的增量远小于并发量级（≈核数量级），据此挡住「线程爆炸」回归，而非精确对账。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.flyway.enabled=false"],
)
@ActiveProfiles("pg")
class HealthConcurrencyTest {
    @Autowired
    lateinit var environment: Environment

    @Test
    fun `50 并发健康请求全部成功且平台线程不随并发膨胀`() {
        val concurrency = 50
        val before = livePlatformThreads()
        val statuses =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                (1..concurrency)
                    .map { executor.submit<Int> { HealthClient.health(environment).status } }
                    .map { it.get() }
            }
        val after = livePlatformThreads()

        assertThat(statuses).hasSize(concurrency).allMatch { it == 200 }
        // 每请求一条平台线程的话，增量≈concurrency；虚拟线程模型下应远小于此。
        assertThat(after - before).isLessThan(concurrency)
    }

    private fun livePlatformThreads(): Int = Thread.getAllStackTraces().keys.count { it.isAlive && !it.isVirtual }
}
