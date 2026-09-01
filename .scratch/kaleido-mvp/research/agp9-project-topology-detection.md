# AGP 9 project-topology detection and runtime-dynamic boundaries

研究日期：2026-08-31

## Scope and evidence baseline

本报告为 [Define supported project topology and rejection behavior](../issues/09-define-supported-project-topology.md) 提供事实，不替 ticket 作产品决策，也不修改实现。问题限定为：Kaleido 应如何识别目标 Application module、读取 variant/topology 信息、在最终 AAB 验证模块类型，以及哪些运行时动态机制不能靠静态结构证明不存在。

AGP API 结论直接核对了当前工程使用的官方 `com.android.tools.build:gradle-api:9.2.1` source artifact（本地 SHA-256 `c50d6746ddb5dc74205043ddab77b5d76bd448a4731f012ab5061a89dc86da5c`），并与 Android Developers 的 9.2 API reference 交叉核对。AAB 模型核对 bundletool 提交 [`586a43a450712a1067f3d92cf7574dee68226302`](https://github.com/google/bundletool/tree/586a43a450712a1067f3d92cf7574dee68226302)。Gradle 行为只引用 Gradle 官方文档。

## Decision-ready answer

1. **“一个 Application module”应定义成当前 Kaleido 所应用项目是 `com.android.application`，且该 variant 的最终 Regular AAB 只有 `base` module；不应定义成遍历整个 Gradle build 后只找到一个 Application project。** 后者既与当前发布产物无关，也会访问其他 Project 的 mutable plugin/extension state，违反 Isolated Projects 约束。[Gradle Isolated Projects](https://docs.gradle.org/current/userguide/isolated_projects.html#isolated_projects_restrictions)
2. **插件类型用公开 plugin ID 与 typed AGP extension 双重确认。** `PluginManager.withPlugin("com.android.application")` 不依赖插件声明顺序：若已应用会立即执行，否则在该 plugin 应用后执行。回调内再取得 `ApplicationExtension` / `ApplicationAndroidComponentsExtension`。同时为 library、dynamic-feature、asset-pack 等已知不兼容 ID 注册 fail-fast 回调。[Gradle `withPlugin`](https://docs.gradle.org/current/kotlin-dsl/gradle/org.gradle.api.plugins/-plugin-manager/with-plugin.html)、[Application Android components extension](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/ApplicationAndroidComponentsExtension)
3. **配置期可确证的 topology 是有限的：** `ApplicationExtension.dynamicFeatures`、`assetPacks`、build types、product flavors、`buildFeatures.compose`；variant 期可确证实际 `buildType`/`productFlavors` 与最终 `isMinifyEnabled`。普通依赖可从 variant 的 `compileConfiguration` / `runtimeConfiguration` 在执行期解析，但该图只能描述已解析 component/artifact，不会替 Kaleido证明依赖的运行时行为“普通且安全”。[ApplicationExtension](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/dsl/ApplicationExtension)、[ApplicationVariant](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/ApplicationVariant)
4. **最终 AAB topology 必须再次验证。** 对 MVP 所承诺的 topology，候选必须是 `REGULAR` bundle，module map 恰好只有名为 `base` 的 `FEATURE_MODULE`，不存在额外 feature、asset、ML/AI 或 runtime-enabled-SDK dependency module。配置期空集合不是产物级证明。[bundletool `BundleModule.ModuleType`](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/java/com/android/tools/build/bundletool/model/BundleModule.java#L105-L128)、[bundletool `AppBundle`](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/java/com/android/tools/build/bundletool/model/AppBundle.java#L99-L186)
5. **DexClassLoader、任意 ClassLoader/反射、SplitInstall 调用、hotfix/plugin framework 以及 native `System.load*`/`dlopen` 的不存在性都不能由结构扫描证明。** Kaleido 最多产生“发现风险信号”或“未发现已知信号”，不能把后者写成“已证明不存在”。运行时字符串、下载内容、加密/生成字节、shading/fork、JNI 和服务器行为都能绕开静态签名。[DexClassLoader](https://developer.android.com/reference/dalvik/system/DexClassLoader)、[ClassLoader](https://developer.android.com/reference/java/lang/ClassLoader)、[Android dynamic linker](https://developer.android.com/ndk/reference/group/libdl)

## 1. Configuration-time facts

### 1.1 Current-project plugin identity

Gradle 的 `PluginManager.withPlugin(id, action)` 保证 action 在指定 plugin 已应用之后执行，并同时覆盖“Kaleido 先应用”和“Android plugin 先应用”两种顺序。因此当前 Project 的可靠正向入口是：

```kotlin
project.pluginManager.withPlugin("com.android.application") {
    val android = project.extensions.getByType(ApplicationExtension::class.java)
    val components = project.extensions
        .getByType(ApplicationAndroidComponentsExtension::class.java)
    // register finalizeDsl / beforeVariants / onVariants
}
```

`ApplicationExtension` 官方定义也明确说明它就是应用 `com.android.application` 后的 `android` block。[ApplicationExtension source/API](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/dsl/ApplicationExtension)

建议至少对下列当前 Project plugin ID 注册显式不兼容回调；这些 ID 来自 AGP 9.2.1 官方 plugin descriptors 与 Android 官方集成文档：

| Plugin ID | 类型 | 与 Kaleido MVP 的关系 |
|---|---|---|
| `com.android.application` | Application | 唯一允许 Kaleido 初始化 Hardening Pipeline 的类型 |
| `com.android.library` | Android library | Kaleido 直接应用于此 Project 时拒绝；作为 Application 的 project dependency 可保留 |
| `com.android.dynamic-feature` | Dynamic feature | 直接应用时拒绝；作为目标 bundle module 也不在 MVP topology 内 |
| `com.android.asset-pack` | Asset pack | 直接应用时拒绝；目标 Application 的 `assetPacks` 非空也不在 MVP 内 |
| `com.android.asset-pack-bundle` | asset-only bundle | 不是 Application AAB，拒绝 |
| `com.android.ai-pack` | AI/ML pack | 不是 Application module；最终会表现成非 base module，拒绝 |
| `com.android.test` | test-only Android project | 无 Release Application AAB，拒绝 |
| `com.android.kotlin.multiplatform.library` | KMP Android library | 不是 Application，直接应用时拒绝 |

Dynamic feature 的官方文档展示了 `com.android.dynamic-feature`，并说明 base app 用 `android.dynamicFeatures` 建立关系；Asset Delivery 官方文档展示了 `com.android.asset-pack` 与 app 的 `assetPacks` 列表。[Play Feature Delivery](https://developer.android.com/guide/playcore/feature-delivery)、[Play Asset Delivery](https://developer.android.com/guide/playcore/asset-delivery/integrate-java)

**限制：** `withPlugin("com.android.application")` 可以可靠确认“存在”，但它单独不能在 configuration phase 给出“此 Project 永远不会再应用 Application plugin”的结束事件。若 adoption interface 允许 Kaleido 先于 Android plugin，应由已知不兼容 ID 回调立即拒绝错误类型，并在实际 Release task/variant 接线前验证 Application 初始化已发生；或者把“先应用 `com.android.application`，再应用 Kaleido”写成公开前置条件并在 Kaleido `apply()` 时立即验证。不要为了补这个缺口使用 `afterEvaluate`、`taskGraph.whenReady` 或 build-wide listener；Configuration Cache 会跳过部分 configuration callbacks，而 Isolated Projects 也限制从 Project 注册 build-scope lifecycle callbacks。[Configuration Cache lifecycle](https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:build_lifecycle)、[Isolated Projects project-to-build constraints](https://docs.gradle.org/current/userguide/isolated_projects.html#isolated_projects_project_to_build_access)

### 1.2 Application DSL facts

应在 AGP `finalizeDsl` 生命周期内读取最终 DSL，而不是保存 mutable extension 供任务执行。Android 官方说明 `finalizeDsl` 在 DSL 解析后、variant 初始化前执行，回调结束后不应继续持有或修改这些对象。[Write Gradle plugins: Variant API lifecycle](https://developer.android.com/build/extend-agp#variant-api-artifacts-tasks)

AGP 9.2.1 公开表面如下：

- `ApplicationExtension.dynamicFeatures: MutableSet<String>`：base app 声明的 dynamic-feature project paths。
- `ApplicationExtension.assetPacks: MutableSet<String>`：bundle 包含的 asset-pack project paths。
- `ApplicationExtension.buildTypes` 与 `productFlavors`：声明层的全部容器。
- `ApplicationExtension.buildFeatures.compose: Boolean?`：Compose DSL 开关；`null` 回落到默认 `false`，因此 Compose Generator 的先决条件应按 `compose == true` 判断，而不是“未明确 false”。[BuildFeatures](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/dsl/BuildFeatures)

这里没有公开字段能宣称“该 build 永远不会产生任何其他 module type”。例如 ML/AI 与 runtime-enabled SDK module 仍需在最终 AAB 检查。`dynamicFeatures.isEmpty()` 与 `assetPacks.isEmpty()` 是强配置证据，但不是最终产物证据。

### 1.3 Variant facts

`ApplicationVariant` 继承 `ComponentIdentity` 与 `CanMinifyCode`：

- `name`、`buildType`、按 dimension 排序的 `productFlavors` 描述实际生成的 variant，而不是仅列 DSL 声明。[ComponentIdentity](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/ComponentIdentity)
- `isMinifyEnabled` 在 `onVariants` 时已经是最终值；AGP 文档明确说如果要改变它只能更早在 `beforeVariants` 操作 builder。Kaleido 因此可在实际 Release variant 上可靠 fail closed，而不应只读取某个同名 DSL build type。[`CanMinifyCode`](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/CanMinifyCode)
- Release variant 选择可基于 `buildType == "release"` 与实际 variant identity；product flavors 不妨碍支持，但每个实际 Release flavor variant 必须分别成为声明输入、分别产出 Evidence Set。

Compose 没有对应的 `ApplicationVariant.composeEnabled` 公共属性；可用证据仍是 `finalizeDsl` 时的 `buildFeatures.compose == true`。这属于 module-level DSL 事实，不是 per-variant API 事实。

### 1.4 Ordinary local/external dependencies

每个 `Component` 公开 `compileConfiguration` 与 `runtimeConfiguration`，并明确要求不要在 configuration time resolve；应把所需文件/metadata 惰性接入验证 task，在 execution time 解析。[AGP `Component`](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/Component)

Gradle 的 `ResolutionResult` 能列出 resolved component graph；Artifact View 可用 `ProjectComponentIdentifier` 与 `ModuleComponentIdentifier` 区分 project dependency 和 external module dependency。Gradle 还承认第三类 file dependency，它没有 module metadata。[ResolutionResult](https://docs.gradle.org/current/kotlin-dsl/gradle/org.gradle.api.artifacts.result/-resolution-result/index.html)、[Artifact Views component filtering](https://docs.gradle.org/current/userguide/artifact_views.html#sec:component_filtering)、[Gradle dependency types](https://docs.gradle.org/current/userguide/declaring_dependencies.html#sec:dependency-types)

由此能得出的事实是“本 variant 实际解析了哪些 component/artifact”。不能由此得出：

- producer Project 一定是 Android library；访问另一个 Project 的 `plugins`/`extensions` 会触碰跨项目 mutable state，Isolated Projects 不允许。
- external coordinate/file 一定不含动态加载、反射、JNI、embedded DEX/APK 或 hotfix 逻辑。
- dependency graph 中未出现某个知名 coordinate 就等于没有相同代码；dependency substitution、shading、fork 与 file dependency 都会破坏这种等价。

因此“ordinary dependencies”更可执行的定义是：**无论 project、external module 或已声明的 local artifact，只要它们正常解析并被 AGP 合入目标 Application 的 base module，且没有触发明确的 unsupported-runtime policy，就属于依赖；Kaleido 不拥有其类名，只做引用/keep 分析。** 这句话是 Kaleido inference，不是 AGP 自动提供的安全分类。

## 2. Artifact-time facts

bundletool `AppBundle.buildFromZip` 读取 `BundleConfig.pb`、枚举并解析所有 module；公开模型将 module 分为：

- `FEATURE_MODULE`：包括名为 `base` 的 base module 与普通 dynamic feature；
- `ASSET_MODULE`；
- `ML_MODULE` 与 `SDK_DEPENDENCY_MODULE`：是独立 enum 值，但其 `isFeatureModule()` 同样返回 `true`，所以也会出现在 `getFeatureModules()` 视图；
- `UNKNOWN_MODULE_TYPE`（不应接受）。

`AppBundle.getModules()` 给出完整 map，`hasBaseModule()` 检查 `base`，`getFeatureModules()` 与 `getAssetModules()` 提供分类视图。[BundleModule types](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/java/com/android/tools/build/bundletool/model/BundleModule.java#L105-L128)、[AppBundle module views](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/java/com/android/tools/build/bundletool/model/AppBundle.java#L142-L186)

`BundleConfig.BundleType` 还区分 `REGULAR`、`APEX`、`ASSET_ONLY`。[bundletool config proto](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/proto/config.proto#L18-L24)

对当前 MVP，“supported final topology”的最强自动证明是：

```text
BundleType == REGULAR
modules.keys == { "base" }
modules["base"].moduleType == FEATURE_MODULE
getAssetModules().isEmpty()
no ML_MODULE / SDK_DEPENDENCY_MODULE / UNKNOWN_MODULE_TYPE
bundletool validate passes
```

普通 AAR/JAR/project libraries 的代码与资源合入 `base`，不作为独立 AAB module；所以 base-only AAB 与“允许普通 library dependencies”并不冲突。反过来，base-only 只证明发布 artifact topology，不证明整个 Gradle build 没有其他不相关 Application project，也不证明 base 内的依赖行为没有动态机制。

配置期和产物期必须互相校验：若 `dynamicFeatures`/`assetPacks` 非空，尽早拒绝；即使二者为空，只要最终 module map 不是严格 base-only，仍应失败。这样能覆盖其他 AGP/plugin 路径生成的 ML/SDK/未知 module。

## 3. Heuristic runtime-dynamic signals

下列检查可以作为诊断或显式 policy 的输入，但只能称为 **signals**：

| Signal layer | 可检测示例 | 不能证明的原因 |
|---|---|---|
| Resolved dependencies/plugins | 已知 hotfix/plugin framework coordinates、Gradle plugin IDs | fork、shading、dependency substitution、file dependency、私有实现 |
| Managed bytecode | `DexClassLoader`、`InMemoryDexClassLoader`、custom `ClassLoader`、`ClassLoader.loadClass`、`Class.forName`、`SplitInstallManager`、`System.load`/`loadLibrary` references | 反射、拼接/加密字符串、生成代码、native 间接调用、dead code 与实际执行路径不可判定 |
| Manifest/resources/assets | `SplitCompatApplication`、proxy/stub components、embedded `.dex`/`.jar`/`.apk`、patch metadata | 可改名/加密/下载，且相同 marker 可能用于合法非动态用途 |
| Native artifact | `lib/**/*.so`、导入或字符串中的 `dlopen`/`android_dlopen_ext` | native code 可自行解密/下载/映射内容；符号可 strip，字符串可构造 |
| Final AAB modules | dynamic feature、asset、ML、SDK dependency modules | 只能看到本次发布 AAB；看不到运行时后下载的任意 code/data 或外部 APK |

这些机制为什么不能被“缺少 marker”否定：

- Android 的 `DexClassLoader` 明确可执行并非随 application 安装的 `.jar`/`.apk` 中 DEX；`InMemoryDexClassLoader` 可直接从 buffer 加载 DEX。[DexClassLoader](https://developer.android.com/reference/dalvik/system/DexClassLoader)、[ClassLoader subclasses](https://developer.android.com/reference/java/lang/ClassLoader)
- `ClassLoader` 可被继承，并可从文件、网络或程序生成的 bytes 定义 class；`Class.forName(String)` 与 `loadClass(String)` 的名字本身可在运行时产生。[ClassLoader](https://developer.android.com/reference/java/lang/ClassLoader)、[`Class.forName`](https://developer.android.com/reference/java/lang/Class#forName(java.lang.String))
- Play Feature Delivery 的 `SplitInstallManager` 在运行时按 module name 请求 on-demand module；即使最终 AAB 没有 dynamic feature，base bytecode 仍可能含无效、条件式或第三方封装的调用，单个 call site 不能反推出当前 bundle topology。[On-demand feature delivery](https://developer.android.com/guide/playcore/feature-delivery/on-demand)
- Java/JNI 可用 `System.loadLibrary` 加载 native library，native code 可再调用 `dlopen`/`android_dlopen_ext`；managed bytecode 扫描无法证明 `.so` 内没有进一步动态行为。[Android JNI tips](https://developer.android.com/ndk/guides/jni-tips)、[Android dynamic linker](https://developer.android.com/ndk/reference/group/libdl)
- 真实框架也展示了这类边界：Tinker 支持不重装 APK 更新 DEX、native library 和 resources；RePlugin/VirtualAPK 支持 class-loader 驱动的 external downloaded plugin APK。即使列出它们全部公开 coordinate，也不能覆盖重命名、fork 和私有同类实现。[Tencent Tinker](https://github.com/Tencent/tinker)、[Qihoo360 RePlugin](https://github.com/Qihoo360/RePlugin)、[Didi VirtualAPK](https://github.com/didi/VirtualAPK)

## 4. Kaleido inferences for the ticket

以下是由上述事实推导出的可讨论方案，不是一手 API 的强制答案：

1. **Topology 定义按 publishable variant，而不是整个 Gradle build。** 允许 Consumer build 中存在任意普通 library subprojects，甚至不相关的其他 app project；只要 Kaleido 应用到的 current Project 是 Application，目标 Release AAB 严格 base-only。这样与 Isolated Projects、composite build 和普通依赖兼容。
2. **三层 fail-closed gate：**
   - plugin initialization：wrong current-project plugin type 立即失败；Application callback 内建立 typed AGP API 接线。
   - `finalizeDsl` / `onVariants`：dynamicFeatures/assetPacks 非空、Compose Generator 已启用但 `compose != true`、目标 Release variant `isMinifyEnabled == false` 时失败。
   - final candidate：不是 Regular/base-only 或出现 asset/ML/SDK/unknown module 时失败，即使配置期未发现。
3. **依赖图不做“安全认证”。** 记录 resolved origin/component/artifact 到 Artifact Report，可对已知高风险框架给诊断；但不要把 Maven coordinate allowlist 当安全边界，也不要跨 Project 读取 producer plugin 类型。
4. **运行时动态机制需要产品 policy，而非伪证明。** 可选边界包括：发现强 signal 时 fail closed；要求 Consumer 显式声明 keep/escape hatch 与接受风险；或将一部分机制列为 MVP unsupported。无论选择哪种，Artifact Report 应写“扫描范围与发现的 signals”，不能写“已证明不存在动态加载”。
5. **native library presence 不应自动等同 unsupported。** 许多普通 SDK 含 `.so`，pipeline 也已承诺保持其 bytes/path 与 code-transparency；真正需要决策的是 JNI name coupling、动态 class/resource lookup 与 external code loading 的 keep/escape-hatch 行为。

## Remaining unknowns / implementation spikes

- `com.android.application` 缺失但 Kaleido 先应用时，首版究竟选择“要求插件顺序”还是“延后到 Release invocation 验证”，属于 adoption-interface decision；公开 API 没有一个 `withPlugin` 的“永远不会再应用”回调。
- AGP 9.0、9.1 与 9.2.1 对 `ApplicationExtension`/`ApplicationVariant` 的兼容范围应由 version matrix/TestKit 证明；本报告只直接核对 9.2.1。
- bundletool model classes是否直接作为生产 library API 使用、还是由 Kaleido 自己解析固定 protobuf/manifest topology，需要 implementation spike；无论实现手段，验收事实仍是 Regular/base-only。
- runtime signal scanner 的 classes、coordinates、manifest/native markers 与 severity 要在 keep/escape-hatch ticket 中决策，并用 positive/negative fixtures 控制误报；它永远不是完备证明。
