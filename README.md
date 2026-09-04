# Kaleido

[简体中文](README.md) | [English](README.en.md)

Kaleido 是一个采用 Apache-2.0 许可证、面向 Android Release AAB 的 Gradle
构建加固插件。它把原本分散在代码与资源生成、类名与 Manifest/XML 引用联动、
R8、最终 AAB 资源处理、重新签名和产物验证中的工作，接入同一次常规
`bundleRelease`。

接入方只需应用一个插件，按需配置一个 `kaleido {}` 块，然后继续执行原有的
Release Bundle 任务。Kaleido 不修改 Consumer Project 的 `src/`；生成内容和中间
产物都位于 `build/` 下。

## 为什么需要 Kaleido

Android Release 的可分析面不只存在于 DEX 类名。Manifest 和 XML 可能继续引用应用
类，资源表和文件路径可能保留业务语义，而类名经过 Kaleido 与 R8 两个阶段后还需要
一份从原始身份到最终身份的完整映射。若这些环节彼此独立，漏改一处引用就可能导致
组件实例化、XML inflate、资源加载、Retrace 或最终 AAB 签名失效。

Kaleido 把这些相互依赖的工作组织成一条自动、确定且可审计的 Release 流水线：

| Release 中的问题或分散环节 | Kaleido 的处理 | 最终结果 |
| --- | --- | --- |
| 需要额外生成加固内容 | 按稳定种子生成 Kotlin、XML、字符串及可选 Manifest/Compose 内容 | 不改动 `src/`，相同声明输入、种子和工具链可重复生成 |
| 应用类名与 Manifest/XML 引用相互关联 | 改写符合条件的应用类，并同步处理受支持的非代码引用 | 类身份发生变化，同时保持已支持引用闭合 |
| 资源名称、文件路径和重复载荷暴露在最终 Bundle 中 | 在最终 AAB 中协调改写资源名、引用和路径，并安全合并兼容的重复载荷 | 保留资源 ID 和声明保护项，降低资源结构的可读性 |
| Kaleido 类改写与 R8 产生多层名称变化 | 生成确定性 R8 字典，保留原始映射并发布组合映射 | 可用同一个 Release Evidence Set 中的映射执行 Retrace 和审计 |
| 最终 AAB 经过二次变换 | 重新签名，核对证书和签名覆盖，验证 Bundle 结构后原子发布 | 只发布完整通过验证的 AAB、映射和 Artifact Report |

一次正常构建中的核心路径如下：

```text
:app:bundleRelease
  → 校验配置与签名输入
  → 生成并编译加固内容
  → 改写应用类及 Manifest/XML 引用
  → 执行 R8 并组合映射
  → 改写最终 AAB 资源
  → 重新签名并验证
  → 发布 AAB + mappings + Artifact Report
```

代码与资源生成、类名及引用联动、最终 AAB 资源处理、确定性 R8 字典与映射构成
Kaleido 的四类核心能力。`SAFE` 和 `FULL` 都会执行全部四类能力；`FULL` 只解锁显式
选择的 Activity 生成与资源操作，仅选择 `FULL` 不会自动删除或过滤任何内容。

## 真实 Sample 中的可见变化

[Sample AAB 验证报告](https://ujffdi.github.io/Kaleido/sample-aab-validation/)
使用同一份 Sample `src/main` 构建两份 Release AAB：baseline 不应用 Kaleido，
plugin-enabled AAB 应用 `FULL` Profile。报告按照“baseline 最终状态 → Kaleido
转换计划或映射 → 插件 AAB 最终状态”核对真实产物，其中包括：

| 检查项 | Baseline | Kaleido 最终产物 |
| --- | --- | --- |
| Activity 类身份 | `com.tongsr.kaleido.sample.MainActivity` | `com.tongsr.kaleido.sample.k0fdd3c.C0fdd3c6f42`；Manifest 使用新名称，DEX 中旧描述符为 0 |
| Layout 名称与路径 | `layout/activity_main`、`base/res/layout/activity_main.xml` | `layout/kc472b50c80`、`base/res/layout/kc472b50c80.xml`；改写边界内资源 ID 保持不变 |
| 重复 Drawable | 两个不同资源 ID 各有文件载荷 | 资源 ID 继续独立，内容相同的资源指向同一份规范化文件载荷 |
| 发布完整性 | 插件未运行 | 生成、类引用、R8、资源、签名、Bundle 验证和原子发布阶段全部通过 |

结论依据来自最终 Manifest、资源表、编译 XML、DEX、ZIP 条目、raw/composed
mapping、签名回执和 Bundle 验证，而不是仅凭 AAB 大小或字符串搜索判断插件有效。

Kaleido 的目标是提高静态分析或篡改 Android Release 的成本，并留下可核查的构建
证据。它不是运行时安全框架，不能替代业务安全、服务端校验或密钥保护；也不承诺
应用商店审核通过、规避审核或绝对抵御所有运行时动态加载机制。当前 Sample 结论
属于静态产物和受控构建验证，不代表已经完成真机运行、覆盖所有设备或获得商店接受。

## 接入要求

Kaleido `0.1.1` 的开发与测试环境为：

| 主机 | Android Gradle Plugin | Gradle | JDK | Build Tools | compileSdk |
| --- | --- | --- | --- | --- | --- |
| macOS arm64 | 9.2.0 | 9.4.1 | 17 | 36.0.0 | 36 |

其他要求：

- 只能应用到 Android Application 模块，并且必须先应用
  `com.android.application`，再应用 Kaleido。
- 必须声明名称精确为 `release` 的 Build Type，并启用 R8 压缩混淆。
- 使用 AGP built-in Kotlin。
- 每个参与构建的 Release Variant 都必须有一套完整的上传签名来源。
- 启用 Compose 生成时，Consumer Project 必须已经启用 Compose、应用
  `org.jetbrains.kotlin.plugin.compose`，并在 Release 编译类路径中提供 Compose
  Runtime。

其他主机和工具链版本尚未验证，但它们不是发布门禁。MVP 不支持 Dynamic
Feature、Asset Pack、AI Pack、test-only、Android Library 和 KMP 目标。

## 快速接入

### 1. 解析插件

在 `settings.gradle.kts` 中启用 Gradle Plugin Portal：

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

在应用模块的 `build.gradle.kts` 中，先应用 Android Application 插件，再应用
Kaleido：

```kotlin
plugins {
    id("com.android.application") version "9.2.0"
    id("io.github.ujffdi.kaleido") version "0.1.1"
}

android {
    namespace = "com.example.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
```

### 2. 提供上传签名

最短且安全的方式是提供一套完整的环境变量：

```shell
export KALEIDO_UPLOAD_KEYSTORE=/secure/path/upload.p12
export KALEIDO_UPLOAD_STORE_PASSWORD='store-password'
export KALEIDO_UPLOAD_KEY_ALIAS='upload'
export KALEIDO_UPLOAD_KEY_PASSWORD='key-password'
export KALEIDO_UPLOAD_CERTIFICATE_SHA256='64-character-certificate-sha256'
```

签名凭据应保存在受保护的 Secret Store 中，不要提交密钥库或密码。

### 3. 构建

无需声明 `kaleido {}` 块。只应用插件时会自动使用 `SAFE` 与 Safe Defaults v1：

```shell
./gradlew :app:bundleRelease
```

存在产品风味时，继续使用原来的精确任务，例如
`./gradlew :app:bundlePaidRelease`。

Safe Defaults v1 使用 `<namespace>.kaleido.generated`，生成 4 个包、每包 4 个类、
每类 4 个方法、8 个布局、16 个 XML Drawable 和 32 个字符串；不生成 Activity，
Compose Generator 默认关闭。

开发者可直接运行的完整示例位于
[`samples/kaleido-sample`](samples/kaleido-sample)。它使用同一套 Consumer 输入构建
Kaleido AAB 与 Baseline AAB，便于对比。

## 完整 `kaleido {}` 配置

下面的 Kotlin DSL 代码块覆盖所有受支持的产品意图参数。实际项目只复制需要的控制
项。每个参数的注释都给出了用途、默认值、范围与 Profile 边界。

```kotlin
kaleido {
    // 运行策略。默认值：SAFE。可选值：SAFE、FULL。
    // FULL 只解锁显式 Activity/资源控制，本身不会启用任何删除或过滤。
    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)

    // 可选的确定性根种子 Provider。
    // 默认值：根据 application ID 与精确 Variant 身份生成。
    // 报告只记录种子的 SHA-256 指纹。
    seed.set(providers.environmentVariable("KALEIDO_ROOT_SEED"))

    generation {
        // 生成代码的基础包名。
        // 默认值：<namespace>.kaleido.generated。必须是合法的点分包名。
        packageBase.set("com.example.app.generated")

        // 生成的包数量。默认值：4。范围：1..64。
        packageCount.set(30)

        // 每个包生成的普通类数量。默认值：4。范围：1..64。
        classesPerPackage.set(10)

        // 每个普通类生成的方法数量。默认值：4。范围：1..128。
        methodsPerClass.set(10)

        // 生成的 XML Layout 数量。默认值：8。范围：1..256。
        layoutCount.set(20)

        // 生成的 XML Drawable 数量。默认值：16。范围：1..512。
        drawableCount.set(20)

        // 生成的字符串资源数量。默认值：32。范围：1..4096。
        stringCount.set(50)

        // 生成的惰性、非导出 Activity 数量。
        // 默认值：0。范围：0..64。非零值要求使用 FULL。
        activityCount.set(2)

        compose {
            // 启用 internal 的 Compose Runtime-only 函数生成。
            // 默认值：false。满足前置条件时，SAFE 与 FULL 都可启用。
            enabled.set(true)

            // 生成的 Kotlin 文件/JVM Facade 数量。默认值：4。范围：1..64。
            fileCount.set(4)

            // 每个文件生成的 @Composable 函数数量。默认值：4。范围：1..32。
            // fileCount * functionsPerFile 不能超过 512。
            functionsPerFile.set(4)
        }
    }

    resources {
        // 按精确文件名删除 Native Library。默认值：空。仅限 FULL。
        // 只填写不带目录的 lib*.so 文件名；空集合不会删除任何内容。
        nativeLibrariesToDelete.add("libobsolete.so")

        // 删除许可范围内的精确 META-INF 条目。默认值：空。仅限 FULL。
        // 允许：INDEX.LIST、DEPENDENCIES、LICENSE*、NOTICE*。
        metadataToDelete.add("META-INF/DEPENDENCIES")

        // 替换由下面清单确认未使用的字符串。
        // 默认值：false。仅限 FULL。必须配置 confirmedUnusedStringsFile。
        replaceUnusedStrings.set(true)

        // UTF-8 文件，每行填写一个精确字符串资源名。
        // 空行与 # 注释会被忽略；配置该文件本身也会启用替换。
        confirmedUnusedStringsFile.set(
            layout.projectDirectory.file("kaleido-unused-strings.txt")
        )

        // 保留指定语言，同时过滤其他语言配置。
        // 默认值：空，即不进行语言过滤。仅限 FULL。使用规范语言标签。
        retainedLanguages.addAll("en", "zh-CN")
    }

    protection {
        // 必须保持原始名称不变的精确应用类身份。
        // 默认值：空。最多 1,024 个非空条目。
        originalClassNames.add("com.example.app.RuntimeEntry")

        // 禁止重命名的精确应用资源 Entry 名。
        // 默认值：空。最多 1,024 个非空条目。
        resourceNames.add("dynamic_icon")

        // 必须在 AAB 中保持不变的精确文件型资源路径。
        // 默认值：空。最多 1,024 个非空条目。
        packagedPaths.add("res/layout/protected_screen.xml")

        // 有界的类 Escape Hatch。稳定 ID 必须全局唯一。
        classes("runtime-entry") {
            // 精确选择一个应用类；选择有界包范围时使用 prefix(...)。
            exact("com.example.app.RuntimeEntry")

            // 保留可达性、原始类名、项目自有描述符闭包，
            // 以及运行时注解、签名和类关联属性。
            dimensions.addAll(
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.REACHABILITY,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.ORIGINAL_IDENTITY,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.DESCRIPTOR_CLOSURE,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.RUNTIME_ATTRIBUTES,
            )

            // 必填的非空审核原因，最长 512 个字符。
            reason.set("运行时按照精确类名加载该入口")
        }

        // 有界的资源 Escape Hatch，使用另一个全局唯一稳定 ID。
        resources("dynamic-icons") {
            // 选择一个有界资源名前缀；选择单个资源时使用 exact(...)。
            prefix("dynamic_")

            // 保留资源 Entry 名称与文件型资源在 AAB 中的路径。
            dimensions.addAll(
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.RESOURCE_NAME,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.PACKAGED_PATH,
            )

            // 必填的非空审核原因，最长 512 个字符。
            reason.set("服务端响应会解析这一有界资源集合")
        }
    }

    // 可选的顶层签名来源，应用到所有符合条件的 Release Variant。
    // 五个字段是原子的：配置不完整会失败，不会从其他来源补齐。
    signing {
        // 上传密钥库文件。实际文件应放在源码管理之外。
        keyStoreFile.set(layout.projectDirectory.file("upload.p12"))

        // 上传密钥库密码 Provider。
        storePassword.set(providers.environmentVariable("UPLOAD_STORE_PASSWORD"))

        // 上传密钥别名。
        keyAlias.set("upload")

        // 上传私钥密码 Provider。
        keyPassword.set(providers.environmentVariable("UPLOAD_KEY_PASSWORD"))

        // 预期上传证书 SHA-256，必须是 64 位十六进制摘要。
        expectedCertificateSha256.set(
            providers.environmentVariable("UPLOAD_CERTIFICATE_SHA256")
        )
    }
}
```

精确 Variant 可以使用一套完整配置覆盖顶层签名来源。Variant 名必须精确匹配，不
支持通配符：

```kotlin
kaleido {
    signing("paidRelease") {
        keyStoreFile.set(layout.projectDirectory.file("paid-upload.p12"))
        storePassword.set(providers.environmentVariable("PAID_STORE_PASSWORD"))
        keyAlias.set("paid-upload")
        keyPassword.set(providers.environmentVariable("PAID_KEY_PASSWORD"))
        expectedCertificateSha256.set(
            providers.environmentVariable("PAID_CERTIFICATE_SHA256")
        )
    }
}
```

`kaleido-unused-strings.txt` 每行填写一个唯一资源名，可使用 `unused_title` 或
`string/unused_title`：

```text
# 已通过应用级分析确认未使用
unused_title
string/obsolete_message
```

## SAFE 与 FULL

| 能力 | `SAFE` | `FULL` |
| --- | --- | --- |
| 普通 Kotlin/资源生成 | 始终执行 | 始终执行 |
| Compose Generator | 显式启用 | 显式启用 |
| Activity 生成 | 不允许 | 显式设置非零 `activityCount` |
| Native/Metadata 删除 | 不允许 | 显式有界选择器 |
| 未使用字符串替换 | 不允许 | 显式确认未使用文件 |
| 语言过滤 | 不允许 | 显式保留语言集合 |
| 类/资源/R8 保护 | 始终执行 | 始终执行 |

## 签名优先级

Kaleido 按以下顺序选择一套完整来源：

1. 精确 Variant 的 `signing("variantName")` DSL。
2. 顶层 `signing {}` DSL。
3. 完整的 `KALEIDO_UPLOAD_*` 环境变量。
4. 完整的 `kaleido.uploadSigning.*` Gradle Property。

等价的外部配置名称：

| 值 | 环境变量 | Gradle Property |
| --- | --- | --- |
| 密钥库 | `KALEIDO_UPLOAD_KEYSTORE` | `kaleido.uploadSigning.keyStoreFile` |
| 密钥库密码 | `KALEIDO_UPLOAD_STORE_PASSWORD` | `kaleido.uploadSigning.storePassword` |
| 密钥别名 | `KALEIDO_UPLOAD_KEY_ALIAS` | `kaleido.uploadSigning.keyAlias` |
| 私钥密码 | `KALEIDO_UPLOAD_KEY_PASSWORD` | `kaleido.uploadSigning.keyPassword` |
| 证书 SHA-256 | `KALEIDO_UPLOAD_CERTIFICATE_SHA256` | `kaleido.uploadSigning.expectedCertificateSha256` |

较高优先级来源只要缺少字段就会失败，Kaleido 不会从低优先级来源补齐。

## 构建产物

成功的 `release` 构建会发布：

```text
app/build/outputs/bundle/release/app-release.aab
app/build/reports/kaleido/release/release-evidence-set/
```

Release Evidence Set 包含 `artifact-report.txt`、证据清单以及 `mappings/` 下的映射
文件。还原混淆崩溃堆栈时，必须使用与该 AAB 来自同一个 Evidence Set 的
`composed-mapping.txt`。失败构建不会发布不完整或过期的成功证据集。

## 常见错误

- `KLD-ADOPTION-*`：确保在 `com.android.application` 之后应用 Kaleido，并且只
  应用到 Application 模块。
- `KLD-TOPOLOGY-*`：声明精确且启用混淆的 `release` Build Type，并移除不支持的
  AAB 变换所有者或模块拓扑。
- `KLD-CONFIG-*`：根据错误信息修正字段、范围、Profile 或 Compose 前置条件。
- `KLD-PROTECTION-*`：使用有界选择器、匹配的保护维度、真实的项目自有目标和审核
  原因。
- `KLD-SIGNING-*`：由同一个来源提供全部五个字段，并验证上传证书指纹。
- `KLD-COMPAT-*`：使用经过测试的 AGP、Gradle 与 JDK 组合，并按照诊断中的
  修复建议处理。

先阅读第一条硬错误中的 `reason` 与 `repair`，修复后重新执行同一个精确 Variant。

## 更多文档

- [接入、Profile、DSL 与签名](docs/public/adoption.md)
- [证据、诊断、映射与 Retrace](docs/public/evidence-and-diagnostics.md)
- [威胁模型与安全边界](docs/public/security-model.md)
- [升级与不可变发布策略](docs/public/upgrade-and-release.md)
- [许可证与来源说明](THIRD_PARTY_NOTICES.md)
- [安全问题报告](SECURITY.md)
- [参与贡献](CONTRIBUTING.md)
- [更新日志](CHANGELOG.md)

## 赞赏支持

如果你觉得 Kaleido 对你有用，感谢老板们打赏一杯咖啡 ☕

<table>
  <tr>
    <th align="center">微信支付</th>
    <th align="center">支付宝</th>
  </tr>
  <tr>
    <td align="center"><img src="docs/public/images/donations/wechat-pay.jpg" alt="微信支付赞赏二维码" width="300"></td>
    <td align="center"><img src="docs/public/images/donations/alipay.jpg" alt="支付宝赞赏二维码" width="300"></td>
  </tr>
</table>
