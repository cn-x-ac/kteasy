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

import cn.x.ac.kteasy.core.kernel.Dialect
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test

/**
 * 方言↔连接串守卫的 L1 单测：这是验收点②「连错库启动失败、报错含方言名、禁静默降级」的逻辑内核。
 */
class DataSourceDialectGuardTest {
    @Test
    fun `pg 方言配 postgresql 连接串通过`() {
        assertThat(DataSourceDialectGuard.requireConsistent("pg", "jdbc:postgresql://localhost:5432/kteasy"))
            .isEqualTo(Dialect.POSTGRESQL)
    }

    @Test
    fun `mysql 方言配 mysql 连接串通过`() {
        assertThat(DataSourceDialectGuard.requireConsistent("mysql", "jdbc:mysql://localhost:3306/kteasy?serverTimezone=UTC"))
            .isEqualTo(Dialect.MYSQL)
    }

    @Test
    fun `pg 方言连到 mysql 串即拒绝且报错含双方言名`() {
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy {
                DataSourceDialectGuard.requireConsistent("pg", "jdbc:mysql://localhost:3306/kteasy")
            }.withMessageContaining("pg")
            .withMessageContaining("mysql")
            .withMessageContaining("禁静默降级")
    }

    @Test
    fun `未知方言值直接抛错绝不套默认库`() {
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { DataSourceDialectGuard.requireConsistent("oracle", "jdbc:oracle:thin:@//localhost:1521/kteasy") }
            .withMessageContaining("oracle")
    }

    @Test
    fun `空方言值抛错`() {
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { DataSourceDialectGuard.requireConsistent("", "jdbc:postgresql://localhost:5432/kteasy") }
    }

    @Test
    fun `方言正确但连接串缺失抛错`() {
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { DataSourceDialectGuard.requireConsistent("pg", null) }
            .withMessageContaining("spring.datasource.url")
    }
}
