# Kaleido MVP 发布验收与公开发布证据

研究日期：2026-08-31

## 结论摘要

Kaleido 的首个公开 MVP 应把两类条件分开：

1. **外部发布条件**：Gradle Plugin Portal 的账号、命名、元数据、版本和人工审核规则；Apache-2.0 对再分发和归属声明的要求；若另发 Maven Central，则再满足 Central 的签名、校验和、POM、源码/Javadoc 等要求。
2. **Kaleido 自己的产品承诺**：每个公开兼容矩阵行跑完整 Release fixture；验证最终 AAB、APK 生成和至少一个受控安装/运行路径；保存并实测 retrace 所需映射；独立通过配置缓存、up-to-date、Build Cache 和无缓存字节复现；给性能与体积设项目自己的预算；原子发布 Release Evidence Set。

Plugin Portal、Gradle、Android、Apache、SLSA 或 SBOM 规范中都没有一个可直接拿来作为 Kaleido 的统一“构建耗时不得超过 X、内存不得超过 Y、插件包/AAB 增量不得超过 Z”数值预算。Android 的应用下载大小上限也不是插件开销预算。因此性能和体积阈值必须由 Kaleido 用固定 fixture、固定测量环境和基线统计自行定义。

对 ticket 10 最关键的事实是：**TestKit 能精确选择 Gradle，但 AGP 版本由 fixture 的插件解析决定；bundletool 能证明结构有效、可生成特定设备 APK 并在所选设备安装，却不能证明所有设备、所有运行路径或 Play 政策接受；所以“矩阵行通过”“静态 AAB/APK 通过”“受控设备运行通过”是三个不同的验收层级。**

## 1. 兼容矩阵、fixture 与安装验收

### 1.1 TestKit 能怎样证明一个精确矩阵行

Gradle TestKit 的 `GradleRunner` 通过 Tooling API 在独立 Gradle 进程中运行临时工程；`withGradleVersion(...)` 可以为测试选择并缓存一个确切 Gradle distribution。[Gradle TestKit](https://docs.gradle.org/current/userguide/test_kit.html)、[`GradleRunner.withGradleVersion`](https://docs.gradle.org/current/kotlin-dsl/gradle/org.gradle.testkit.runner/-gradle-runner/with-gradle-version.html)

但 Gradle 还明确指出：如果待测插件对另一个插件 API 使用 `compileOnly`，标准 `withPluginClasspath()` 方式与这种依赖模型不兼容；官方建议把待测插件发布到本地 Maven 仓库，再让 fixture 按正常插件解析方式使用它。[Developing binary plugins: compile-only plugin dependencies](https://docs.gradle.org/current/userguide/implementing_gradle_plugins_binary.html#avoid_plugin_dependency_conflicts)

这直接决定 Kaleido harness 的最小机械结构：

- 先把当前待测 Kaleido 实现 publication 和 plugin marker publication 发布到**每次测试独立**的临时 Maven 仓库；
- fixture 的 `settings.gradle(.kts)` 在 `pluginManagement.repositories` 中显式加入该临时仓库、Google Maven、Plugin Portal/Maven Central（按实际依赖最小化）；
- fixture 显式解析目标 AGP patch；runner 通过 `withGradleVersion` 选择目标 Gradle；Gradle 运行 JDK、Build Tools、`compileSdk` 和 Kotlin 模式也必须由 harness 固定并记录；
- 为避免测试间的缓存污染，用每个矩阵行独立或可清空的 TestKit Gradle User Home。Gradle 文档说明 TestKit 默认会复用专用 Gradle User Home；这对普通测试有利，但不适合证明空缓存、跨目录可迁移或冷启动行为。[TestKit Gradle User Home](https://docs.gradle.org/current/userguide/test_kit.html#sub:test-kit-gradle-user-home)

因此，现有候选四行可以直接落成如下 release-blocking 骨架，而不是做 `4 × 4` 的未经厂商保证的笛卡尔积：

| 行 | AGP | Gradle | 运行 JDK | Build Tools | `compileSdk` | Kotlin 模式 |
|---|---:|---:|---:|---:|---:|---|
| A1 | 9.0.1 | 9.1.0 | 17 | 36.0.0 | 36 | AGP built-in Kotlin；另跑 Java-only |
| A2 | 9.1.1 | 9.3.1 | 17 | 36.0.0 | 36 | AGP built-in Kotlin；另跑 Java-only |
| A3 | 9.2.1 | 9.4.1 | 17 | 36.0.0 | 36 | AGP built-in Kotlin；Compose fixture |
| A4 | 9.3.2 | 9.5.0 | 17 | 36.0.0 | 36 | AGP built-in Kotlin；Compose fixture |

这些配对来自各 AGP 线的官方兼容表：[AGP 9.0](https://developer.android.com/build/releases/agp-9-0-0-release-notes)、[AGP 9.1](https://developer.android.com/build/releases/agp-9-1-0-release-notes)、[AGP 9.2](https://developer.android.com/build/releases/agp-9-2-0-release-notes)、[AGP 9.3](https://developer.android.com/build/releases/agp-9-3-0-release-notes)。它们仍然只是 Kaleido 的**候选公开矩阵**；只有完整 fixture 通过的行才能进入该版本发布的 immutable Compatibility Matrix。

建议把 JDK 21 端点实验、最新 Gradle 与最新 AGP 的额外组合、下一 AGP preview 放入非阻断前瞻任务。它们没有完整矩阵证据时不得扩大公开承诺。

### 1.2 每个矩阵行应跑什么 fixture

Gradle 把插件测试分为单元、集成和功能测试，并明确说明手工测试不能替代自动测试。[Testing Gradle plugins](https://docs.gradle.org/current/userguide/testing_gradle_plugins.html)

结合 Kaleido 已确定的 scope，每个公开矩阵行至少应跑同一套完整 Release fixture：

- 一个 `com.android.application`、一个 release variant、regular base-only AAB、依赖分析，以及 Java/Kotlin/Compose generator 适用场景；
- Core Capability Set 全开，证明代码生成与保留、类重写、资源/asset 改名与内容重写、manifest/R8 协作、最终 bundle 签名和 Release Evidence Set 闭合；
- 负向 fixture 证明：不支持的 AGP/Gradle/JDK/Build Tools/`compileSdk`/Kotlin 模式在注册 transform、读取 secret 或改写 artifact 前失败；dynamic feature、非应用工程和其他不支持 topology fail closed；
- 真实消费测试通过 marker publication 使用 `plugins { id(...) version ... }`，避免只证明实现 JAR 能被测试类路径加载；
- 从完全干净的临时消费仓库解析发布物，避免依赖源码工程、组合构建或开发机全局缓存。

“Sample App”和“Sana Reference Consumer”可以共享相同验收协议，但不能互相替代：前者给出可重复的最小边界，后者覆盖真实规模和真实依赖图。两者都应只对公开矩阵中的行承担 release-blocking 义务；否则矩阵成本会失控。

### 1.3 bundletool 能证明什么

Android 官方将 bundletool 定义为 Android Studio、AGP 与 Google Play 使用的底层 App Bundle 工具；它可以验证 bundle、从 AAB 生成设备 APK 集并安装与已连接设备匹配的 split APK。[bundletool overview](https://developer.android.com/tools/bundletool)

可自动化的验收层级如下：

| 层级 | 官方能力 | 能证明 | 不能证明 |
|---|---|---|---|
| AAB 结构 | `bundletool validate` | bundle 能通过 bundletool 的结构/模型验证 | Kaleido 映射语义正确、应用运行正确、Play 政策接受 |
| APK 生成 | `build-apks`，可用 `--device-spec`、`--connected-device`、`--mode=universal` | 所选配置可从 AAB 生成可安装 APK 集 | 每一种现实设备配置均成功、运行时路径正确 |
| 安装 | `install-apks` | 所选连接设备能安装与其匹配的 split 组合 | 启动、业务行为、所有 ABI/密度/语言/SDK 路径正确 |
| 运行测试 | 在受控设备上启动并执行 smoke/instrumentation | 被实际执行的场景在该设备与该 APK 集上通过 | 全设备覆盖或 Play 审核/政策保证 |

官方 App Bundle 测试文档也把“本地 bundletool/设备验证”和“通过 Play 测试轨道分发”作为不同层级；本地测试可验证 bundle 生成、针对特定配置的 APK 提取、模块兼容和被执行的应用行为，Play 测试轨道提供更接近实际分发的验证，但两者都不是所有设备与所有行为的证明。[Test your app bundle](https://developer.android.com/guide/app-bundle/test)

对 Kaleido MVP，建议每个公开矩阵行阻断于：

1. 最终 AAB 通过固定版本 bundletool `validate`；
2. 通过一组版本化 device specs 运行 `build-apks`，覆盖 MVP 选择的 ABI、密度、语言和 SDK 边界；
3. 至少一个受控设备/模拟器安装并启动完整 Core Capability Set fixture，执行能触发生成代码、重写类、重命名资源/asset 与 Compose retained entry 的 smoke；
4. 将设备型号/spec、系统版本、bundletool 版本、APK set digest 和测试结果写入 Release Evidence Set。

第 3 项是 **Kaleido 产品建议，不是 Plugin Portal 的发布要求**。如果首发不愿把设备基础设施设为阻断门禁，必须把公开声明降为“结构与 APK 生成已自动验证，设备运行待人工验证”，不能把 `install-apks` 或静态 `build-apks` 描述成端到端运行证明。

bundletool 支持 `get-size total` 估计按维度的压缩下载大小；可用它测硬化前后 fixture 的下载体积变化，但这是测量手段，不提供允许的 Kaleido 增量阈值。[Measure app size](https://developer.android.com/tools/bundletool#measure_size)

## 2. 映射与崩溃可读性

Android 官方提醒 `mapping.txt` 在每次 build 都会被覆盖，因此必须为每次发布保存一份；retrace 使用该 mapping 恢复 R8 改写后的栈信息。[Troubleshoot R8 optimization](https://developer.android.com/topic/performance/app-optimization/troubleshoot-the-optimization)

R8 mapping 格式可包含版本、source-file、residual-signature 等元数据；较旧 retrace 遇到无法理解的更新 mapping 信息时会忽略并警告。因此不能只保存一个“类名对照表”，也不能假设所有 retrace 版本都能解释未来 mapping。[R8 retrace/mapping specification](https://r8.googlesource.com/r8/+/refs/heads/main/doc/retrace.md)

外部最低事实只有“要能 retrace 就必须保存与该发布物匹配的 mapping”。Kaleido 还叠加了自己的类重写、资源/asset 和 Compose provenance，所以建议 release-blocking 地：

- 原样归档 R8 mapping，并保存 Kaleido raw class/resource/asset/Compose 映射及 deterministic composed mapping；
- 记录 R8/AGP 坐标、mapping 版本与 map ID（若存在）、输入/输出 digest、composer 版本；
- 用 fixture 中一条真实混淆栈同时经过 R8 与 Kaleido 的映射链，断言能回到原始类/方法/source position；
- 保证 Artifact Report reader 的 current + previous major 读取窗口与 mapping schema 迁移测试同步；
- 把上传 Play Console、Crashlytics 或其他崩溃平台留给 Consumer Project 的发布集成，不把某一家平台上传凭据变成 Kaleido 核心发布条件。

## 3. 缓存、复现与性能/体积门禁

### 3.1 四个门禁必须独立

Gradle 将 Configuration Cache 与 Build Cache 明确区分：前者缓存配置阶段结果，后者复用任务输出。配置缓存应在第二次相同 invocation 中显示复用；问题默认导致失败，而不是长期用 warning 掩盖。[Configuration Cache](https://docs.gradle.org/current/userguide/configuration_cache.html)、[Enable and verify Configuration Cache](https://docs.gradle.org/current/userguide/configuration_cache_enabling.html)、[Debug Configuration Cache](https://docs.gradle.org/current/userguide/configuration_cache_debugging.html)

Gradle 的 Build Cache 调试指南分别要求先验证不使用缓存时输出 up-to-date，再填充空缓存、clean 后验证 `FROM-CACHE`，并用第二个 checkout 证明可迁移性。[Debug the Build Cache](https://docs.gradle.org/current/userguide/build_cache_debugging.html)

Gradle 对 reproducible build 的定义是同一源码在不同时间/机器产生 byte-for-byte 相同输出；Gradle 9 的 archive tasks 默认使用可复现顺序与时间戳，但这不自动证明自定义转换、ZIP 重写或签名可复现。[Gradle security best practices: reproducible builds](https://docs.gradle.org/current/userguide/best_practices_security.html#reproducible_builds)

所以 ticket 10 应保留四个独立的发布门禁：

1. **Configuration Cache**：同一 Release invocation 第二次明确复用，无 Kaleido 配置缓存问题；
2. **no-clean up-to-date**：关闭 Build Cache，第二次相同 invocation 的确定性阶段均 `UP-TO-DATE`；
3. **Build Cache**：空缓存填充后 clean 命中，并在 relocation checkout 中命中；签名、最终验证和原子 publication 保持 non-cacheable；
4. **clean byte reproduction**：禁用 Build Cache，在独立 clean workspace 中产生 byte-for-byte 相同的 transformed unsigned AAB 和确定性证据文件。

签名和外部工具可能引入允许的变化，因此 Kaleido 当前把字节复现边界放在 transformed unsigned AAB 是合理且可审计的；最终已签名 AAB 应验证内容闭包、签名身份和输入/output digest，而不是错误承诺跨运行字节相同。

### 3.2 官方没有 Kaleido 可直接采用的数值预算

Gradle 的性能指南建议先测量、建立 baseline、使用 Build Scan/profiler、减少配置工作并让任务增量化/可缓存，但没有规定第三方插件的统一耗时、CPU、heap、缓存条目或 JAR 大小上限。[Gradle performance guide](https://docs.gradle.org/current/userguide/performance.html)

Plugin Portal 的审核规则同样没有数值性能或体积预算。[Plugin approval and publishing rules](https://plugins.gradle.org/docs/publish-plugin) Android 的 App Bundle 文档给出的应用交付大小限制，是 Consumer App 的分发上限，不是 Kaleido 可消耗的开销预算。[App Bundle FAQ](https://developer.android.com/guide/app-bundle/faq)

因此建议由 Kaleido 决策并版本化以下**产品预算**，但本研究不伪造具体阈值：

- 固定 cold/warm、cache-disabled/cache-hit 场景，分别测配置时间、关键 stage wall time、峰值 RSS/heap（若测量工具可靠）与总 Release wall time；
- 同时测最小 Sample App 与 Sana Reference Consumer，记录主机 CPU、内存、OS、JDK、Gradle/AGP、守护进程状态、预热次数、重复次数和统计量；
- 对未硬化基线报告绝对值和增量百分比；对 AAB/APK 下载大小、插件 implementation JAR、Release Evidence Set 分别设预算；
- 阈值变化是显式发布决策，不能因为 CI 抖动自动漂移；首次无稳定数据时可以先把趋势报告设为阻断前观察项，但公开 MVP 前应给出明确门禁或明确写成“无性能承诺”。

## 4. Plugin Portal 与 Maven publication

### 4.1 Gradle Plugin Portal 的外部要求

Portal 官方流程要求注册账号/API key，并通过 Gradle properties 或 `GRADLE_PUBLISH_KEY`/`GRADLE_PUBLISH_SECRET` 提供凭据；`publishPlugins` 执行发布。首次发布、插件 ID 变更和 group 变更会进入人工审核。[Publishing plugins to the Gradle Plugin Portal](https://plugins.gradle.org/docs/publish-plugin)

当前公开规则要求：

- 插件提供有用功能并面向足够广泛受众；
- description、tags、project URL、VCS URL 等元数据完整，文档或源码至少有英文；
- plugin ID 可追溯到作者/组织拥有的域名或身份，group 与 plugin ID 共享前缀；
- 只发布 final version，不发布 `SNAPSHOT`；fork 通常不接受，除非原项目已废弃；
- 声明插件支持的 Gradle feature compatibility；Portal 已将缺少声明标为 deprecated，并预告未来拒绝；
- 遵守 Portal Terms 和 Code of Conduct。[Portal publishing rules](https://plugins.gradle.org/docs/publish-plugin)、[Portal Terms](https://plugins.gradle.org/docs/terms)

Gradle 的 `java-gradle-plugin` 会生成 plugins DSL 所需的 marker publication；Plugin Publish Plugin 会发布实现 artifact 与 marker。官方还提供 `./gradlew publishPlugins --validate-only` 做不实际发布的 Portal 校验。[Publishing binary plugins](https://docs.gradle.org/current/userguide/publishing_gradle_plugins.html)

另一个容易混淆的门禁是 `validatePlugins`：它静态分析插件类、task 和 artifact transform 的 Gradle 属性注解；`java-gradle-plugin` 会自动加入该任务。它与 `publishPlugins --validate-only` 检查的是不同层面，应分别执行。[`ValidatePlugins`](https://docs.gradle.org/current/javadoc/org/gradle/plugin/devel/tasks/ValidatePlugins.html)

Kaleido 建议的 Portal 发布顺序是：

1. `validatePlugins` 和全部单元/TestKit/Release fixture 门禁；
2. 在隔离临时 Maven 仓库消费 implementation + marker publication；
3. `publishPlugins --validate-only`；
4. 人工批准发布版本、release notes、compatibility matrix 和证据摘要；
5. `publishPlugins`；
6. 从公开 Portal 在全新 Consumer fixture 中解析该不可变版本并跑最小 Release smoke。

第 5 步是不可轻易回滚的外部动作；研究报告只定义门禁，不授权执行发布。

### 4.2 Gradle/Maven 元数据、校验和与签名

Gradle Maven Publish publication 可以同时生成主 artifact、POM 与 Gradle Module Metadata；后者保留 variant-aware 模型并在发布前校验 variant 名、attribute、capability 和 dependency version 等一致性。[Gradle Module Metadata](https://docs.gradle.org/current/userguide/publishing_gradle_module_metadata.html)、[Maven Publish Plugin](https://docs.gradle.org/current/userguide/publishing_maven.html)

Gradle 为发布文件生成 SHA-256 与 SHA-512 校验和；应用 Signing Plugin 后可以签署 publication 的 artifacts 和 metadata，包括 POM 与 module metadata。[Publishing setup](https://docs.gradle.org/current/userguide/publishing_setup.html)、[Signing publications](https://docs.gradle.org/current/userguide/publishing_signing.html)

Module Metadata 默认不含 build identifier，因而可复现；显式 `withBuildIdentifier()` 会使每次输出不同。Kaleido 若把独立 clean-byte reproduction 设为发布门禁，就不应启用该 build identifier。[Gradle Module Metadata reproducibility](https://docs.gradle.org/current/userguide/publishing_gradle_module_metadata.html#sec:publishing_gradle_module_metadata)

若 Kaleido **另外发布 Maven Central**，Central 的官方要求还包括 sources/Javadoc JAR、每个文件的 checksum 和 PGP/GPG `.asc`、完整 POM（name/description/url/license/developer/SCM）、已验证 namespace/group，并且只能发布不可替换的 release。[Maven Central requirements](https://central.sonatype.org/publish/requirements/)、[Namespace verification](https://central.sonatype.org/register/namespace/)

这些 Central 条件不能误写成 Plugin Portal 的强制条件。建议 MVP 先明确渠道：

- **Portal-only**：满足 Portal 规则；仍建议签署 publication、保留 Gradle Module Metadata 和校验和，以便消费端验证；
- **Portal + Central**：两套外部要求同时阻断，并验证 marker、implementation、POM/module、sources/Javadoc、checksums/signatures 在两个渠道的一致坐标与可消费性。

## 5. Apache-2.0 分发与来源声明

Apache License 2.0 第 4 节对再分发的核心要求是：向接收者提供许可证副本；对修改过的文件给出显著修改声明；在 source-form derivative 中保留相关 copyright、patent、trademark 与 attribution notices；若上游 Work 带有 `NOTICE`，还要把其中相关的 NOTICE 内容放入合规位置。[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0.html)

这意味着：

- Kaleido 自身选择 Apache-2.0 时，公开分发中应带完整 `LICENSE`；源码文件可使用 SPDX/短头标，但不能用短头标代替许可证正文；
- 复用 AabResGuard 等 Apache-2.0 代码时，保存精确上游 revision、来源 URL、复制/改写文件清单，并在被修改文件或其明确关联记录中标注修改；保留适用的版权与归属声明；
- `NOTICE` **不是仅因为选择 Apache-2.0 就自动强制产生**；它在被纳入的上游 Work 已有相关 NOTICE 内容时触发传递义务。Kaleido 可以自建 NOTICE 汇总归属，但不能把它写成许可证本身一律要求；
- AndroidJunkCode 的 source/generated 边界和 XmlClassGuard 的无许可证边界仍按既有票据处理，不因 Kaleido 自己开源而获得复用授权。

Apache 官方 FAQ 对非 ASF 项目的建议也是在 Work 中包含许可证副本，并按实际需要维护 NOTICE；ASF 自己的发布政策不能泛化成所有 Apache-2.0 项目的额外强制规则。[Apache License FAQ](https://www.apache.org/foundation/license-faq)

Apache-2.0 本身不强制发布 sources JAR；Plugin Publish Plugin 会为插件 publication 提供 sources/Javadoc artifacts，而 Maven Central 另有 sources/Javadoc 要求。应把“许可证义务”和“发布渠道要求”分开审计。

## 6. SBOM、SLSA 与 source provenance

Plugin Portal 与 Maven Central 的上述公开要求没有把 SBOM 或 SLSA 等级列为发布前提，因此二者对 Kaleido MVP 都属于供应链**产品选择**，不是当前外部强制项。

如果生成 SBOM，应选择并固定一个 owning specification，而不是制造私有字段：SPDX 3.0.1 覆盖软件组成、构建、身份、来源、许可证和安全等信息；CycloneDX 1.7 覆盖 components、dependency graph、completeness、provenance 与 licensing。[SPDX 3.0.1 scope](https://spdx.github.io/spdx-spec/v3.0.1/scope/)、[CycloneDX 1.7 overview](https://cyclonedx.org/specification/overview/)

如果声称 SLSA，必须满足该等级的真实要求：Build L1 provenance 要通过 digest 标识输出并描述 build；L2 要求 authenticated hosted build；L3 进一步要求隔离和不可伪造等属性。provenance 的 subject、build definition 和 run details 需要能被验证，而不是仅在 CI 中自行写一个无认证 JSON。[SLSA v1.2 build requirements](https://slsa.dev/spec/v1.2/build-requirements)、[SLSA build provenance](https://slsa.dev/spec/v1.2/build-provenance)、[SLSA attestation model](https://slsa.dev/spec/v1.2/attestation-model)

SLSA Source track 要求 VCS，并在更高等级加入历史、source provenance、强制控制和双人审查；具体 source-provenance 格式由 Source Control System 定义，SLSA 不提供一个可任意自封的通用 predicate。[SLSA v1.2 source requirements](https://slsa.dev/spec/v1.2/source-requirements)

对首发最稳妥的建议是：

- 必须有 authoritative source repository、不可变 release tag/revision，并在一个 release manifest 中绑定 implementation/marker/source/Javadoc/POM/module/checksum/signature 与公开证据的 digests；
- 可发布一个版本固定、经 schema 校验的 SPDX 或 CycloneDX SBOM，但只选择一种作为规范主格式；
- 除非发布流水线真的提供符合 SLSA 的认证 provenance，否则只称“release manifest / source revision and artifact digests”，不要声称 SLSA L2/L3；
- SBOM、attestation 和 release manifest 都不得包含 upload key、keystore password、绝对用户路径或其他 secret/host-specific 信息。

## 7. 建议的发布阻断清单

| 门禁 | 外部强制要求 | Kaleido MVP 建议 | 通过证据 |
|---|---|---|---|
| Plugin Portal 账号、ID、metadata、final version、审核规则 | 是（Portal 发布时） | 是 | `publishPlugins --validate-only` 输出；审核状态 |
| `validatePlugins` | 不是 Portal 人工审核的同义词 | 是 | task 成功且无 suppressed problem |
| implementation + marker publication 可消费 | marker 为 plugins DSL 所需 | 是 | 全新临时仓库/fixture 的 plugin resolution 与 Release build |
| A1–A4 精确兼容矩阵 | 否 | 仅把全部通过的行公开 | 每行完整 Release fixture + immutable matrix |
| `bundletool validate` + device-spec `build-apks` | 否 | 是 | 固定 bundletool 版本、命令、spec 与 digest |
| 受控设备安装、启动、行为 smoke | 否 | 建议首发阻断 | 设备/spec/OS/APK-set digest/结果 |
| R8/Kaleido mapping 归档和真实 retrace | Android 发布可读性所需，但非 Portal 条件 | 是 | mapping closure + retraced fixture stack |
| 四类缓存/复现门禁 | 否 | 是 | 分开的 task outcome、cache log、跨 checkout 命中、unsigned AAB byte digest |
| 性能/内存/体积预算 | 没有通用官方数值 | 必须由产品定阈值或明确无承诺 | 固定环境、基线、重复样本、统计结果 |
| Apache-2.0 LICENSE/NOTICE/attribution/change audit | 是（按实际再分发内容触发） | 是 | source/distribution 审计和 provenance inventory |
| GMM/checksums/signatures | publication 模型/渠道相关；Central 有更强制要求 | Portal-only 也建议 | publication 文件清单、digest、signature verification |
| SBOM/SLSA | 当前 Portal/Central 不强制 | SBOM 可选；SLSA 只按真实能力声明 | schema validation、attestation verification |
| Release Evidence Set 原子发布 | 否 | 是 | final AAB 与 reports/mappings/digests 同一成功边界闭合 |

## 8. 对 ticket 10 的决策影响

下一轮只需产品确认六项，不需要再研究工具是否“理论可用”：

1. **公开兼容行**：首发承担 A1–A4 全部阻断成本，还是只公开实际能持续跑完整 fixture 的子集；未经完整证明的行不发布。
2. **安装验收**：建议至少一个受控设备/模拟器的安装、启动与关键行为 smoke 作为首发阻断；device-spec `build-apks` 作为每行静态门禁，不能替代运行证明。
3. **fixture 集合**：最小 Sample App 每行必跑；Sana Reference Consumer 是全部公开行必跑，还是只跑当前锚点并作为规模/performance gate。
4. **性能与体积预算**：官方没有现成数值，必须确定测量环境、baseline、重复统计和可接受阈值；若暂不设阈值，要明确首发无数值性能承诺。
5. **发布渠道**：建议首发以 Gradle Plugin Portal 为唯一强制渠道；只有产品明确需要 Maven Central 坐标时，才引入 Central 的 namespace、PGP 和双渠道一致性门禁。
6. **供应链级别**：建议首发必须有不可变 source revision + release manifest + artifact digests；SBOM 可选但固定一种规范；不在没有认证 build provenance 时声称 SLSA 等级。

这些决策不改变现有 Core Pipeline：最终签名 AAB、Artifact Report、映射、兼容矩阵和其他证据仍须作为一个 Release Evidence Set 原子成功；可复现字节边界仍止于 transformed unsigned AAB。
