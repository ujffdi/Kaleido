# Kaleido Sample 双 AAB 验证报告

验证日期：2026-09-02<br>
验证环境：macOS arm64、JDK 17、Gradle 9.4.1、Android Build Tools 36.0.0<br>
插件版本：本仓库当前源码发布到隔离 Functional Test Maven 仓库的 `0.1.0-dev`

## 最终结论

| AAB | 定位 | 结论 |
| --- | --- | --- |
| [`baseline-without-plugin.aab`](https://github.com/ujffdi/Kaleido/releases/download/sample-aab-validation-2026-09-02/baseline-without-plugin.aab) | 与插件包共享同一份 Sample `src/main`，未应用 Kaleido | **有效对照：插件未运行** |
| [`plugin-enabled.aab`](https://github.com/ujffdi/Kaleido/releases/download/sample-aab-validation-2026-09-02/plugin-enabled.aab) | 应用 Kaleido `FULL` 配置 | **有效产物：Kaleido 已运行且核心能力生效** |

插件有效的依据不是 AAB 体积或模糊字符串搜索，而是每个功能点均形成了“baseline 最终状态 → Kaleido 转换计划/映射 → 插件 AAB 最终状态”的证据链。最终 Manifest、资源表、编译 XML、全部 DEX、ZIP 条目、raw/composed mapping、签名回执、bundletool APK Set 和 Dexcount 结果互相一致。

> 证明边界：这是静态产物与可控构建验证。未安装或启动 APK，因此不声称设备运行、全部运行时路径、所有设备、Google Play 审核或商店接受性已经验证。

## 如何阅读证据

每张证据卡固定回答五个问题：

1. **功能目标**：Kaleido 应改变或保护什么。
2. **Baseline**：未应用插件的最终 AAB 中是什么状态。
3. **转换证据**：Kaleido 的计划、mapping 或回执记录了什么。
4. **最终产物**：插件 AAB 的 Manifest、资源、XML、DEX 或 ZIP 实际是什么。
5. **判定**：证据是否闭环，以及不能据此推断什么。

证据源均可从本报告链接的[在线证据目录](evidence/)和两个 AAB 复查。

## 产物身份

| 产物 | SHA-256 | 精确字节数 | 状态 |
| --- | --- | ---: | --- |
| baseline-without-plugin.aab | `77a2dd9311ed17a886dce489fb41a4899b428fdc8c8a82fd2ef4ca6837087c09` | 1,886,380 | bundletool 结构有效；未签名对照 |
| plugin-enabled.aab | `935fd1e9fae270af17a90ba23a76bc1cc867a62e0561da7f3583fbb1d9c4c8c8` | 3,431,428 | bundletool 结构有效；Kaleido 签名有效 |
| baseline.apks | `17107ba4c7b6e3421ed3260211246af5a994e0c1ba76fd747f520a824974d9dc` | 1,395,018 | 固定设备规格生成成功 |
| plugin-enabled.apks | `6314fe23eb2365a8c342a78a5b38fa060e3930eb5fdf4e969c03b63eaff22b9c` | 4,805,619 | 固定设备规格生成成功 |

固定设备规格为 `pixel-api-36-arm64.json`。baseline 按普通 Android release 默认行为保持未签名；插件 AAB 由 Kaleido 完成发布签名。

## 核心能力总览

| 核心能力 | 关键数据 | 判定 |
| --- | --- | --- |
| 代码与资源生成 | 1,254 个生成 Kotlin 文件；1,238 个普通/Activity 类；60,000 个普通方法；50 layout；40 drawable；50 string；38 Activity；512 Compose 函数 | **通过** |
| 类名与引用同步 | Activity、Service、Receiver、Provider、自定义 View 均完成 mapping → Manifest/XML → DEX 闭环；保护类保留原身份 | **通过** |
| 最终 AAB 资源处理 | 资源改名/改路径、重复文件归并、native/metadata 删除、未使用 string 删除、语言过滤和保护项稳定均有最终产物证据 | **通过** |
| 确定性 R8 | 三份 4,096 项字典、raw Kaleido、raw R8、composed mapping 齐全；两次未签名 AAB 与确定性证据逐字节一致 | **通过** |

Release Evidence Set 的 `stage.01` 至 `stage.10` 全部为 `PASS`，`diagnostics.count=0`，`publicationResult=PUBLISHED`。

## 逐功能证据

### 证据卡 01：普通代码、资源与 Activity 生成

**功能目标**：在 Consumer 源码之外生成受配置约束的 Kotlin、资源和 Manifest Activity，并进入最终 release 产物。

**Baseline**：没有 Kaleido `GeneratedInventory.v1`、Kaleido 生成命名空间或 38 个生成 Activity；Dexcount 在 `com.tongsr.kaleido.sample` 下只统计到 18 个方法引用。

**Kaleido 计划与生成清单**：

```properties
generation.packageCount=30
generation.classesPerPackage=40
generation.methodsPerClass=50
generation.activityCount=38
generation.layoutCount=50
generation.drawableCount=40
generation.stringCount=50

classes=1238
methods=60000
components.activities=38
```

磁盘清点得到 1,254 个 `.kt` 文件，构成为 `1,200 普通类 + 38 Activity + 16 Compose facade`；另有 50 个 layout、40 个 drawable 和 50 个 string。

**具体样例闭环**：

```text
生成名：com.tongsr.kaleido.sample.kaleido.generated.components.A_020f9b7a6a32
Kaleido 改名：...components.k14ee8c.C14ee8cbfa7
最终 Manifest：<activity android:exported="false"
  android:name="...components.k14ee8c.C14ee8cbfa7"/>
最终 DEX：新描述符存在
```

最终 Manifest 共包含全部 38 个 `exported=false` 的生成 Activity。Dexcount 将 61,750 个方法引用归入生成命名空间，见证据卡 10。

**判定：通过。** 生成内容不仅存在于中间目录，也被 Manifest、资源与 DEX 最终产物消费。

证据源：[`generated-inventory.properties`](evidence/generated-inventory.properties)、[`raw-kaleido-mapping.txt`](evidence/raw-kaleido-mapping.txt)。

### 证据卡 02：Activity / Service / Receiver / Provider 类名同步

**功能目标**：Application 自有组件改名后，Manifest 与最终 DEX 必须使用同一个新身份，不能留下会导致系统实例化失败的旧引用。

| 类型 | Baseline 最终名 | Kaleido mapping / 插件最终名 | 最终核对 |
| --- | --- | --- | --- |
| Activity | `com.tongsr.kaleido.sample.MainActivity` | `com.tongsr.kaleido.sample.k0fdd3c.C0fdd3c6f42` | Manifest 命中；DEX 新描述符 1、旧描述符 0 |
| Service | `...StaticProbeService` | `...k74f8f2.C74f8f267e8` | Manifest 命中；DEX 新 1、旧 0 |
| Receiver | `...StaticProbeReceiver` | `...kf11dba.Cf11dba09ef` | Manifest 命中；DEX 新 1、旧 0 |
| Provider | `...StaticProbeProvider` | `...kd0bf18.Cd0bf18f520` | Manifest 命中；DEX 新 1、旧 0 |

raw Kaleido mapping 的原始片段：

```text
com.tongsr.kaleido.sample.MainActivity -> com.tongsr.kaleido.sample.k0fdd3c.C0fdd3c6f42
com.tongsr.kaleido.sample.StaticProbeProvider -> com.tongsr.kaleido.sample.kd0bf18.Cd0bf18f520
com.tongsr.kaleido.sample.StaticProbeReceiver -> com.tongsr.kaleido.sample.kf11dba.Cf11dba09ef
com.tongsr.kaleido.sample.StaticProbeService -> com.tongsr.kaleido.sample.k74f8f2.C74f8f267e8
```

最终 Manifest 的原始片段：

```xml
<activity android:exported="true" android:name="com.tongsr.kaleido.sample.k0fdd3c.C0fdd3c6f42">
<service android:enabled="false" android:exported="false" android:name="com.tongsr.kaleido.sample.k74f8f2.C74f8f267e8"/>
<receiver android:enabled="false" android:exported="false" android:name="com.tongsr.kaleido.sample.kf11dba.Cf11dba09ef"/>
<provider android:authorities="com.tongsr.kaleido.sample.static-probe" android:name="com.tongsr.kaleido.sample.kd0bf18.Cd0bf18f520"/>
```

Provider 的业务 authority `com.tongsr.kaleido.sample.static-probe` 未被类名改写波及。

**判定：通过。** 四类组件形成 mapping → Manifest → DEX 的完整一致性链。

证据源：[`manifest-rewrite-intent.properties`](evidence/manifest-rewrite-intent.properties)、[`composed-mapping.txt`](evidence/composed-mapping.txt)、[`plugin DEX 精选证明`](evidence/plugin-dex-proof.txt)。

### 证据卡 03：编译 XML 中的自定义 View 类名同步

**功能目标**：XML 元素引用的自定义 View 改名后，编译 XML 和 DEX 必须同步；只改 DEX 会导致 inflate 失败。

**Baseline 编译 XML**：

```text
E: com.tongsr.kaleido.sample.StaticProbeView (line=9)
```

**转换计划**：

```properties
mapping=com.tongsr.kaleido.sample.StaticProbeView|com.tongsr.kaleido.sample.kf77a70.Cf77a70ae59
site=layout/protected_probe_layout.xml/element[1]|com.tongsr.kaleido.sample.StaticProbeView|com.tongsr.kaleido.sample.kf77a70.Cf77a70ae59
```

**插件包派生 APK 的编译 XML**：

```text
E: com.tongsr.kaleido.sample.kf77a70.Cf77a70ae59 (line=3)
```

**最终 DEX**：新描述符 `Lcom/tongsr/kaleido/sample/kf77a70/Cf77a70ae59;` 出现 1 次类定义，旧 `StaticProbeView` 类定义为 0。

**判定：通过。** 这是“XML 从名字 `StaticProbeView` 改为 `kf77a70.Cf77a70ae59`”的计划、编译 XML 与 DEX 三方证据。编译 XML 使用同一 AAB 生成的固定设备 APK Set，通过 `aapt2 dump xmltree` 读取。

证据源：[`xml-rewrite-intent.properties`](evidence/xml-rewrite-intent.properties)、[`baseline base-master.apk`](https://github.com/ujffdi/Kaleido/releases/download/sample-aab-validation-2026-09-02/baseline-base-master.apk)、[`plugin base-master.apk`](https://github.com/ujffdi/Kaleido/releases/download/sample-aab-validation-2026-09-02/plugin-base-master.apk)。

### 证据卡 04：资源名与文件路径改写

**功能目标**：改变可改写资源的 entry name 和文件路径，同时维持插件包资源 rewrite 前后的资源 ID。

| 资源 ID | Baseline 名称 | 插件最终名称 | 文件路径变化 |
| --- | --- | --- | --- |
| `0x7f070000` | `layout/activity_main` | `layout/kc472b50c80` | `base/res/layout/activity_main.xml` → `base/res/layout/kc472b50c80.xml` |
| `0x7f090003` | `string/app_name` | `string/kf08c6e23af` | values entry，无独立文件路径 |
| `0x7f0a0000` | `style/AppTheme` | `style/k1bad403e37` | values entry，无独立文件路径 |

资源 mapping 的原始片段：

```text
resource=0x7f070000|...:layout/activity_main -> ...:layout/kc472b50c80
path=base/res/layout/activity_main.xml -> base/res/layout/kc472b50c80.xml
resource=0x7f090003|...:string/app_name -> ...:string/kf08c6e23af
resource=0x7f0a0000|...:style/AppTheme -> ...:style/k1bad403e37
```

最终 ZIP 中存在 `base/res/layout/kc472b50c80.xml`，不存在 `base/res/layout/activity_main.xml`；最终资源表按相同 ID 命中新名称。

**判定：通过。** 资源表名称、文件引用与 ZIP 物理路径一致。这里的“ID 保持”指插件包 rewrite 输入到输出的边界，不表示加入大量生成资源后 baseline 与插件包的所有资源 ID 都必须相同。

证据源：[`resource-mapping.txt`](evidence/resource-mapping.txt)、[`plugin-enabled.aab`](https://github.com/ujffdi/Kaleido/releases/download/sample-aab-validation-2026-09-02/plugin-enabled.aab)。

### 证据卡 05：重复 drawable 物理去重

**功能目标**：保留每个资源 entry/ID，但让内容相同的 file-backed 资源引用同一规范化文件，并删除冗余 ZIP 条目。

```text
0x7f040001 drawable/ic_arrow_left_long
  -> drawable/k8429b21c4a
  -> base/res/drawable/k8429b21c4a.xml

0x7f040002 drawable/ic_arrow_left_long_duplicate
  -> drawable/kaaf2d9f046
  -> base/res/drawable/k8429b21c4a.xml
```

第二组证据相同：`ic_arrow_up_right_long` 和 duplicate 的两个 entry 分别改名为 `k50238a4eb7`、`k78d690aac7`，但都指向 `base/res/drawable/k50238a4eb7.xml`。

| 检查 | Baseline | 插件 AAB |
| --- | ---: | ---: |
| 左箭头重复组物理 XML | 2 | 1（`k8429b21c4a.xml`） |
| 右上箭头重复组物理 XML | 2 | 1（`k50238a4eb7.xml`） |
| 每组资源 entry / ID | 2 | 2 |

**判定：通过。** 这是文件 payload 去重，不是合并资源 ID；两个调用方资源身份仍各自存在。

### 证据卡 06：native、metadata、unused string 与语言过滤

**功能目标**：执行 FULL Profile 中明确声明的删除与过滤，同时保留非目标内容。

配置证据：

```properties
resources.nativeLibrariesToDelete=libobsolete.so
resources.metadataToDelete=META-INF/DEPENDENCIES
resources.replaceUnusedStrings=true
resources.confirmedUnusedStrings=confirmed_unused_label
resources.retainedLanguages=zh-CN
```

最终产物对比：

| 项目 | Baseline AAB | 插件 AAB | 判定 |
| --- | --- | --- | --- |
| `base/lib/x86_64/libobsolete.so` | 存在，42 bytes | 不存在 | 删除目标命中 |
| `base/root/META-INF/DEPENDENCIES` | 存在，54 bytes | 不存在 | 删除目标命中 |
| `base/lib/x86_64/libkeep.so` | 存在，39 bytes | 存在，39 bytes | 非目标保留 |
| `confirmed_unused_label` | 资源表存在 | 最终资源表 0 命中 | 确认未使用项删除 |
| `locale_probe` | default、fr、zh-CN | `k501fca2d85`：default、zh-CN，fr 为 0 命中 | 语言过滤命中 |

`confirmed_unused_label` 仍出现在资源 mapping 计划中，表示它在 rewrite 输入阶段可枚举；它不出现在最终资源表，证明后续 confirmed-unused 删除实际完成。

**判定：通过。** 目标删除与非目标保留同时成立，避免了仅凭“文件变少”作结论。

### 证据卡 07：保护规则与 Escape Hatch

**功能目标**：对按名称、路径、动态查找或运行时类名声明保护的对象保持稳定身份，并在需要时阻止 R8 删除。

| 保护目标 | 声明/发现来源 | Baseline / 插件结果 | 判定 |
| --- | --- | --- | --- |
| `string/sample_status` | `protection.resourceNames=sample_status` | 插件最终资源表仍叫 `sample_status` | 名称稳定 |
| `layout/protected_probe_layout` | `PACKAGED_PATH+RESOURCE_NAME` Escape Hatch | 插件最终名和 `base/res/layout/protected_probe_layout.xml` 路径不变 | 名称/路径稳定 |
| `string/runtime_label` | 字节码发现精确 `getIdentifier` | 插件最终资源表仍叫 `runtime_label` | 动态查找保护 |
| `RuntimeProtectedEntry` | `ORIGINAL_IDENTITY+REACHABILITY+RUNTIME_ATTRIBUTES` | baseline 被普通 R8 移除；插件 DEX 原描述符类定义 1 | 原身份与可达性保留 |

保护 keep 规则与 composed mapping 原始片段：

```text
-keep,allowoptimization class com.tongsr.kaleido.sample.RuntimeProtectedEntry { *; }

com.tongsr.kaleido.sample.RuntimeProtectedEntry
  -> com.tongsr.kaleido.sample.RuntimeProtectedEntry:
```

**判定：通过。** 保护功能不仅让目标“不改名”，还让本来会被 baseline R8 移除的运行时入口保留在最终 DEX。

证据源：[`adoption-plan.properties`](evidence/adoption-plan.properties)、[`resource-protection.properties`](evidence/resource-protection.properties)、[`protection.keep`](evidence/protection.keep)。

### 证据卡 08：Compose 生成、编译与最终保留

**功能目标**：显式启用后生成闭合 Compose 函数，经过 Compose compiler 与 R8 后仍存在于最终 DEX，且没有普通业务字节码入口。

```properties
generation.compose.enabled=true
generation.compose.fileCount=16
generation.compose.functionsPerFile=32

facades=16
functions=512
dexFiles=2
mappingResolved=true
incomingBytecodeEdges=0
finalDexRetained=true
```

具体 facade 样例：

```text
KldCompose_02e7b7359eea
  -> com.tongsr.kaleido.sample.kaleido.generated.compose.kbb68d0.Cbb68d0ad82

编译后方法样例：
kld_6e3cbb95ecdb|(ILandroidx/compose/runtime/Composer;I)I
```

Dexcount 对 `...generated.compose` 的包级统计为 512 个方法引用；最终回执同时确认 16 facade、512 函数、mapping 全解析、2 个 DEX 中保留，并且普通入边为 0。

原 `44 × 33` 配置违反每文件最多 32、总计最多 512 的公开边界，已改为 `16 × 32 = 512`。首次 512 规模还暴露跨文件深度 512 调用链导致 Compose compiler `StackOverflowError`；修复后调用链限制在单文件、最大深度 32，并由契约测试覆盖。

**判定：通过。** 证明生成、Compose lowering、mapping 和最终 DEX 保留；`incomingBytecodeEdges=0` 不等于对反射、JNI 或下载代码的绝对不可达证明。

证据源：[`compose-compiled-inventory.properties`](evidence/compose-compiled-inventory.properties)、[`compose-final-dex-receipt.properties`](evidence/compose-final-dex-receipt.properties)。

### 证据卡 09：确定性 R8 字典与 composed mapping

**功能目标**：为 class、member、package 生成稳定字典，并把 Kaleido pre-R8 改名与 R8 改名合成为原始类到最终类的可追溯映射。

| 字典 | 数据项 | 首个 token | SHA-256 |
| --- | ---: | --- | --- |
| class | 4,096 | `C00042dffdc` | `f6751844d8ccfa2b2ffb152a6e023f2be44dc1970187fb1462994aacbb4a0bd2` |
| member | 4,096 | `m001f12ea9c` | `af6d413e886b13be94705ab876caf1906e81757691cef9f4f22e066297d20cea` |
| package | 4,096 | `p000665a366` | `5c8a2cf08a8acf6fcaae2f461188709ee857aa7b6597b583b45ad8e5016b3116` |

具体三段映射链：

```text
原始生成类：...generated.p_0990225c.C_b8de81286c
raw Kaleido：...generated.p_0990225c.k04a1ee.C04a1ee4378
raw R8：     C0eb5829e95
composed：原始生成类 -> C0eb5829e95
```

R8 规则明确引用三份字典：

```text
-obfuscationdictionary ../dictionaries/member.txt
-classobfuscationdictionary ../dictionaries/class.txt
-packageobfuscationdictionary ../dictionaries/package.txt
```

**判定：通过。** 三份字典被实际接入 R8，且 raw/composed mapping 对同一类给出一致的两段与直达关系。

### 证据卡 10：Dexcount 4.0.0 量化

按照 [KeepSafe Dexcount 官方文档](https://keepsafe.github.io/dexcount-gradle-plugin/)，baseline 与插件模块均应用 `com.getkeepsafe.dexcount:4.0.0`，任务直接读取各自 release AAB：

```shell
./gradlew :baseline:countReleaseBundleDexMethods :app:countReleaseBundleDexMethods
```

| 指标 | Baseline | Kaleido | 净增量 | 增幅 | 倍率 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 方法引用 | 8,591 | 70,431 | +61,840 | +719.82% | 8.20× |
| 字段指标 | 4,364 | 5,634 | +1,270 | +29.10% | 1.29× |
| 类指标 | 1,604 | 2,873 | +1,269 | +79.11% | 1.79× |
| DEX 文件 | 1 | 2 | +1 | +100% | 2.00× |
| AAB 字节数 | 1,886,380 | 3,431,428 | +1,545,048 | +81.91% | 1.82× |
| 固定设备规格 APK Set 字节数 | 1,395,018 | 4,805,619 | +3,410,601 | +244.48% | 3.44× |

包级原始片段：

```text
61750  com.tongsr.kaleido.sample.kaleido.generated
38     com.tongsr.kaleido.sample.kaleido.generated.components
512    com.tongsr.kaleido.sample.kaleido.generated.compose
```

61,750 占两包方法净增量 61,840 的 **99.85%**。其构成可精确复核：

```text
1,200 普通类 × (50 声明方法 + 1 构造方法) = 61,200
38 生成 Activity × 1 构造方法                 =     38
512 Compose 函数                              =    512
合计                                          = 61,750
```

Dexcount 的 `107.47% used` 是把两个 DEX 的总方法引用与单个 DEX 的 65,536 阈值比较；插件包已成功生成两个 DEX，不表示构建失败。Dexcount 不测量资源、native、下载体积、启动耗时或运行时内存。

原始输出：[`baseline summary.csv`](evidence/dexcount/baseline-summary.csv)、[`baseline release.txt`](evidence/dexcount/baseline-release.txt)、[`plugin summary.csv`](evidence/dexcount/plugin-summary.csv)、[`plugin release.txt`](evidence/dexcount/plugin-release.txt)。

### 证据卡 11：签名、结构与可重复性

签名回执原始数据：

```properties
unsignedAabSha256=1291547ffcbeabf6ffe402dd36c0e6fe5ebeb84eaa93cc73251695efd55e3af7
signedAabSha256=935fd1e9fae270af17a90ba23a76bc1cc867a62e0561da7f3583fbb1d9c4c8c8
certificateSha256=2aab083453565aca922c100e7001a520027b55af50afa05c3ec2e6c2f262c5b6
signatureCoverageValidated=true
certificateMatched=true
bundletoolValidated=true
```

两次干净、关闭 Build Cache 的构建对比：

| 确定性边界文件 | 第一次 SHA-256 | 第二次 SHA-256 | 结果 |
| --- | --- | --- | --- |
| unsigned-candidate.aab | `1291547f...e3af7` | `1291547f...e3af7` | 相同 |
| deterministic-evidence-manifest.properties | `15f47961...768b0` | `15f47961...768b0` | 相同 |
| raw-kaleido-mapping.txt | `476dc3c9...96a1` | `476dc3c9...96a1` | 相同 |
| raw-r8-mapping.txt | `41ea8821...0bc3` | `41ea8821...0bc3` | 相同 |
| composed-mapping.txt | `0dcb4540...297e` | `0dcb4540...297e` | 相同 |
| resource-mapping.txt | `01c2ab7f...40ca` | `01c2ab7f...40ca` | 相同 |

最终签名 AAB 的字节哈希允许因 JAR 签名元数据变化而不同；Kaleido 承诺并已验证的确定性边界是未签名候选、生成内容、计划、字典和映射。每次 Evidence Set 均把对应签名 AAB 绑定到同一个未签名 digest 和证书身份。

**判定：通过。** 结构校验、完整签名覆盖、证书匹配和声明的确定性边界均有回执与重复构建证据。

证据源：[`signing-receipt.properties`](evidence/signing-receipt.properties)、[`release-evidence-set-manifest.properties`](evidence/release-evidence-set-manifest.properties)。

## 已执行的验证

1. `:kaleido-gradle-plugin:test`：通过，包含 Compose 调用深度回归契约。
2. 干净、关闭 Build Cache 的 baseline + plugin release AAB 联合构建：通过，110 个任务执行成功。
3. 第二次干净、关闭 Build Cache 的 plugin release AAB 构建：通过。
4. bundletool：两份 AAB 结构有效，并成功生成固定设备规格 APK Set。
5. `bundletool dump manifest/resources`、`aapt2 dump xmltree`、Build Tools 36.0.0 `dexdump`、ZIP 条目、mapping 和 Evidence Set 交叉检查：通过。
6. `jarsigner -verify`：插件 AAB 完整签名通过；baseline 确认为未签名对照。
7. Dexcount `countReleaseBundleDexMethods`：两模块任务通过，输入 DEX 哈希与报告 AAB 完全一致。

## 总判定与限制

- **baseline-without-plugin.aab：有效对照，未运行 Kaleido。** 最终类名、XML、资源、待删除条目和单 DEX 规模符合未应用插件的预期。
- **plugin-enabled.aab：Kaleido 有效。** 四项核心能力和各功能点均有至少一组具体 before/after 数据，并由最终 AAB 或其固定设备 APK Set 反向确认。
- **尚未验证：设备运行行为。** 如需证明启动、资源加载、四类组件实例化或交互路径，应另做真机人工测试；这不改变本报告对“插件真实参与构建并按配置改变最终 AAB”的静态结论。

本次验证直接使用 AAB 的 ZIP、protobuf、DEX、mapping 和 Kaleido Release Evidence Set，形成可重复的产物证据链。
