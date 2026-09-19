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
package cn.x.ac.kteasy.server.arch

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * 架构门禁（步骤卡 M1-02 设计要点 4 / 红线④⑤）：把「方言只经 SchemaProvider」从约定变 CI 硬约束。
 *
 * 结构不变式（非字符串字面量——字面量隔离由 [DialectLiteralIsolationTest] 覆盖，ArchUnit 读不到常量池里的 SQL 串）：
 *  1. `core` 保持纯库：不得依赖 Spring / JDBC（方言产物只是字符串，执行归 query/schema）。
 *  2. 具体方言实现类只能被装配工厂 `server.config..` 触达；业务层拿不到 `Postgres/MySqlSchemaProvider`，
 *     也就写不出 `if (isMySQL)`——只能注入 [cn.x.ac.kteasy.core.schema.dialect.SchemaProvider] 接口。
 */
class DialectIsolationArchTest {
    private val productionClasses: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("cn.x.ac.kteasy")

    @Test
    fun `core 保持纯库 不依赖 Spring 与 JDBC`() {
        noClasses()
            .that()
            .resideInAPackage("cn.x.ac.kteasy.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "java.sql..",
                "javax.sql..",
            ).because("core 是纯引擎库；方言差异只产 SQL 字符串，驱动/JDBC 出口只在装配与 query/schema 模块")
            .check(productionClasses)
    }

    @Test
    fun `业务层不得直连具体方言实现 只经 SchemaProvider 接口`() {
        noClasses()
            .that()
            .resideOutsideOfPackages(
                "cn.x.ac.kteasy.core.schema.dialect..",
                "cn.x.ac.kteasy.server.config..", // 唯一允许选实现的装配工厂所在区
            ).should()
            .dependOnClassesThat(concreteDialectProviders)
            .because(
                "方言选择集中在 SchemaProviders 工厂；业务层依赖接口即无从写出 isMySQL（红线⑤）。" +
                    "注意：业务可正常引用 dialect 包的 SchemaProvider 接口 / LogicalArea / Fragment，禁的是两个具体实现类",
            ).check(productionClasses)
    }

    private val concreteDialectProviders: DescribedPredicate<JavaClass> =
        object : DescribedPredicate<JavaClass>("是具体方言实现类（Postgres/MySql SchemaProvider）") {
            override fun test(input: JavaClass): Boolean =
                input.fullName == "cn.x.ac.kteasy.core.schema.dialect.PostgresSchemaProvider" ||
                    input.fullName == "cn.x.ac.kteasy.core.schema.dialect.MySqlSchemaProvider"
        }
}
