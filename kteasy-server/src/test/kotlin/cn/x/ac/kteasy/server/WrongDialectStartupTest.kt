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
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

/**
 * 验收②的接线级证明：pg Profile 却喂 mysql 连接串时，上下文在装配阶段即失败，
 * 且异常链里同时含方言名与「禁静默降级」——不只是守卫单测，而是真的拦在起服路径上。
 * 第二个用例覆盖「未选 Profile 即失败，不默认连某个库」。
 */
class WrongDialectStartupTest {
    @Test
    fun `pg Profile 连 mysql 串启动失败且报错含方言名`() {
        val failure =
            startAndExpectStartupFailure(
                "--spring.profiles.active=pg",
                "--spring.datasource.url=jdbc:mysql://localhost:3306/kteasy",
            )
        val chain =
            generateSequence<Throwable>(failure) { it.cause }
                .joinToString(" | ") { it.message ?: it.javaClass.name }
        assertThat(chain)
            .contains("pg")
            .contains("mysql")
            .contains("禁静默降级")
    }

    @Test
    fun `未选 Profile 时启动失败不默认连库`() {
        startAndExpectStartupFailure("--spring.datasource.url=jdbc:postgresql://localhost:5432/kteasy")
    }

    /**
     * 以 WebApplicationType.NONE 起一次进程内启动，断言其失败并返回原始异常；
     * 万一意外启动成功（守卫漏网的真 bug），也负责关掉泄漏的上下文再让断言报错。
     */
    private fun startAndExpectStartupFailure(vararg args: String): Throwable {
        var context: ConfigurableApplicationContext? = null
        return try {
            assertThrows<Exception> {
                context =
                    SpringApplicationBuilder(KteasyApplication::class.java)
                        .web(WebApplicationType.NONE)
                        .run(*args)
            }
        } finally {
            context?.close()
        }
    }
}
