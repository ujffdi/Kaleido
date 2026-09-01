# Keep and escape-hatch evidence for Kaleido

研究日期：2026-08-31

## Scope

本报告为 `Define the keep and escape-hatch model` 提供一手技术证据，不替 ticket 作最终产品决策，也不修改 Kaleido 实现。问题限定为：Kaleido 在 Application-module class/Manifest/XML transform、R8、generated-code 与 final-AAB resource transform 之间，哪些引用可自动建立完整保护，哪些必须由 Consumer Project 声明，以及何时不能把静态扫描结果当成完整性证明。

资料只采用 Android Developers、Android/AndroidX source、R8 source documentation、Gradle/Kotlin 官方文档与 JNI specification。下文明确区分 **官方事实** 与 **Kaleido policy inference**。

## Decision-ready summary

1. **一个 `keep` 布尔值不足以表达风险。** R8 本身把 shrink/reachability、rename、optimization、descriptor types 与 attributes 分开；Kaleido 还额外拥有 pre-R8 class rename 和 final-AAB resource name/path rewrite。因此 escape hatch 至少要分别表达：保留可达性、保留原 class/member name、保留 descriptor 中的类型、保留必要 attributes、保留 resource entry name、保留 packaged path。
2. **自动处理只适用于可解析且闭合的引用边。** 直接 bytecode reference、Manifest/XML 中精确 class reference、明确 annotation/type/成员模式、已合并的 app/library R8 rules，以及精确可解析的 resource reference 可以自动进入 protection set。运行时字符串、native lookup、配置/网络生成名称和开放式 reflection 不能由“未发现 signal”否定。
3. **`RegisterNatives` 不是 JNI rename 的通用豁免。** 它避免依赖导出的 `Java_...` symbol 名，但注册表仍包含 managed method name/signature，通常还要用 `FindClass` 定位 managed class；native upcall 也仍需要精确 keep。
4. **`tools:keep` 与 `<public>` 不是 final-AAB resource-name contract。** `tools:keep` 是 resource shrinker 的“不要删除”指令；`<public>` 是 library resource API visibility。官方资料均未承诺它们防止另一个工具改 entry name 或 packaged path。Kaleido 若要兼容 `getIdentifier()` 和 SDK string lookup，必须显式把这些输入翻译成自己的 name/path protection policy。
5. **codegen 降低反射风险，但 processor/framework 身份不是安全证明。** KSP/annotation processor 的最终生成代码会参与编译，可在 compiled-input stage 分析；但 KSP 自身看不到表达式/语句，生成代码仍可能使用 reflection、naming convention、JNI 或 resources。AAR/JAR consumer rules 应作为有效 R8 输入保留并报告，但“规则存在”不证明规则正确或覆盖完整。
6. **推荐的 fail-closed 边界不是“看到任何 reflection/JNI/.so 就失败”。** native library、reflection API 或 processor 本身只是 signal。只有当 Kaleido 将要改变一个已确认的 name/path coupling，而目标集合不能闭合、也没有匹配的显式声明时，才应失败。对未触发强 signal 的构建只能报告“在声明的扫描面未发现”，不能报告“已证明不存在”。

## 1. R8, reflection, keep rules, and attributes

### 1.1 Official facts

- R8 能识别并保留 direct calls，但看不到 reflection/JNI 的间接调用。Android 官方例子明确指出，按运行时字符串调用 `Class.forName(className)` 时，R8 可能删除目标 class；所有 reflection 都需要对应的 keep，library 应把规则放入 consumer keep rules。[About keep rules](https://developer.android.com/topic/performance/app-optimization/keep-rules-overview)、[reflection examples](https://developer.android.com/topic/performance/app-optimization/keep-rule-examples#reflection)
- R8 protection 不是单一语义：`-keep`、`-keepclassmembers`、`-keepclasseswithmembers` 等对 class/member 的 shrinking 与 obfuscation 有不同作用；`allowoptimization`、`allowobfuscation`、`allowshrinking`、`includedescriptorclasses` 等 modifier 又分别放开不同操作。官方特别提醒裸 `-keep` 会阻止匹配项的一切优化，而 `-keepnames` 只防 rename、不防 removal。[Add keep rules](https://developer.android.com/topic/performance/app-optimization/add-keep-rules)
- 反射可能依赖 class-file attributes，而不仅是 class/member 存在。Android 官方列出的常见对应关系包括：`getEnclosingMethod()`/`getDeclaredClasses()` 需要 `EnclosingMethod` 和 `InnerClasses`；generic reflection 需要 `Signature`；runtime annotation lookup 需要 `RuntimeVisibleAnnotations`。应只保留真正需要的 attributes。[Global options: keep attributes](https://developer.android.com/topic/performance/app-optimization/global-options#keep-attributes)
- R8 full mode 只为被显式 keep 匹配的 item 保留相关 attributes；只有 `-keepattributes Signature` 而没有匹配 holder/item，不能保证 generic metadata 存活。[Use R8 in full mode](https://developer.android.com/topic/performance/app-optimization/full-mode)
- 对 runtime annotation 驱动的框架，官方示例同时保留 `RuntimeVisibleAnnotations`、annotation class 及匹配的 annotated member；三者不是可互换的单个开关。[Reflection based on method annotations](https://developer.android.com/topic/performance/app-optimization/keep-rule-examples#reflection-annotations)
- library consumer rules 会随 AAR/JAR 进入 consuming app 的 R8 配置。Android 官方要求 library 把其 reflection/JNI 所需规则打包进去，并反对 package-wide keep；若 full-mode 下失败，应定位具体入口而非保留整个 package。[Optimization for library authors](https://developer.android.com/topic/performance/app-optimization/library-optimization)
- R8 keep-annotation 设计也把 name、lookup、normal use、generic signature 和 annotation constraints 分开，并把 `@UsedByReflection` 与 `@UsedByNative` 作为等价语义入口。它可作为 protection vocabulary 的一手参考，但并不要求 Kaleido 把 R8 annotation 类型暴露成自己的公共 API。[R8 keep-annotation guide](https://r8.googlesource.com/r8/+/refs/heads/main/doc/keepanno-guide.md)

### 1.2 Kaleido policy inference

Kaleido 的 protection model 不应直接等同于一段任意 ProGuard 文本。它至少需要下列可审计维度，并在 Artifact Report 中写出每一项的来源和最终解析目标：

| Protection dimension | 解决的问题 | 对 Kaleido pipeline 的含义 |
|---|---|---|
| Reachability | R8 不删除 runtime-only class/member | 生成精确 R8 retention rule |
| Original class/member name | reflection/JNI/string contract 依赖旧名 | 同时排除 Kaleido pre-R8 rename，并让 R8 不 obfuscate 对应 name |
| Descriptor closure | native/reflection signature 中的参数、返回值、field type | 把 descriptor types 纳入 name/reachability closure，而不是只 keep bridge member |
| Required attributes | runtime annotation、generic、inner/enclosing metadata | 生成最小 `-keepattributes` 并证明 holder/item 也被匹配 |
| Optimization freedom | 可保留 name 但仍允许 shrink/optimize 的目标 | 不默认退化成裸 `-keep` |

自动识别可以覆盖 exact class literal、exact constant member name、runtime annotation + 明确 target pattern 等可闭合情形；开放式 `Class.forName(input)`、`getDeclaredMethod(input)`、从配置或网络取得的名字必须要求 Consumer declaration。显式规则若解析为零个目标、pattern 过宽、或只声明 attributes 而未保护 holder，应产生诊断而非静默视为安全。

## 2. JNI name discovery and `RegisterNatives`

### 2.1 Official facts

- Android 默认 `proguard-android-optimize.txt` 包含 `-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }`，用于保护保留到最终程序中的 Java/Kotlin-to-native declaration 的 class/member name 及 descriptor types。按 R8 keep-option 语义，这条条件规则本身不为 otherwise-unreachable declaration 建立 reachability；通常由 managed direct call 提供 reachability。native 到 Java/Kotlin 的 upcall 对 R8 不可见，需要针对被调用 member 和其 descriptor types 的精确规则。[JNI keep-rule examples](https://developer.android.com/topic/performance/app-optimization/keep-rule-examples#jni)、[keep-option semantics](https://developer.android.com/topic/performance/app-optimization/add-keep-rules#keep-option)
- 传统 name-based JNI discovery 按 JNI specification 从 declaring class binary name、method name，以及重载时的 parameter descriptor 构造 `Java_...` symbol，并按 short/long name 查询。因此 managed class/method/signature 改变会破坏对应 native symbol lookup。[JNI specification: resolving native method names](https://docs.oracle.com/en/java/javase/25/docs/specs/jni/design.html#resolving-native-method-names)
- `RegisterNatives` 接收一个 `jclass` 以及 `JNINativeMethod[]`；每个 entry 仍包含 managed method `name`、`signature` 和 native function pointer。目标 native method 找不到时会抛 `NoSuchMethodError`。[JNI specification: RegisterNatives](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html#registernatives)
- Android NDK 推荐多数 app 在 `JNI_OnLoad` 中显式 `RegisterNatives`，这样通常只需导出 `JNI_OnLoad` 且错误在 library load 时较早暴露。但官方同时说明，启用 shrinking 时，只有 JNI 使用的 class/method/field 仍必须配置 keep；`FindClass` 的 class name 和 method signatures 仍是 native-side knowledge。[Android JNI tips](https://developer.android.com/ndk/guides/jni-tips)

### 2.2 Kaleido policy inference

| JNI pattern | 可自动做什么 | 仍需声明/验证什么 |
|---|---|---|
| Managed `native` declaration + name-based native symbol | 识别 declaration；套用/验证默认 downcall rule；从可见 symbol 推导候选 coupling | stripped/hidden/encrypted native symbol、runtime-loaded library 和重载 closure 不能靠 absence 证明 |
| `RegisterNatives` table with literal names/signatures | 若 `.so` 可解析，可记录 candidate member/signature；managed declaration 可交叉验证 | registration class（常由 `FindClass` string 指定）、native upcalls、运行时构造/加密 table 仍需 declaration |
| Native `FindClass`/`GetMethodID`/`GetFieldID` literal | 可把 exact class/member/descriptor 加入 original-name closure | 非 literal/native computation 无法闭合 |
| `.so` merely present | 只记录 native dependency/origin | 不能自动视为 incompatible，也不能视为 JNI-safe |

`RegisterNatives` 只降低 exported C symbol 对 Java binary name 的耦合，不消除 Java class lookup、registered method name/signature 或 native upcall 的耦合。若 Kaleido 将要 rename 一个已确认由 native string 指向的 Application class/member，而不能得到闭合目标集，也没有 Consumer declaration，应 fail；但仅发现 `.so` 或 `System.loadLibrary` 不足以 fail。

## 3. Resource shrinking, runtime lookup, public resources, and SDKs

### 3.1 Official facts

- Android resource shrinker 的 safe mode 会保留显式引用以及“可能由 `Resources.getIdentifier()` 动态引用”的资源；strict mode 只保留 code/resource 中的显式引用。`tools:keep` 用于声明 runtime indirect lookup 需要保留的资源，`tools:discard` 用于明确删除工具误判为 reachable 的资源。[Tools attributes: resource shrinking](https://developer.android.com/studio/write/tool-attributes#resource_shrinking_attributes)
- keep file 位于 `res/raw/*.keep.xml`，规则具有 global scope，library 应使用唯一文件名避免合并冲突；keep file 本身不会被打包进 app。官方把 `getIdentifier()` 明确列为少数需要手工 resource keep 的例外。[Customize which resources to keep](https://developer.android.com/topic/performance/app-optimization/customize-which-resources-to-keep)
- resource shrinker 的诊断 `resources.txt` 会记录 reachable/removed 关系。在非 strict 检查下，若 string constant 像动态 resource-name format，shrinker 可能把匹配资源标为 reachable；这说明它能处理部分常量格式，但不是任意 runtime string 的完备证明。[Troubleshoot resource shrinking](https://developer.android.com/topic/performance/app-optimization/customize-which-resources-to-keep#troubleshoot)
- Android library 的 `<public>`/`public.xml` 定义哪些 library resources 对 consumer 是公开 API；AGP 将其抽取为 AAR 的 `public.txt`。官方描述的是 code completion、lint 与 library API visibility，并未把它定义为 resource-shrinker keep 或 final packaged-name guarantee。[Declare a public resource](https://developer.android.com/studio/projects/android-library#PrivateResources)
- AAR 可以携带 consumer ProGuard configuration，AGP 会把它追加到 consuming app 的代码优化配置；这是 class/member R8 规则通道。[Library ProGuard configuration](https://developer.android.com/studio/projects/android-library#Considerations)

### 3.2 What those facts do **not** establish

- `tools:keep` 的官方语义是“不要由 resource shrinker 删除”，不是“禁止第三方 final-AAB transformer 改 resource entry name/path”。
- `tools:discard` 是 Consumer 对 shrink/removal 的显式意图，不是授权 Kaleido 对仍存在的其他资源任意 rename。
- `<public>` 表示 library resource API visibility；它不证明 runtime code 会按字符串查询该名字，也不证明名字可安全改变。
- `consumerProguardFiles` 保护 managed code；官方没有把它定义成 resource-name/path obfuscator 的 consumer-rule 格式。

### 3.3 Kaleido policy inference

Kaleido 应把 resource **existence、entry name 和 packaged path** 分开：

| Input/signal | MVP conservative handling | Reason |
|---|---|---|
| Compiled `R.type.name` / binary XML resource reference | 允许 entry/path transform，只要 resource table/XML/DEX ID references 同步且验收通过 | 引用以编译后的 ID/table 边闭合，不依赖原字符串名 |
| `tools:keep` / exact `getIdentifier()` literal | 自动保留存在性与原 entry name；若 file path 也由字符串直接访问，再保留 path | 只保留 resource 但改变 lookup name 仍会破坏 runtime contract |
| format/prefix/suffix-generated resource names | 要求显式 pattern declaration；解析成确定 resource set 后冻结 entry names | safe-mode string matching只是启发式，不是完整 lookup proof |
| `<public>`/AAR `public.txt` | 至少记录 provenance；MVP 可保守冻结 public entry names | public 本身不是 runtime proof，但冻结 name 是可审计的兼容选择 |
| dependency/AAR-owned resources | MVP 默认不改其 entry names/paths，除非未来有单独 opt-in + SDK fixture | SDK 可能在 dependency/native code 内按字符串引用，而 Kaleido 不重写 dependency code |
| `assets/`、raw path、`AssetManager.open(string)` | 需要单独 path protection；不能用 resource-entry keep 代替 | 运行时 contract 是 packaged path，不是 `R` entry name |

“默认保留 dependency-owned resource names/paths”是保守的 Kaleido 产品选择，不是 Android 强制要求；它会减少 resource obfuscation 覆盖面，但与当前“dependencies analysis-only、不重写 dependency code”的 ownership 边界一致。若未来选择 transform third-party resources，应以每个 SDK 的 minified release fixture、动态 lookup 证据和显式 opt-in 替代当前默认值。

## 4. Annotation processors, KSP, generated frameworks, and consumer rules

### 4.1 Official facts

- KSP 是 source-generation framework：processor 读取 symbols/resources、生成 code/output，随后 Kotlin compiler 把 source 与 generated code 一起编译。KSP 只能看到 symbol-level declarations/types，不能检查 expressions 或 statements，也不能修改 source。[KSP overview](https://kotlinlang.org/docs/ksp-overview.html)
- KSP 的 incremental model 要求 processor 申报 generated output 与 input source 的关联，并区分 aggregating/isolating output；这个 dependency graph 证明增量失效关系，不描述 generated runtime behavior。[KSP incremental processing](https://kotlinlang.org/docs/ksp-incremental.html)
- AGP/Gradle 为 `ksp`、`kapt`、`annotationProcessor` 提供独立 processor configurations；传统 annotation-processor JAR 可由 `META-INF/services/javax.annotation.processing.Processor` 标记识别。compile-time annotation dependency 还可能只存在于 `compileOnly`，不进入 runtime artifact。[Add build dependencies](https://developer.android.com/build/dependencies#dependency_configurations)
- Android 官方建议优先 codegen，因为编译后的直接代码更容易让 R8 确定 reachability；Room、Hilt 等是这类模式。但官方并没有把“使用 codegen”定义成无需 keep 的保证，library 仍须为实际 reflection/JNI 入口提供 targeted consumer rules。[Optimization for library authors](https://developer.android.com/topic/performance/app-optimization/library-optimization)
- Hilt 的官方文档说明 `@HiltAndroidApp`/`@AndroidEntryPoint` 等会在 build time 生成 container/components，并由 Dagger 验证和生成 runtime object graph。这证明其标准路径是 generated compiled graph，而不是让 Kaleido 按 annotation 名猜测 runtime target。[Hilt generated components](https://developer.android.com/training/dependency-injection/hilt-android)
- AAR/JAR consumer rules在 app optimization 时自动合并。Android 官方仍要求通过 minified integration app 与 R8 Configuration Analyzer 验证规则质量，说明“规则存在”不等于“规则精确/完整”。[Validate consumer keep rule quality](https://developer.android.com/topic/performance/app-optimization/library-optimization#validate-consumer-rules)

### 4.2 Kaleido policy inference

1. **扫描最终 compiled inputs，而不是只扫 source annotations 或 processor identity。** Kaleido class transform 应在 KAPT/KSP/generated sources 已编译并进入 Application scoped classes 后分析真实 class/reference graph；processor coordinate 和 generated directory 只作为 provenance。
2. **标准 codegen direct edge 可自动信任为 reachability edge。** 若 generated class 通过普通 bytecode type/call reference 指向 Application class，rename 应按统一 bytecode transform 同步；无需额外 framework-specific name keep。
3. **naming convention/reflection fallback 仍需规则。** 若 generated/runtime code 拼接如 `Foo_Impl`、`FooJsonAdapter` 等名字，或从 runtime annotation 扫描目标，必须由 bundled consumer rule、Kaleido-known exact adapter，或 Consumer declaration 保护；仅看到 KSP/annotation processor 不足以放行。
4. **保留所有已解析 consumer rules，并报告 origin。** R8 rules 是 additive；Kaleido 不应静默过滤/覆盖 library rules，也不能承诺它们会保护 Kaleido 自己更早的 class rename。需要把“R8 rule protection”和“Kaleido transform exclusion”分别计算，再在 report 中展示交集/缺口。
5. **不要硬编码无限 framework allowlist。** 版本变化、fork、shading、processor/runtime artifact 分离会让 coordinate/annotation-name allowlist 失效。首版可对已验证 fixture 提供自动 adapter，但 generic fallback 仍是声明或 fail-closed。

## 5. What static scanning cannot prove

静态分析可证明某个输入 artifact 中存在具体 reference/signal；通常不能证明全程序在所有 runtime inputs 下不存在动态边。至少包括：

- class/member/resource/library/path 名称来自 network、remote config、database、encrypted/compressed blob、reflection result、locale-sensitive computation 或 user input；
- `ClassLoader`、`Class.forName`、`getDeclaredMethod`、`Resources.getIdentifier`、`AssetManager.open` 等 API 的参数跨方法/跨语言计算，超出声明的数据流边界；
- native `.so` stripped symbols、computed/encrypted strings、`dlopen` 后加载的第二层 library，以及 JNI table 在 runtime 构造；
- dependency 被 shading/relocation/fork 或以 file dependency 引入，导致 coordinate/known-framework marker 缺失；
- processor/plugin 生成的 runtime behavior 与其公开 annotation/coordinate 不一致；KSP processor 本身甚至不能观察 source expressions/statements；
- dead code、feature flag、server path 与实际设备执行路径：发现 signal 不代表一定执行，未发现已知 signal 也不代表没有等价实现。

因此 Artifact Report 应使用可证实措辞，例如：

> Scanned the declared Application compiled classes, merged manifests/XML/resources, resolved dependency artifacts, consumer rules, and packaged native libraries for the listed signal set. No matching signal was found.

不应写：

> The application does not use reflection/JNI/dynamic resource lookup.

## 6. Suggested decision matrix for Ticket 16

以下是由一手事实推导出的建议，不是 Android/R8 的强制 API：

| Situation | Automatic | Consumer declaration | Release result |
|---|---|---|---|
| Direct bytecode/Manifest/XML/reference graph fully closes | 生成同步 transform/R8/resource protection | 无 | 继续；报告自动边与目标 |
| Exact reflection/JNI/resource literal resolves to finite targets | 自动生成精确 protection set | 可选收窄/补充 | 继续；原名/path 按实际 coupling 冻结 |
| Bundled app/AAR/JAR consumer rule resolves to concrete items | R8 侧保留并记录 origin | 若同一 target 还受 Kaleido pre-R8 rename，需转换成对应 Kaleido name exclusion | protection 两侧均闭合才继续 |
| Runtime annotation/generic reflection | 识别 annotation/attributes signal | 声明 target pattern、member kind、required attributes | 解析非空且闭合后继续 |
| Open-ended runtime string/native lookup/dynamic resource name | 只报告 callsite/origin，不能猜目标 | 必须给 class/member/resource/path pattern 与理由 | 缺失、零匹配或冲突则 fail |
| Dependency contains `.so`/reflection but no confirmed Application target | 记录 signal；dependency class/resources 保守不改名 | 不因 signal 本身强制声明 | 继续并报告“不构成 absence proof” |
| Consumer asks for package-wide/broad wildcard | 可解析并估算影响 | 明确理由与 scope | 允许作临时 escape hatch，但 warning + Artifact Report；是否 strict-mode fail 属产品决策 |
| `tools:discard` conflicts with Kaleido/name keep or required reference | 显示规则来源与冲突 | Consumer 必须消除冲突 | fail，不由 Kaleido静默决定优先级 |
| Declarative keep matches zero items or只保留 attribute 无 holder | 诊断无效声明 | 修正规则 | fail 或至少 strict error；不能当成成功证据 |

### Recommended principles

- **Protection requirements take the union.** App rules、library consumer rules、AAPT/default rules、Kaleido auto inference 与 explicit declarations 都是 additive protection inputs；Kaleido 不宣称撤销现有规则。
- **Transformation domains remain explicit.** `R8 keep`、Kaleido class rename exclusion、resource entry-name keep、packaged-path keep 是不同域；同名 `keep` 不应暗中跨域。
- **Reason and provenance are required.** 每个 manual declaration 应包含 reason，并在 Artifact Report 记录 source file/DSL location、resolved target count、保护维度与是否为 broad wildcard。
- **No generic force override for broken integrity.** Escape hatch 表达“这些对象必须保留什么性质”，而不是允许用户跳过 unresolved reference-integrity failure 后继续发布。

## 7. Acceptance fixtures implied by the evidence

后续 implementation spike/集成测试至少应覆盖：

1. `Class.forName` exact literal、runtime input、class literal + reflective constructor、annotation-driven member reflection，以及 attributes/holder 不配对的 negative fixture。
2. JNI name discovery、`RegisterNatives`、native upcall、descriptor custom type、stripped `.so`/无法解析 table，以及“只有普通 `.so` 不失败”的 negative-control fixture。
3. `getIdentifier` exact name、format pattern、`tools:keep`、`tools:discard` conflict、AAR `public.txt`、dependency-owned resource 与 `AssetManager.open(path)`。
4. KAPT/KSP isolating/aggregating generated class、direct-reference codegen、naming-convention reflection fallback、AAR/JAR consumer rules 与 zero-match/broad-rule diagnostics。
5. 对每种 keep 验证 raw Kaleido mapping、raw R8 mapping、composed mapping、resource mapping 与 Artifact Report 一致；特别验证“保留原名”不会被 pipeline 后续 stage 再次改名。

这些 fixture 能证明已声明场景在 minified Release AAB 中成立；它们仍不能把有限 scanner 变成对所有 runtime 动态行为的完备证明。
