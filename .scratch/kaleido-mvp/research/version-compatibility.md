# Kaleido MVP 版本兼容性研究

研究日期：2026-08-31

## 问题与边界

本报告回答：Kaleido MVP 可以基于哪些 AGP 9、Gradle、JDK、Kotlin 与 Android SDK Build Tools 组合建立可辩护的兼容性承诺，以及怎样用 Gradle TestKit 证明该承诺。

这里把三类东西严格分开：

1. **厂商要求**：Android/Gradle 官方文档明确给出的最低版本、默认版本和运行条件。
2. **候选支持范围**：Kaleido 可以考虑承诺、但必须由自身测试证明的范围。
3. **测试覆盖**：为了控制成本而选择的代表性组合；测试过一个组合不等于官方保证整个笛卡尔积。

本报告不决定最终支持范围。尤其是最低 AGP 版本仍取决于“Establish supported AGP 9 artifact seams”对必需 Artifact API 的调查结果。

## 官方要求矩阵

截至研究日，AGP 9 的稳定小版本线及其官方兼容表如下。表中的 Gradle 是该 AGP 线的**最低版本，同时也是默认版本**；不是说该 AGP 与所有更高 Gradle 版本的任意组合都经过 Android 团队保证。

| AGP 稳定线（选最新补丁做测试） | Gradle 最低/默认 | JDK 最低/默认 | SDK Build Tools 最低/默认 | AGP 支持的最高 Android API |
| --- | --- | --- | --- | --- |
| 9.0.1 | 9.1.0 | 17 | 36.0.0 | 36.1 |
| 9.1.1 | 9.3.1 | 17 | 36.0.0 | 37.0 |
| 9.2.1 | 9.4.1 | 17 | 36.0.0 | 37.0 |
| 9.3.2 | 9.5.0 | 17 | 36.0.0 | 37 |

来源分别是 [AGP 9.0 release notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes)、[AGP 9.1 release notes](https://developer.android.com/build/releases/agp-9-1-0-release-notes)、[AGP 9.2 release notes](https://developer.android.com/build/releases/agp-9-2-0-release-notes) 和 [AGP 9.3 release notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes)。

因此，有证据直接支持的配对是上表四个“AGP 线 + 该线默认 Gradle”组合。不能仅凭“Gradle 最低版本”就把 `4 个 AGP × 4 个 Gradle` 的全部组合写成 Kaleido 支持范围；额外配对只能通过 Kaleido 自身测试升级为支持项。

## 公共 API 对兼容范围的约束

AGP 9.0 将新版 DSL 和 Variant API 定为稳定 API，并要求插件使用 `gradle-api` artifact；官方明确警告私有内部 AGP 类型随时可能发生破坏性变化。[AGP DSL/API migration timeline](https://developer.android.com/build/releases/gradle-plugin-roadmap)

Gradle 对其公共 API 提供向后兼容政策，但不覆盖内部 API或仍带 `@Incubating` 标记的 API。[Gradle feature lifecycle](https://docs.gradle.org/current/userguide/feature_lifecycle.html)、[Public Gradle APIs](https://docs.gradle.org/current/userguide/public_apis.html)

由此可得一个有条件的候选策略：

- 用 `compileOnly("com.android.tools.build:gradle-api:<minimum-supported-agp>")` 编译 Kaleido，让 Consumer Project 决定实际 AGP 运行版本；Gradle 官方也明确建议，对其他插件的 API 依赖使用 `compileOnly`，避免把某个 AGP 版本泄漏到 Consumer Project 的构建类路径。[Gradle binary plugin guidance](https://docs.gradle.org/current/userguide/implementing_gradle_plugins_binary.html#avoid_plugin_dependency_conflicts)
- 只使用该最低版本中已经稳定的公开 DSL、Variant 和 Artifact API，不使用 `com.android.build.gradle.internal.*`、旧 `applicationVariants` API 或 MVP 所依赖的 `@Incubating` API。
- 如果某项 Hardening Pipeline 必需能力直到 AGP 9.1、9.2 或 9.3 才有稳定公开 seam，则最低支持版本必须同步提高，或把该能力从 MVP 移出；不能用内部 API“补洞”后仍宣称稳定兼容。

所以，**AGP 9.0.1–9.3.2 是一个可验证的候选范围，不是仅靠文档即可成立的结论**。它能否成为最终范围，要看必需 API 是否全部存在于 9.0.1，并且下述四条 TestKit 构建均通过。

## Kotlin 必须拆成两个版本域

### Consumer Project 的 Android Kotlin

AGP 9.0 起默认启用 built-in Kotlin；应用模块不再应用 `org.jetbrains.kotlin.android`。如果暂时退出 built-in Kotlin，还必须同时退出新 DSL；这两项退出机制是迁移手段，并计划在 AGP 10 移除。[Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)

Google Maven 上四个所选 AGP 补丁的官方 POM 都声明 `kotlin-gradle-plugin` 与 `kotlin-stdlib` 运行时版本为 `2.2.10`：[9.0.1 POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.0.1/gradle-9.0.1.pom)、[9.1.1 POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.1.1/gradle-9.1.1.pom)、[9.2.1 POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.2.1/gradle-9.2.1.pom)、[9.3.2 POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.3.2/gradle-9.3.2.pom)。

这意味着 MVP 的默认 Kotlin Consumer fixture 应验证 **AGP 自带 Kotlin 2.2.10**，而不是再套一层 `org.jetbrains.kotlin.android` 版本矩阵。JetBrains 的 KGP/AGP 兼容表适用于独立应用 KGP 的项目，不能拿它否定 AGP 自己发布并拥有的 built-in Kotlin 依赖。[Kotlin Gradle compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html#apply-the-plugin)

候选 MVP 边界应为：

- 支持 Java-only Consumer module，以及启用 AGP 9 默认 built-in Kotlin 的 Java/Kotlin 混合 module。
- 不把 `android.builtInKotlin=false` + `android.newDsl=false` + `org.jetbrains.kotlin.android` 的旧迁移模式纳入首版承诺；若产品以后要支持它，应建立独立 KGP 版本矩阵。
- Compose 编译器插件是独立维度。当前 Sample App 使用 `org.jetbrains.kotlin.plugin.compose:2.2.10`，可以作为生产形态 fixture，但不应把“任意 Compose/Kotlin 插件版本”写进 Kaleido 的通用兼容承诺。

### Kaleido 插件自身的 Kotlin

Kaleido 实现代码使用哪个 Kotlin/JVM 编译器，是插件的构建工具链选择，不等于 Consumer Project 的 Kotlin 版本。若 Kaleido 使用 Kotlin 实现，候选基线是 Kotlin 2.2.10、Java toolchain 17、JVM target 17：它与当前工程及上述 AGP runtime Kotlin 对齐，也不会把插件 bytecode 最低运行时提高到 JDK 21。

无论最终选 Java 还是 Kotlin 实现，发布物都应只依赖 Gradle/AGP 的公共 API；Kotlin 编译器升级属于 Kaleido 自身发布验证事项，而不是自动扩大 Consumer Kotlin 支持范围。

## JDK 范围

四条 AGP 9 稳定线都明确要求并默认使用 JDK 17。Gradle 的 Java 兼容表同时确认：JDK 17 可运行 Gradle 7.3 及以后版本，JDK 21 可运行 Gradle 8.5 及以后版本，所以表中四个 Gradle 版本都能在 JDK 17 和 21 上运行。[Gradle compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html)

不过，“Gradle 可在 JDK 21 运行”并不自动证明“Kaleido + AGP + Android 工具链”整体兼容。因此建议分级：

- **规范基线与最低要求：JDK 17**。所有发布阻断矩阵必须跑 JDK 17。
- **候选额外支持：JDK 21**。只有对应矩阵也成为发布阻断测试后，才能正式承诺。
- JDK 25/26 即使被部分 Gradle 9 版本支持，也先作为非阻断前瞻测试，不进入 MVP 承诺。AGP 9.2 的 release notes 甚至记录过 JDK 26 的 `JdkImageTransform` 修复项，说明只看 Gradle 的 JVM 表不足以做 Android 组合保证。

Android 官方还区分了“运行 Gradle 的 JDK”和“编译源码的 Java toolchain”，并建议显式配置 toolchain 以获得一致结果。[Java versions in Android builds](https://developer.android.com/build/jdks) 因此 Kaleido 自身插件编译与测试 fixture 都应固定 toolchain/target 17；测试 JDK 21 时只切换运行 Gradle/TestKit 的 JDK，不应顺便改变产物 bytecode target。

## Android SDK 与 Build Tools

四条稳定 AGP 线的最低和默认 Build Tools 都是 36.0.0，因此它是唯一有官方共同交集证据的 MVP 基线。建议 CI 显式安装 `build-tools;36.0.0`，fixture 不主动覆写 `buildToolsVersion`，让 AGP 使用其文档默认值；测试日志与 Artifact Report 记录实际解析出的版本。

共同的 `compileSdk` 测试基线应使用 API 36，原因是 AGP 9.0 的最高支持 API 为 36.1，而 AGP 9.1–9.3 可到 37。API 37 应作为只在 9.1+ 行运行的边界场景；不能让它进入 9.0 兼容 fixture。

## TestKit 可执行性与限制

Gradle TestKit 会在隔离的临时工程中通过 Tooling API 启动真实 Gradle build；`GradleRunner.withGradleVersion(...)` 可以为每个用例选择 Gradle distribution，因此它适合验证上述 AGP/Gradle配对。[Testing build logic with TestKit](https://docs.gradle.org/current/userguide/test_kit.html#sec:gradle_runner)

但有两个关键限制：

1. TestKit 不提供为被测 build 精细选择 JDK 的 API，所以 JDK 17/21 必须拆成不同 CI job，在启动测试进程之前设置运行 JDK；不能在同一个参数化测试方法里可靠地切换 JDK。[TestKit controlling the build environment](https://docs.gradle.org/current/userguide/test_kit.html#sub:test-kit-controlling-build-environment)
2. Gradle 官方指出，插件把其他插件 API 声明为 `compileOnly` 时，标准 `withPluginClasspath()` 测法会失败。因此 Kaleido 的 harness 必须先用一个最小 spike 证明装载方式：让 fixture 自己从 Google Maven 应用指定 AGP，并通过测试仓库/显式测试类路径提供 Kaleido，而不是把 Kaleido 编译时的 AGP 固化进插件发布物。[Gradle binary plugin guidance](https://docs.gradle.org/current/userguide/implementing_gradle_plugins_binary.html#avoid_plugin_dependency_conflicts)

## 建议的 MVP 测试矩阵

### A. 发布阻断：官方配对，JDK 17

同一个最小 Consumer fixture 在下列四行执行 `bundleRelease`，并验证插件应用、任务图接入、AAB 生成、mapping/Artifact Report 生成、无 Consumer source rewrite，以及第二次构建/配置缓存行为：

| 行 | AGP | Gradle | 运行 JDK | compileSdk | Build Tools | Kotlin 模式 |
| --- | --- | --- | --- | --- | --- | --- |
| A1 | 9.0.1 | 9.1.0 | 17 | 36 | 36.0.0 | built-in Kotlin；另有 Java-only variant |
| A2 | 9.1.1 | 9.3.1 | 17 | 36 | 36.0.0 | built-in Kotlin；另有 Java-only variant |
| A3 | 9.2.1 | 9.4.1 | 17 | 36 | 36.0.0 | built-in Kotlin；当前开发锚点 |
| A4 | 9.3.2 | 9.5.0 | 17 | 36 | 36.0.0 | built-in Kotlin；最新稳定边界 |

若全部能力 fixture 每行成本过高，最小 seam/装载/AAB 测试必须四行都跑；Full Profile、Compose/XML 混合、反射/JNI keep 等生产形态场景至少在 A3 与 A4 跑。不能因为 A3 是当前工程版本就省略 A1 最低边界和 A4 最新边界。

### B. JDK 21 候选支持

先在 A1 与 A4 的完全相同 fixture 上只更换运行 JDK 为 21，捕获范围两端的问题。若最终规格要公开承诺“支持 JDK 21”，则 A2/A3 也应加入发布阻断矩阵；否则只能表述为“已在端点验证”，不能表述为全矩阵保证。

### C. Android/Kotlin 边界场景

- AGP 9.1.1 与 9.3.2：`compileSdk = 37`，验证较新平台边界。
- AGP 9.2.1 与 9.3.2：Kotlin 2.2.10 built-in Kotlin + Compose compiler plugin 2.2.10 的 Sample App 场景。
- AGP 9.0.1 与 9.3.2：Java-only module，显式关闭不需要的 Kotlin compilation，确保 Kaleido 不假设 Kotlin source/task 一定存在。
- 所有正式行都保持 `android.newDsl=true` 与 `android.builtInKotlin=true`；旧 DSL/旧 Kotlin opt-out 只可做负向诊断测试，不计入支持范围。

### D. 非阻断前瞻

- 当前最新稳定 Gradle 搭配最新稳定 AGP（若不是官方默认配对）。
- 下一 AGP preview 与其要求的 Gradle。
- JDK 25/26。

这些 job 的目的只是提前发现变化；失败不阻断 MVP，也不得据此把 preview 写入支持声明。

## 候选兼容性表述

在 Artifact seam 调查确认 9.0.1 已具备所有必需稳定 API、且 A1–A4 通过之后，Kaleido 才有依据写：

> Kaleido MVP supports stable Android Gradle Plugin 9.0 through 9.3 on each AGP line's documented Gradle baseline, with JDK 17 and Android SDK Build Tools 36.0.0. Android modules use AGP built-in Kotlin. Exact tested patch pairs are published in the compatibility table.

如果 9.0.1 缺少任一必要稳定 seam，则应把最低版本提高到第一个具备它的 AGP 线，并删除更早的测试/承诺；如果只验证当前工程组合，则应更诚实地写成：

> Kaleido MVP initially supports AGP 9.2.1, Gradle 9.4.1, JDK 17, and SDK Build Tools 36.0.0; other AGP 9 combinations are experimental until added to the release matrix.

这两个表述不能混用。前者是完成四线验证后的范围承诺，后者是单锚点 MVP 的保守承诺。

## 待决事项

- “Establish supported AGP 9 artifact seams”是否证明全部必需接口在 AGP 9.0.1 已稳定存在。
- MVP 是否愿意承担四条 AGP 线的每次发布阻断成本，还是只承诺当前锚点 9.2.1。
- JDK 21 是正式支持项还是端点兼容信号。
- TestKit 对 `compileOnly` AGP API 的插件装载 harness 采用测试 Maven 仓库还是显式类路径；应先做最小 spike 再固定。
- Android API 37 是正式支持边界还是只做前瞻覆盖。
