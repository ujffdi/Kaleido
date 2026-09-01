# Kaleido 升级、弃用与迁移政策证据

研究日期：2026-08-31

## 问题与边界

本报告为 Ticket 18 提供事实底座：Kaleido 应如何发布兼容范围、应用语义化版本、弃用 DSL 与机器格式、保存和读取旧 Release Evidence Set，以及在插件升级后处理 Gradle 缓存与迁移诊断。

报告只使用 Gradle、Android、R8、Semantic Versioning、JSON Schema 和 IETF 的一手资料。外部规范没有替 Kaleido 决定具体支持窗口；下文严格区分“可验证事实”与“建议决策”。

仓库已经固定以下前提，本报告不重新讨论：

- MVP 的候选兼容范围是 AGP `9.0.1`–`9.3.2` 与各线文档化 Gradle 基线，但只有加入发布阻断矩阵的精确组合才能成为正式支持项。见 [Ticket 04](../issues/04-research-version-compatibility.md) 与 [`version-compatibility.md`](version-compatibility.md)。
- `com.tongsr.kaleido`、`kaleido {}`、Safe Defaults、Profile、声明语义和稳定诊断属于 Adoption Contract；内部引擎、任务名和任务顺序不是公共接口。见 [Ticket 06](../issues/06-define-adoption-interface-and-profiles.md)。
- 每个成功 Release variant 产生一个不可分割的 Release Evidence Set；Artifact Report、原始/组合 mapping、可复现 unsigned 边界和发布证据已有固定含义。见 [Ticket 08](../issues/08-define-failure-reporting-and-reproducibility.md)。
- Consumer Project 是当前应用 Kaleido 的 Application Project；每个 Release variant 隔离，unsupported topology 不可通过 force 或 Escape Hatch 放行。见 [Ticket 09](../issues/09-define-supported-project-topology.md)。

## 一、Gradle 插件发布与兼容声明：事实

### 插件坐标与解析元数据

Gradle 发布一个可由 `plugins {}` 解析的二进制插件时，至少需要 plugin ID、implementation class、group 和 version；发布工具生成 POM、Gradle Module Metadata 和 plugin marker module。未发布 marker artifact 的插件不能直接通过通常的 Plugin DSL 解析。[Gradle: Preparing to Publish Plugins](https://docs.gradle.org/9.7.1/userguide/preparing_to_publish.html)、[Gradle: Publishing Plugins](https://docs.gradle.org/9.7.1/userguide/publishing_gradle_plugins.html)

Gradle 的插件变体可以声明 `org.gradle.plugin.api-version`。解析时，Gradle 选择“不高于当前 Gradle”的最高兼容插件变体；这个属性描述的是 **Gradle API 版本**，不是 AGP、JDK、Android SDK 或 Kaleido Artifact Report schema 的兼容范围。[Gradle: Binary Plugins](https://docs.gradle.org/9.7.1/userguide/implementing_gradle_plugins_binary.html#providing_multiple_variants_of_a_plugin)、[Gradle: Variants and Attributes](https://docs.gradle.org/9.7.1/userguide/variant_attributes.html#sub:gradle_plugin_api_version)

Gradle TestKit 可以通过 `GradleRunner.withGradleVersion(...)` 对真实隔离构建做跨版本功能测试。官方同时说明：使用 `compileOnly` 依赖另一个插件 API 时，标准 `withPluginClasspath()` 注入不够，测试应先把被测插件发布到本地 Maven 仓库，再由 fixture 正常解析依赖。[Gradle: Testing Build Logic with TestKit](https://docs.gradle.org/9.7.1/userguide/test_kit.html)

**没有一份 Gradle 或 Android 规范规定第三方 AGP 插件必须采用固定的 N、N-1 或三年支持窗口。** Gradle 自身当前只积极支持当前和前一 major 的最新 minor，这是 Gradle 产品的维护政策，不会自动成为第三方插件的兼容承诺。[Gradle: General Best Practices](https://docs.gradle.org/9.7.1/userguide/best_practices_general.html#use_the_latest_minor_version_of_gradle)

Android Studio 的“三年 AGP 兼容政策”只规定 Android Studio 可以打开哪些 AGP 版本；它不证明任意第三方插件与这些 AGP 版本兼容。[Android: About Android Gradle Plugin](https://developer.android.com/build/releases/about-agp#android_gradle_plugin_and_android_studio_compatibility)

### 可公开检测的运行版本

Gradle 公共 API 提供当前运行版本（`Gradle.getGradleVersion()` / `GradleVersion.current()`）。AGP 公共 `AndroidComponents.pluginVersion` 属性提供当前 AGP 版本，并自 AGP 8.2 起存在。因此 Kaleido 可以在不读取内部 AGP 类型的前提下进行确定性的兼容检查。[Gradle API: GradleVersion](https://docs.gradle.org/9.7.1/kotlin-dsl/gradle/org.gradle.util/-gradle-version/)、[AGP 9.2 API: AndroidComponents](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/AndroidComponents#pluginVersion)

## 二、Gradle 与 AGP 的弃用、移除和升级：事实

Gradle 将公开、incubating 和 internal feature 分开。公开 feature 不会在没有弃用期的情况下被有意移除；弃用后会产生运行时 warning，并可能在下一个 Gradle major 移除。Gradle 的兼容政策不覆盖 internal API，也不保证 incubating API。[Gradle: Feature Lifecycle](https://docs.gradle.org/9.7.1/userguide/feature_lifecycle.html)

AGP 的政策与事实例子同样说明“弃用不是永久兼容”：

- AGP 9.0 将新版 DSL 与 Variant API 放入稳定 `gradle-api` artifact，旧 DSL/Variant API 被弃用；私有 internal 类型仍可能随时破坏。[Android: AGP DSL/API Migration Timeline](https://developer.android.com/build/releases/gradle-plugin-roadmap)
- 当前路线图计划在 AGP 10 删除旧 DSL/Variant API 及 9.x 的兼容退出开关，并要求插件迁移到 lazy Provider、Sources、Artifacts 和 Instrumentation APIs；时间表明确标为估算。[Android: AGP DSL/API Migration Timeline](https://developer.android.com/build/releases/gradle-plugin-roadmap#agp_100_late_2026)
- Transform API 在 7.2 弃用并在 8.0 移除；官方 tracker 将 deprecated version、removed version 和替代 API 分开列出。[Android: AGP API Updates](https://developer.android.com/build/releases/gradle-plugin-api-updates)

Android 的升级指导要求联合检查 AGP、Gradle、Android Studio、SDK Build Tools、NDK 和 JDK；开发依赖 AGP 的 Gradle 插件也可能必须升级，因为 AGP 会弃用并移除 API。AGP Upgrade Assistant 理解 AGP/Gradle 以及部分第三方插件的兼容关系，但无法替第三方插件证明其完整 Release 行为。[Android: Upgrade Dependency Versions](https://developer.android.com/build/version-upgrade-strategies)、[Android: AGP Upgrade Assistant](https://developer.android.com/build/agp-upgrade-assistant)

Gradle 9 自身给出了同类边界：它不再支持 AGP 8.4 之前的版本，因为更老 AGP 依赖已移除的 Gradle API。这证明“Gradle 可运行”与“某 AGP/插件组合可运行”必须作为组合验证，而不能只比较单个最低版本数字。[Gradle: Upgrading to Gradle 9](https://docs.gradle.org/9.7.1/userguide/upgrading_major_version_9.html#minimum_supported_android_gradle_plugin_version)

## 三、Semantic Versioning 对 Kaleido 的适用范围：事实

Semantic Versioning 2.0.0 要求软件先精确定义 public API；public API 可以由代码或文档定义。`1.0.0` 定义首个稳定 public API，`0.y.z` 被明确视为初始开发、任何内容都可能改变。[Semantic Versioning 2.0.0, rules 1, 4, 5](https://semver.org/spec/v2.0.0.html)

对 `x > 0`：

- 仅修复错误且保持 API 兼容，增加 patch；
- 增加向后兼容功能，增加 minor；
- **把 public API 标记为 deprecated 也必须增加 minor**；
- 引入任何不兼容 public API 变更，增加 major；
- 已发布版本的内容不得被修改，任何修改必须发布新版本。[Semantic Versioning 2.0.0, rules 3, 6–8](https://semver.org/spec/v2.0.0.html)

SemVer 不规定弃用必须持续几个月、几个 minor，也不定义 JSON/R8 schema 的兼容方向。Kaleido 必须先把 DSL、默认行为、诊断、外部文件格式和兼容矩阵中哪些内容算 public API 写进自身政策，SemVer 才能产生明确结论。

## 四、R8/ProGuard mapping 的版本与历史可用性：事实

Android 官方说明，R8 `mapping.txt` 用于 retrace 已混淆堆栈，而且构建会覆盖该文件；因此必须为每个发布版本保存对应 mapping。错误版本的 mapping 不能可靠解释另一个发布产物。[Android: Retrace and Preserve Mapping Files](https://developer.android.com/topic/performance/app-optimization/troubleshoot-the-optimization#decode-stack-trace)

R8 mapping 的扩展信息采用 JSON-like comment，并有显式版本头：

```text
# {"id":"com.android.tools.r8.mapping","version":"2.0"}
```

没有版本头时按 version zero 解释。支持该版本机制的 retrace 对更高版本信息应忽略为普通 comment，并应在输入 mapping 版本高于工具支持版本时发出 warning。这提供了有限的 forward tolerance，但不是“任意未来 retrace 永久兼容”的保证。[R8: Retrace and Map File Versioning](https://r8.googlesource.com/r8/+/816b0dc28020b0b8a0975a81f1ab17545dbed00f/doc/retrace.md#Additional-information-appended-as-comments-to-the-file)

R8 的 mapping header 还包含 compiler/version、`pg_map_id` 和 `pg_map_hash`。默认 map ID 是 mapping 内容 SHA-256 的截断前缀，完整 map hash 也写入 header。[R8 source: `ProguardMapSupplier`](https://r8.googlesource.com/r8/+/985fba43c558eb6de3ebe6318a5149aa673fedb2/src/main/java/com/android/tools/r8/naming/ProguardMapSupplier.java)

mapping 2.2 的 residual-signature metadata 对普通堆栈 retrace 没有影响，但 R8 文档明确指出，它对 Retrace API 处理 residual signature 和 **mapping composition** 是必要的。只组合类名/方法名行而丢弃 metadata，不能声称与 R8 完整 mapping 语义兼容。[R8: Residual Signature](https://r8.googlesource.com/r8/+/816b0dc28020b0b8a0975a81f1ab17545dbed00f/doc/retrace.md#Residual-signature-Introduced-at-22)

R8 的这些机制解决的是 mapping 文件自身的格式与关联，不提供跨应用发布的名称稳定性保证，也不要求后一次构建把前一次 mapping 当作输入。它与 Ticket 08 的“mapping 是当前构建证据、不得隐式消费旧 mapping”一致。

## 五、Artifact Report/JSON schema 演进：事实

JSON Schema Draft 2020-12 用 `$schema` 声明 dialect；缺少 `$schema` 时解释行为由实现决定。`$id` 可为 schema resource 提供稳定 URI 标识。schema 必须对其声明的 meta-schema 有效。[JSON Schema Core 2020-12, section 8.1](https://json-schema.org/draft/2020-12/json-schema-core#section-8.1)

JSON Schema 规范允许不识别的 **schema keyword** 被当作 annotation；这不是“实例 JSON 中所有未知字段都自动兼容”的承诺。实例对象是否允许额外字段由该 schema 的 `properties`、`additionalProperties`、`unevaluatedProperties` 等约束决定。[JSON Schema Core 2020-12, sections 6.5 and 11](https://json-schema.org/draft/2020-12/json-schema-core#section-6.5)

RFC 8259 指出，不同 JSON 实现可能向调用方暴露或不暴露 object member ordering；不依赖成员顺序的实现互操作性更好。Kaleido 可以为字节复现另行规定 canonical serialization order，但语义 reader 不应把 JSON object 顺序当作字段含义。[RFC 8259, section 4](https://datatracker.ietf.org/doc/html/rfc8259#section-4)

JSON、JSON Schema 与 SemVer 都没有规定一个通用的“报告 schema minor 一定可由旧 reader 读取”规则。兼容方向至少有两种，必须由 Kaleido 明确：

- **backward-reading**：新 reader 能读取旧 report；
- **forward-reading**：旧 reader 能读取新 report。

例如，添加 optional 字段通常能保留 backward-reading，但若旧 schema 使用 `additionalProperties: false`，旧 validator 仍会拒绝新 report；这不违反 JSON Schema，说明兼容承诺必须由版本化 schema 和 reader tests 证明。

## 六、Gradle task input/output schema 与缓存：事实

Gradle 的 up-to-date 检查会 fingerprint 声明的 inputs/outputs，并把 task、task action 及其依赖实现代码也当作输入；实现无法被可靠跟踪时，Gradle会禁用 up-to-date 和 cache reuse。[Gradle: Incremental Build](https://docs.gradle.org/9.7.1/userguide/incremental_build.html#sec:how_does_it_work)

Build Cache key 包含：task type 与 implementation classpath、output property names、input property names/values、Gradle distribution/buildSrc/plugin classpath，以及影响执行的 build script 内容。task path 本身不是 key。缺失 input 会产生错误 cache hit；volatile/absolute-path input 会产生无谓 miss。[Gradle: Build Cache](https://docs.gradle.org/9.7.1/userguide/build_cache.html#sec:task_output_caching)

因此，Kaleido 升级时只要 task implementation/classpath、input property name/value、output property name 或显式 schema/policy version 发生变化，相应 cache key 就会变化。Gradle 不要求、也不提供把旧插件 task cache entry “迁移”成新 schema entry 的通用机制。cache miss 是正确失效，不是 Release Evidence Set 迁移失败。

反过来，如果 output-affecting schema/algorithm 改变却没有进入 task implementation fingerprint 或显式 input，可能错误复用旧输出。因此 plan/receipt/mapping/report 的每个输出语义版本都应成为其生产任务的显式稳定 input，并由 TestKit 证明升级前后发生预期 miss、未受影响 variant 不被误失效。这个结论来自 Gradle cache key 事实，具体版本字段设计属于 Kaleido 决策。

## 七、建议决策及其影响

以下是基于上述事实、且与当前 Core Pipeline 一致的建议；它们不是外部规范强制要求。

### 1. 兼容窗口用“不可变精确矩阵”，不用模糊 N-1

建议每个 Kaleido release 发布一张精确、不可变的兼容矩阵，至少列出 Kaleido、AGP patch、Gradle、运行 JDK、Build Tools、compileSdk 与 Kotlin mode。只有完整 Release fixture 成为阻断 CI 的组合才标记 `supported`；preview/未测试组合标记 `unsupported`，前瞻 shadow job 不扩大承诺。

对 MVP，Ticket 04 的 AGP `9.0.1`–`9.3.2` 候选范围只能在四条对应矩阵全部通过后整体发布；否则先发布已证明的子集。加入新的 AGP stable line 是向后兼容的 Kaleido minor；删除已发布支持组合会缩小 Adoption Contract，应只发生在 Kaleido major，并提前弃用。

不建议为 AGP/Gradle 构建多个 Kaleido plugin variants，除非未来确实需要不同 Gradle public API 实现；`org.gradle.plugin.api-version` 不能表达 AGP 兼容矩阵。MVP 应保持一个只使用最低支持 AGP 的稳定 public API 编译出来的插件 binary，并对每个运行组合做 TestKit 验证。

### 2. 把公开 surface 明确纳入 Kaleido SemVer

建议公开 `1.0.0` 时把下列内容定义为 public API：

- plugin ID、DSL 类型/属性/枚举、Provider 语义、默认值、验证与失败行为；
- Safe/Full Profile 与每项声明的效果；
- 正式支持矩阵；
- 稳定诊断 code、severity 和 machine fields；
- Artifact Report schema、Release Evidence Set member/identity 规则；
- Kaleido-owned raw/composed/resource mapping 外层格式及其与 R8 mapping 的绑定语义；
- 文档化的 CLI/文件路径或 reader/verifier interface（若公开）。

内部 task 名、task graph、engine 类型和中间实现仍不成为 public API。内部变更可以是 patch/minor，只要外部行为保持兼容；由此产生的 cache miss 不构成 SemVer break。

建议版本判定如下：

| 变更 | Kaleido 版本影响 |
| --- | --- |
| 保持公开契约的错误修复 | patch |
| 新增 optional DSL、支持新的工具链组合、添加可忽略的 report 字段、新增 diagnostic code | minor |
| 标记 public DSL/schema field 为 deprecated | minor（SemVer 明确要求） |
| 删除/重命名 DSL、改变既有默认或含义、删除已支持工具链、改变 diagnostic machine identity、破坏 report/mapping reader | major |
| 改内部 task/plan schema，但旧公开 evidence 仍可读取 | patch 或 minor；明确宣布 cache reset |

### 3. 弃用期有明确下限，删除只进 major

建议 public DSL、诊断字段和外部 schema member 的弃用遵循：

1. 在 minor release 标记 deprecated，保持旧写法/字段原有语义；
2. 提供稳定 `KLD-DEPRECATION-*` warning，含旧 declaration path、replacement、首次弃用版本和计划移除 major；
3. 至少跨过一个后续 non-preview minor 且不少于 90 天；
4. 仅在下一个 Kaleido major 删除；删除后以稳定 error 指向可机械执行的迁移示例，禁止 silent ignore 或 last-write-wins fallback。

“一个 minor + 90 天”是建议的项目政策，不是 SemVer/Gradle 强制时长。它给 Consumer Project 一个可测试的 bridge release，又避免“只要没有 major 就无限维护”的承诺。

### 4. 外部 evidence schema 与内部 task schema 分域版本化

建议不要把 Kaleido plugin version 直接当作所有文件的 schema version：

- `artifact-report.json` 的 `schema` 记录稳定 schema family、`major.minor`、不可变 schema URI 与 JSON Schema dialect；每个已发布 schema 文档永久不覆写。
- report schema minor 只允许新 reader 读取旧 report 的 backward-compatible 添加；字段删除、rename、type/units/requiredness/semantic change 增加 schema major。
- 同一 Kaleido major 的 reader/verifier 必须读取该 major 产生的所有 report schema minor；下一 Kaleido major 至少继续读取前一个 report schema major。更老 evidence 仍可依靠不可变 schema、记录的 toolchain 和归档工具解释，但不承诺由最新 Kaleido binary 无限期执行。
- Adoption Plan、stage plan/receipt 等内部 schema 分别具有显式 version input。它们可随实现变化并使旧 cache entry miss，不提供跨插件版本 cache migration API。
- 历史 Release Evidence Set 永不原地改写。若以后提供迁移工具，只能生成带 `sourceReleaseEvidenceSetId`、源 member digests、目标 schema 和 migration-tool version 的派生视图；原始集合仍是审计源。

这个设计把“旧 evidence 仍可解释”与“最新插件永远兼容所有旧 runtime/cache”分开，满足有限兼容而不破坏证据不可变性。

### 5. mapping 按发布保留，不承诺跨发布名称稳定

建议每个 Release Evidence Set 永久保存该次发布的 raw Kaleido class map、原始 AGP/R8 map、retrace-compatible composed map 和 resource map，并记录：

- R8 compiler version、R8 mapping version、原始 `pg_map_id`/`pg_map_hash`；
- 每个 raw/composed/resource mapping 的完整 SHA-256；
- 组合器版本与输入 mapping digests；
- 能完成验收的 retrace tool coordinates/version。

组合器必须保留或正确重写 R8 versioned metadata，包括 composition 所需 residual signatures，并用该发布的实际 obfuscated stack fixture 做 retrace 测试。Kaleido 不保证不同 app release 使用稳定混淆名，不把旧 mapping 隐式作为新构建输入，也不把“最新版 retrace 可以读取”作为旧 evidence 唯一可用路径。

### 6. 不支持的工具链要在任何变换前 fail closed

建议 Kaleido 通过 Gradle/AGP public version API 读取精确版本，在配置 Adoption Plan、注册变换任务或触碰产物前校验兼容矩阵。unsupported、preview、已移除组合以稳定 `KLD-COMPAT-*` error 失败，字段至少包括 Consumer Project、检测到的 Kaleido/AGP/Gradle/JDK/Build Tools 版本、支持的精确组合、兼容的升级/降级目标和迁移文档链接；不提供 force override。

仅当当前组合仍受支持但已计划移除时发 deprecation warning。普通“存在更新版本”不是 warning，更不能改变 report 的 deterministic evidence。

### 7. Consumer Project 的 bridge upgrade 路径

建议每个可能破坏兼容的发布都提供一条可重复路径：

1. 保存并验证当前 Release Evidence Set，不改写其 AAB、mapping 或 report；
2. 阅读目标 Kaleido 的精确兼容矩阵，选择 AGP/Gradle/JDK 组合；
3. 先升级到同时支持旧组合与目标组合的最新 Kaleido bridge minor，修完所有 `KLD-DEPRECATION-*`；
4. 使用 AGP Upgrade Assistant/官方 release notes 升级 AGP、Wrapper、JDK/SDK，并按一次一个维度定位失败；
5. 执行 clean Release build。预期旧 task/configuration cache 可能失效，不把 miss 当作数据迁移；
6. 验证新的 Release Evidence Set closure、retrace、bundle/signing 和 reproducibility gates；旧 evidence 继续由其原 schema/toolchain 解释。

若不存在同时覆盖两端的 bridge release，迁移文档必须明确要求两段升级，不应让 Consumer Project 猜测中间版本。

## 八、建议的发布阻断验收

Ticket 18 的最终政策应要求以下自动化证据：

- 每个 `supported` 精确矩阵行执行完整 TestKit Release fixture；矩阵外的相邻旧/新/preview 版本以稳定 `KLD-COMPAT-*` fail。
- 每个 deprecated DSL/field 同时测试旧写法、新写法、等价 Adoption Plan、唯一 warning、无 secret/path 泄漏，以及删除 major 的 actionable error。
- report schema fixture 覆盖同-major 所有历史 minor 和前一 major；旧 report bytes/Release Evidence Set member 永不被测试迁移修改。
- raw/composed mapping fixture 保存 R8 version/ID/hash/metadata，并由记录的 retrace tool 对真实 obfuscated stack 完成 retrace。
- 插件/task/schema/policy 升级测试验证受影响 task cache miss、未受影响 variant 不误复用或误失效；从旧 cache 恢复不得产生旧 schema output。
- 升级前后各自独立通过 Ticket 08 的 Configuration Cache、up-to-date、local/relocated Build Cache 和 clean-byte reproducibility 四道门；不要求两个不同 Kaleido/toolchain 版本生成相同 bytes。

## 九、对 Ticket 18 的最短决策影响

一手资料足以支持以下收敛方向：

1. **兼容性是 Kaleido 自己发布并测试的精确矩阵，不继承 Android Studio 三年窗口，也没有通用 N-1 规范。**
2. **SemVer 作用于已声明的 Adoption Contract 与 machine interfaces；弃用进 minor，删除和支持范围收缩进 major。**
3. **历史 Release Evidence Set 保持原样；可用性来自每发布 mapping、不可变 schema、记录的 toolchain 和有限 reader window，不来自隐式 mapping 复用或无限期 latest-binary 支持。**
4. **外部 report/mapping schema 与内部 task schema 必须分域；内部升级通过 cache-key 失效，不做 cache migration。**
5. **升级必须有稳定兼容诊断和 bridge release 文档路径，并在任何 Hardening Pipeline 变换前 fail closed。**

仍需由 Ticket 18 明确选择的产品数字只有：正式 AGP 矩阵是否覆盖全部 `9.0.1`–`9.3.2`，弃用下限是否接受“一个后续 minor + 90 天”，以及 latest reader 支持“当前 report major + 前一 major”是否足够。
