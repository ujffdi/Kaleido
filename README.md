# Kaleido

[English](#english) | [中文](#中文)

## English

Kaleido is an Apache-2.0 Gradle plugin for deterministic, auditable Android
Release AAB hardening. A Consumer Project applies one plugin, optionally
configures one `kaleido {}` block, and runs its normal Release bundle task.

Kaleido combines four capability families in that build:

1. Generates deterministic Java/Kotlin code, resources, and optional Manifest
   contributions. The opt-in Compose Generator belongs to this capability.
2. Rewrites eligible application class names while synchronizing supported
   Manifest and XML class references.
3. Obfuscates and optimizes resources in the final AAB while preserving resource
   IDs and declared protected names and paths.
4. Generates deterministic R8 dictionaries and publishes raw and composed
   mappings for the exact Release artifact.

Both the `SAFE` and `FULL` profiles run all four families. `FULL` only unlocks
explicit Activity generation and explicitly selected resource operations; it
does not enable them automatically.

Kaleido raises the cost of inspecting or tampering with an Android release. It
does not guarantee store approval, review evasion, absolute unreachability, or
protection against every runtime loading mechanism.

### Requirements

- An Android application module. Apply `com.android.application` before Kaleido.
- An exact `release` build type with R8 minification enabled.
- JDK 17, Build Tools 36.0.0, and compileSdk 36.
- A complete upload-signing source for every Release variant being built.
- For Compose generation: an already Compose-enabled Consumer Project, the
  matching `org.jetbrains.kotlin.plugin.compose` plugin, and Compose Runtime on
  the Release compile classpath.

Kaleido validates exact compatibility rows instead of claiming generic AGP 9
support. The `0.1.0` release targets the following rows; consult the
[Compatibility Matrix](docs/public/compatibility-and-performance.md) for their
current support status and evidence:

| Row | Android Gradle Plugin | Gradle |
| --- | --- | --- |
| A3 | 9.2.1 | 9.4.1 |
| A4 | 9.3.2 | 9.5.0 |

Dynamic Feature, Asset Pack, AI Pack, test-only, Android library, and KMP targets
are outside the MVP topology.

### Install

Add the plugins to the application module's `build.gradle.kts`. The order is
significant because Kaleido requires the Android application plugin to already
be applied.

```kotlin
plugins {
    id("com.android.application") version "9.2.1"
    id("io.github.ujffdi.kaleido") version "0.1.0"
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

Make sure `gradlePluginPortal()` is available to `pluginManagement` in
`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

### Quick start: Safe Defaults

No `kaleido {}` block is required. Plugin-only adoption selects `SAFE` and Safe
Defaults v1. Provide one complete signing source, for example through protected
environment variables:

```shell
export KALEIDO_UPLOAD_KEYSTORE=/secure/path/upload.p12
export KALEIDO_UPLOAD_STORE_PASSWORD='store-password'
export KALEIDO_UPLOAD_KEY_ALIAS='upload'
export KALEIDO_UPLOAD_KEY_PASSWORD='key-password'
export KALEIDO_UPLOAD_CERTIFICATE_SHA256='64-character-certificate-sha256'

./gradlew :app:bundleRelease
```

For a flavored variant, run its normal task, such as
`./gradlew :app:bundlePaidRelease`. Kaleido applies the top-level configuration
to every eligible Release variant.

Safe Defaults v1 generates:

- 4 packages, 4 ordinary classes per package, and 4 methods per class;
- 8 layouts, 16 XML drawables, and 32 strings;
- no generated Activity and no Compose code;
- a package base of `<android namespace>.kaleido.generated`;
- a deterministic root seed derived from application ID and exact variant
  identity when no explicit seed is supplied.

### Full and Compose example

Use `FULL` only when the build needs one of the explicitly selected Full-only
controls. This example also enables the optional Compose Generator:

```kotlin
kaleido {
    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)
    seed.set(providers.environmentVariable("KALEIDO_ROOT_SEED"))

    generation {
        packageBase.set("com.example.app.generated")
        packageCount.set(8)
        classesPerPackage.set(6)
        methodsPerClass.set(8)
        layoutCount.set(12)
        drawableCount.set(24)
        stringCount.set(48)
        activityCount.set(2)

        compose {
            enabled.set(true)
            fileCount.set(4)
            functionsPerFile.set(4)
        }
    }

    resources {
        nativeLibrariesToDelete.add("libobsolete.so")
        metadataToDelete.add("META-INF/DEPENDENCIES")
        replaceUnusedStrings.set(true)
        confirmedUnusedStringsFile.set(
            layout.projectDirectory.file("kaleido-unused-strings.txt")
        )
        retainedLanguages.addAll("en", "zh-Hans-CN")
    }
}
```

`kaleido-unused-strings.txt` contains one exact string resource name per line.
Blank lines and lines beginning with `#` are ignored; both `unused_title` and
`string/unused_title` are accepted. Supplying this file also enables unused
string replacement, even when `replaceUnusedStrings` is not set explicitly.

```text
# Confirmed by application-level analysis
unused_title
string/obsolete_message
```

Compose generation produces internal Compose Runtime-only functions under
`build/`. It does not generate UI, Preview, navigation, components, startup
entries, or references to Consumer Project symbols.

### Protection and Escape Hatches

Kaleido detects supported Manifest, XML, reflection, and JNI requirements. Use
typed Escape Hatches for bounded dynamic behavior that cannot be proven
automatically:

```kotlin
kaleido {
    protection {
        originalClassNames.add("com.example.app.RuntimeEntry")
        resourceNames.add("dynamic_icon")
        packagedPaths.add("res/raw/runtime_protocol.bin")

        classes("runtime-entry") {
            exact("com.example.app.RuntimeEntry")
            dimensions.addAll(
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.REACHABILITY,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.ORIGINAL_IDENTITY,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.DESCRIPTOR_CLOSURE,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.RUNTIME_ATTRIBUTES,
            )
            reason.set("The runtime loads this entry by its original class name")
        }

        resources("dynamic-icons") {
            prefix("dynamic_")
            dimensions.addAll(
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.RESOURCE_NAME,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.PACKAGED_PATH,
            )
            reason.set("A server response resolves this bounded resource family")
        }
    }
}
```

Every Escape Hatch ID must be globally unique across class and resource blocks,
use 3..64 lowercase letters, digits, `.`, `_`, or `-`, and remain stable across
builds. Every declaration needs exactly one `exact(...)` or bounded
`prefix(...)` selector, at least one applicable dimension, and a non-empty
review reason. Global wildcards and declarations matching no project-owned
target fail closed.

| Escape Hatch field | Meaning |
| --- | --- |
| `classes("stable-id")` / `resources("stable-id")` | Creates a typed declaration whose stable ID identifies it in plans, diagnostics, and evidence. |
| `exact("identity")` | Selects one exact class identity or resource entry name. |
| `prefix("bounded-prefix")` | Selects one bounded package or resource-name family; `*` is never valid. |
| `dimensions` | Declares the minimum runtime properties Kaleido must preserve. |
| `reason` | Records a non-blank, reviewable explanation of why the protection is required; maximum 512 characters. |

### Signing

Kaleido signs the canonical transformed AAB and verifies the expected upload
certificate and complete JAR signature coverage. One signing source must provide
all five fields atomically:

```kotlin
kaleido {
    signing {
        keyStoreFile.set(layout.projectDirectory.file("upload.p12"))
        storePassword.set(providers.environmentVariable("UPLOAD_STORE_PASSWORD"))
        keyAlias.set("upload")
        keyPassword.set(providers.environmentVariable("UPLOAD_KEY_PASSWORD"))
        expectedCertificateSha256.set(
            providers.environmentVariable("UPLOAD_CERTIFICATE_SHA256")
        )
    }
}
```

An exact variant may override the complete top-level source:

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

Signing-source precedence is:

1. Exact-variant `signing("variantName")` DSL.
2. Top-level `signing {}` DSL.
3. The complete `KALEIDO_UPLOAD_*` environment-variable convention.
4. The complete `kaleido.uploadSigning.*` Gradle-property convention.

A partial higher-precedence source fails. Kaleido never fills its missing fields
from a lower-precedence source. Keep passwords in protected providers or secret
stores; do not commit them to source control.

### DSL reference

#### Top level

| Configuration | Meaning | Default | Allowed values / Profile |
| --- | --- | --- | --- |
| `profile` | Selects the adoption policy. Both profiles run the complete hardening pipeline. | `SAFE` | `SAFE` or `FULL` |
| `seed.set(Provider<String>)` | Replaces the derived root seed. All capability and variant sub-seeds are derived internally; reports contain only its SHA-256 fingerprint. | Derived from application ID and exact variant identity | A non-empty Gradle `Provider<String>`; both profiles |

#### `generation {}`

| Configuration | Meaning | Default | Range / Profile |
| --- | --- | --- | --- |
| `packageBase` | Base Java/Kotlin package for generated code. Compose uses `<packageBase>.compose`. | `<namespace>.kaleido.generated` | Legal dotted Java package; both |
| `packageCount` | Number of generated ordinary packages. | `4` | `1..64`; both |
| `classesPerPackage` | Ordinary classes generated in each package. | `4` | `1..64`; both |
| `methodsPerClass` | Methods generated in each ordinary class. | `4` | `1..128`; both |
| `layoutCount` | Generated XML layout count. | `8` | `1..256`; both |
| `drawableCount` | Generated XML drawable count. | `16` | `1..512`; both |
| `stringCount` | Generated string resource count. | `32` | `1..4096`; both |
| `activityCount` | Number of inert, non-exported generated Activities. | `0` | `0..64`; nonzero is `FULL` only |

The mandatory ordinary counts cannot be zero.

#### `generation { compose {} }`

| Configuration | Meaning | Default | Range / Profile |
| --- | --- | --- | --- |
| `enabled` | Enables deterministic Compose Runtime-only generation and final-DEX retention proof. | `false` | Boolean; both profiles |
| `fileCount` | Generated Kotlin facade/file count. | `4` | `1..64`; both |
| `functionsPerFile` | Generated `@Composable` functions in each file. | `4` | `1..32`; both |

`fileCount * functionsPerFile` must not exceed `512`. Enabling Compose does not
apply plugins, change compiler settings, or add dependencies for the Consumer
Project.

#### `resources {}`

| Configuration | Meaning | Default | Constraint / Profile |
| --- | --- | --- | --- |
| `nativeLibrariesToDelete` | Deletes selected native-library payloads by exact basename. | Empty | Exact `lib*.so` basenames; `FULL` only |
| `metadataToDelete` | Deletes selected permitted metadata entries. | Empty | Exact `META-INF/INDEX.LIST`, `DEPENDENCIES`, `LICENSE*`, or `NOTICE*`; `FULL` only |
| `replaceUnusedStrings` | Replaces only strings confirmed unused by the supplied file. | `false` | Requires `confirmedUnusedStringsFile`; `FULL` only |
| `confirmedUnusedStringsFile` | UTF-8/LF file containing one exact string name per line. Supplying it enables replacement. | Unset | At least one unique legal string name; `FULL` only |
| `retainedLanguages` | Keeps the selected language resources while filtering other language splits/configurations. | Empty, so no language filtering | Canonical tags such as `en`, `en-US`, or `zh-Hans-CN`; `FULL` only |

Empty selectors perform no destructive operation. Selected operations fail when
they conflict with a Protection Requirement.

#### `protection {}`

| Configuration | Meaning | Default | Constraint |
| --- | --- | --- | --- |
| `originalClassNames` | Exact application class identities whose original names must remain stable. | Empty | Up to 1,024 non-blank exact identities |
| `resourceNames` | Exact application resource entry names that must not be renamed. | Empty | Up to 1,024 non-blank names |
| `packagedPaths` | Exact AAB resource payload paths that must remain stable, with or without a leading `base/`. | Empty | Up to 1,024 non-blank paths |
| `classes("stable-id")` | Declares a bounded class Escape Hatch. | Empty | `exact` or package `prefix`; class dimensions only |
| `resources("stable-id")` | Declares a bounded resource Escape Hatch. | Empty | `exact` or name `prefix`; resource dimensions only |

Escape Hatch dimensions:

| Dimension | Meaning | Applicable block |
| --- | --- | --- |
| `REACHABILITY` | Retains the selected target when runtime behavior requires it to remain reachable. | Class or resource |
| `ORIGINAL_IDENTITY` | Preserves the original class identity through Kaleido and R8 naming. | Class |
| `DESCRIPTOR_CLOSURE` | Extends protection to project-owned types referenced by selected class descriptors. | Class |
| `RUNTIME_ATTRIBUTES` | Retains runtime annotations, signatures, and class-association attributes required by frameworks. | Class |
| `RESOURCE_NAME` | Preserves the selected resource entry name. | Resource |
| `PACKAGED_PATH` | Preserves the selected file-backed resource path in the AAB. | Resource |

#### Signing fields and external conventions

| Field | Meaning |
| --- | --- |
| `keyStoreFile` | Upload keystore file used for the final AAB signature. |
| `storePassword` | Keystore password, preferably supplied by a lazy secret Provider. |
| `keyAlias` | Upload key alias. |
| `keyPassword` | Private-key password, preferably supplied by a lazy secret Provider. |
| `expectedCertificateSha256` | Expected upload certificate SHA-256 fingerprint used to reject the wrong key; use the 64-character hexadecimal digest. |

Equivalent external names:

| Value | Environment variable | Gradle property |
| --- | --- | --- |
| Keystore | `KALEIDO_UPLOAD_KEYSTORE` | `kaleido.uploadSigning.keyStoreFile` |
| Store password | `KALEIDO_UPLOAD_STORE_PASSWORD` | `kaleido.uploadSigning.storePassword` |
| Key alias | `KALEIDO_UPLOAD_KEY_ALIAS` | `kaleido.uploadSigning.keyAlias` |
| Key password | `KALEIDO_UPLOAD_KEY_PASSWORD` | `kaleido.uploadSigning.keyPassword` |
| Certificate SHA-256 | `KALEIDO_UPLOAD_CERTIFICATE_SHA256` | `kaleido.uploadSigning.expectedCertificateSha256` |

### Outputs

A successful Release build publishes the signed AAB at the normal AGP location,
for example:

```text
app/build/outputs/bundle/release/app-release.aab
```

It also publishes the complete Release Evidence Set at:

```text
app/build/reports/kaleido/<variant>/release-evidence-set/
```

Use the composed mapping from the same evidence set to retrace a crash from that
exact AAB. A failed build does not publish a partial or stale success set.

### Common failures

- `KLD-ADOPTION-*`: apply Kaleido after `com.android.application` and only to an
  application module.
- `KLD-TOPOLOGY-*`: declare an exact minified `release` build type and remove
  unsupported final-AAB owners or module topology.
- `KLD-CONFIG-*`: correct the named DSL field, range, profile, or Compose
  prerequisite.
- `KLD-PROTECTION-*`: narrow the selector, use the matching typed dimensions,
  ensure it matches a project-owned target, and provide a review reason.
- `KLD-SIGNING-*`: provide all five fields from one source and verify the expected
  certificate fingerprint.
- `KLD-COMPAT-*`: use an exact Compatibility Matrix row.

Read the first hard diagnostic's `reason` and `repair`, then rerun the same
variant.

### Documentation

- [Adoption, profiles, DSL, and signing](docs/public/adoption.md)
- [Compatibility and performance gates](docs/public/compatibility-and-performance.md)
- [Evidence, diagnostics, mappings, and retrace](docs/public/evidence-and-diagnostics.md)
- [Threat model and security boundaries](docs/public/security-model.md)
- [Upgrade and immutable release policy](docs/public/upgrade-and-release.md)
- [License and provenance](THIRD_PARTY_NOTICES.md)
- [Security reporting](SECURITY.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

---

## 中文

Kaleido 是一个采用 Apache-2.0 许可证的 Gradle 插件，用于对 Android
Release AAB 进行确定性、可审计的构建加固。接入方只需应用一个插件，按需配置一个
`kaleido {}` 块，然后执行原有的 Release Bundle 任务。

Kaleido 在一次构建中统一提供四类能力：

1. 确定性生成 Java/Kotlin 代码、资源和可选的 Manifest 内容；需要显式启用的
   Compose Generator 属于这一类能力。
2. 改写符合条件的应用类名，并同步处理受支持的 Manifest 与 XML 类引用。
3. 在最终 AAB 中混淆和优化资源，同时保留资源 ID 以及声明保护的名称和路径。
4. 生成确定性的 R8 字典，并为同一个 Release 产物发布原始映射与组合映射。

`SAFE` 和 `FULL` 都会执行全部四类能力。`FULL` 只解锁显式声明的 Activity
生成与资源操作，不会因为选择该 Profile 就自动启用这些操作。

Kaleido 的目标是提高分析或篡改 Android Release 的成本。它不承诺应用商店审核
通过、规避审核、绝对不可达，也不保证抵御所有运行时动态加载机制。

### 使用要求

- Android Application 模块，并且必须先应用 `com.android.application`，再应用
  Kaleido。
- 存在名称精确为 `release` 的构建类型，并启用 R8 压缩混淆。
- JDK 17、Build Tools 36.0.0、compileSdk 36。
- 为每个参与构建的 Release Variant 提供一套完整的上传签名配置。
- 启用 Compose 生成时，Consumer Project 必须已经启用 Compose、应用匹配的
  `org.jetbrains.kotlin.plugin.compose` 插件，并在 Release 编译类路径中提供
  Compose Runtime。

Kaleido 只验证精确的兼容性组合，不笼统宣称支持所有 AGP 9 版本。`0.1.0` 的目标
组合如下；是否已经正式支持以及相应证据，请查看
[兼容性矩阵](docs/public/compatibility-and-performance.md)：

| 组合 | Android Gradle Plugin | Gradle |
| --- | --- | --- |
| A3 | 9.2.1 | 9.4.1 |
| A4 | 9.3.2 | 9.5.0 |

MVP 不支持 Dynamic Feature、Asset Pack、AI Pack、test-only、Android Library
和 KMP 目标。

### 安装

在应用模块的 `build.gradle.kts` 中应用插件。顺序不能颠倒，因为 Kaleido 应用时
要求 Android Application 插件已经存在。

```kotlin
plugins {
    id("com.android.application") version "9.2.1"
    id("io.github.ujffdi.kaleido") version "0.1.0"
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

确保 `settings.gradle.kts` 的 `pluginManagement` 可以访问
`gradlePluginPortal()`：

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

### 快速开始：Safe Defaults

无需声明 `kaleido {}` 块。只应用插件时会使用 `SAFE` 和 Safe Defaults v1。
通过受保护的环境变量提供一套完整签名配置即可：

```shell
export KALEIDO_UPLOAD_KEYSTORE=/secure/path/upload.p12
export KALEIDO_UPLOAD_STORE_PASSWORD='store-password'
export KALEIDO_UPLOAD_KEY_ALIAS='upload'
export KALEIDO_UPLOAD_KEY_PASSWORD='key-password'
export KALEIDO_UPLOAD_CERTIFICATE_SHA256='64-character-certificate-sha256'

./gradlew :app:bundleRelease
```

存在产品风味时，仍执行原来的 Variant 任务，例如
`./gradlew :app:bundlePaidRelease`。顶层 Kaleido 配置会应用到每个符合条件的
Release Variant。

Safe Defaults v1 会生成：

- 4 个包，每个包 4 个普通类，每个类 4 个方法；
- 8 个布局、16 个 XML Drawable、32 个字符串；
- 不生成 Activity，Compose Generator 默认关闭；
- 默认包名为 `<android namespace>.kaleido.generated`；
- 未显式配置种子时，根据 application ID 和精确 Variant 身份生成确定性根种子。

### Full 与 Compose 示例

只有在需要某项显式 Full-only 控制时才选择 `FULL`。下面的示例同时启用了可选的
Compose Generator：

```kotlin
kaleido {
    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)
    seed.set(providers.environmentVariable("KALEIDO_ROOT_SEED"))

    generation {
        packageBase.set("com.example.app.generated")
        packageCount.set(8)
        classesPerPackage.set(6)
        methodsPerClass.set(8)
        layoutCount.set(12)
        drawableCount.set(24)
        stringCount.set(48)
        activityCount.set(2)

        compose {
            enabled.set(true)
            fileCount.set(4)
            functionsPerFile.set(4)
        }
    }

    resources {
        nativeLibrariesToDelete.add("libobsolete.so")
        metadataToDelete.add("META-INF/DEPENDENCIES")
        replaceUnusedStrings.set(true)
        confirmedUnusedStringsFile.set(
            layout.projectDirectory.file("kaleido-unused-strings.txt")
        )
        retainedLanguages.addAll("en", "zh-Hans-CN")
    }
}
```

`kaleido-unused-strings.txt` 每行填写一个确认未使用的精确字符串资源名。空行与
以 `#` 开头的行会被忽略，`unused_title` 和 `string/unused_title` 两种格式都可用。
只要配置该文件，即使没有显式设置 `replaceUnusedStrings`，也会启用未使用字符串
替换。

```text
# 已通过应用级分析确认未使用
unused_title
string/obsolete_message
```

Compose Generator 只会在 `build/` 下生成 internal 的 Compose Runtime 函数，
不会生成 UI、Preview、导航、组件、启动入口，也不会引用 Consumer Project 符号。

### Protection 与 Escape Hatch

Kaleido 会自动识别受支持的 Manifest、XML、反射和 JNI 要求。对于无法自动证明
闭包的有界动态行为，使用类型化 Escape Hatch：

```kotlin
kaleido {
    protection {
        originalClassNames.add("com.example.app.RuntimeEntry")
        resourceNames.add("dynamic_icon")
        packagedPaths.add("res/raw/runtime_protocol.bin")

        classes("runtime-entry") {
            exact("com.example.app.RuntimeEntry")
            dimensions.addAll(
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.REACHABILITY,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.ORIGINAL_IDENTITY,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.DESCRIPTOR_CLOSURE,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.RUNTIME_ATTRIBUTES,
            )
            reason.set("运行时按照原始类名加载该入口")
        }

        resources("dynamic-icons") {
            prefix("dynamic_")
            dimensions.addAll(
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.RESOURCE_NAME,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.PACKAGED_PATH,
            )
            reason.set("服务端响应会解析这一有界资源集合")
        }
    }
}
```

Escape Hatch ID 必须在 class/resource 两类配置中全局唯一，由 3..64 个小写字母、
数字、`.`、`_` 或 `-` 组成，并在不同构建间保持稳定。每条声明必须配置一个
`exact(...)` 或有界 `prefix(...)` 选择器、至少一个适用的保护维度和非空审核原因。
全局通配符以及未匹配任何项目自有目标的声明会直接使构建失败。

| Escape Hatch 字段 | 含义 |
| --- | --- |
| `classes("stable-id")` / `resources("stable-id")` | 创建类型化声明；稳定 ID 用于 Plan、诊断与证据记录。 |
| `exact("identity")` | 选择一个精确类身份或资源 Entry 名。 |
| `prefix("bounded-prefix")` | 选择一个有界包或资源名前缀；不允许使用 `*`。 |
| `dimensions` | 声明 Kaleido 必须保留的最小运行时属性集合。 |
| `reason` | 记录非空、可审核的保护原因，最长 512 个字符。 |

### 签名

Kaleido 会签名经过规范化变换的最终 AAB，并验证预期上传证书和完整的 JAR 签名
覆盖。每个签名来源必须原子地提供以下五个字段：

```kotlin
kaleido {
    signing {
        keyStoreFile.set(layout.projectDirectory.file("upload.p12"))
        storePassword.set(providers.environmentVariable("UPLOAD_STORE_PASSWORD"))
        keyAlias.set("upload")
        keyPassword.set(providers.environmentVariable("UPLOAD_KEY_PASSWORD"))
        expectedCertificateSha256.set(
            providers.environmentVariable("UPLOAD_CERTIFICATE_SHA256")
        )
    }
}
```

精确 Variant 可以用一套完整配置覆盖顶层签名来源：

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

签名来源优先级如下：

1. 精确 Variant 的 `signing("variantName")` DSL。
2. 顶层 `signing {}` DSL。
3. 完整的 `KALEIDO_UPLOAD_*` 环境变量约定。
4. 完整的 `kaleido.uploadSigning.*` Gradle Property 约定。

较高优先级来源只要缺少字段就会失败，Kaleido 不会从低优先级来源补齐。密码应通过
受保护的 Provider 或 Secret Store 提供，不应提交到源码仓库。

### DSL 配置说明

#### 顶层配置

| 配置 | 含义 | 默认值 | 允许值 / Profile |
| --- | --- | --- | --- |
| `profile` | 选择接入策略；两个 Profile 都运行完整加固流水线。 | `SAFE` | `SAFE` 或 `FULL` |
| `seed.set(Provider<String>)` | 替换默认根种子；各能力与 Variant 子种子由插件内部生成，报告只记录 SHA-256 指纹。 | 根据 application ID 与精确 Variant 身份生成 | 非空 Gradle `Provider<String>`；两个 Profile |

#### `generation {}`

| 配置 | 含义 | 默认值 | 范围 / Profile |
| --- | --- | --- | --- |
| `packageBase` | 生成代码的 Java/Kotlin 基础包名，Compose 使用 `<packageBase>.compose`。 | `<namespace>.kaleido.generated` | 合法的点分 Java 包名；两个 Profile |
| `packageCount` | 生成普通代码包的数量。 | `4` | `1..64`；两个 Profile |
| `classesPerPackage` | 每个包中生成的普通类数量。 | `4` | `1..64`；两个 Profile |
| `methodsPerClass` | 每个普通类中生成的方法数量。 | `4` | `1..128`；两个 Profile |
| `layoutCount` | 生成的 XML Layout 数量。 | `8` | `1..256`；两个 Profile |
| `drawableCount` | 生成的 XML Drawable 数量。 | `16` | `1..512`；两个 Profile |
| `stringCount` | 生成的字符串资源数量。 | `32` | `1..4096`；两个 Profile |
| `activityCount` | 生成的惰性、非导出 Activity 数量。 | `0` | `0..64`；非零值仅限 `FULL` |

普通生成项的必选数量不能设置为零。

#### `generation { compose {} }`

| 配置 | 含义 | 默认值 | 范围 / Profile |
| --- | --- | --- | --- |
| `enabled` | 启用确定性的 Compose Runtime 代码生成与最终 DEX 保留证明。 | `false` | Boolean；两个 Profile |
| `fileCount` | 生成的 Kotlin 文件/JVM Facade 数量。 | `4` | `1..64`；两个 Profile |
| `functionsPerFile` | 每个文件中生成的 `@Composable` 函数数量。 | `4` | `1..32`；两个 Profile |

`fileCount * functionsPerFile` 不能超过 `512`。启用 Compose Generator 不会替
Consumer Project 应用插件、修改编译器设置或添加依赖。

#### `resources {}`

| 配置 | 含义 | 默认值 | 约束 / Profile |
| --- | --- | --- | --- |
| `nativeLibrariesToDelete` | 按精确文件名删除选中的 Native Library。 | 空 | 精确 `lib*.so` 文件名；仅限 `FULL` |
| `metadataToDelete` | 删除选中的许可范围内的 Metadata。 | 空 | 精确 `META-INF/INDEX.LIST`、`DEPENDENCIES`、`LICENSE*` 或 `NOTICE*`；仅限 `FULL` |
| `replaceUnusedStrings` | 只替换清单中确认未使用的字符串。 | `false` | 必须配置 `confirmedUnusedStringsFile`；仅限 `FULL` |
| `confirmedUnusedStringsFile` | UTF-8/LF 文件，每行一个精确字符串资源名；配置文件本身会启用替换。 | 未配置 | 至少包含一个合法且不重复的资源名；仅限 `FULL` |
| `retainedLanguages` | 保留指定语言资源并过滤其他语言配置/拆分。 | 空，即不进行语言过滤 | 例如 `en`、`en-US`、`zh-Hans-CN`；仅限 `FULL` |

空选择器不会执行破坏性操作；当所选操作与 Protection Requirement 冲突时，构建会
失败。

#### `protection {}`

| 配置 | 含义 | 默认值 | 约束 |
| --- | --- | --- | --- |
| `originalClassNames` | 必须保持原始名称不变的精确应用类身份。 | 空 | 最多 1,024 个非空精确身份 |
| `resourceNames` | 禁止重命名的精确应用资源 Entry 名。 | 空 | 最多 1,024 个非空名称 |
| `packagedPaths` | 必须保持不变的精确 AAB 资源路径，可带或不带 `base/` 前缀。 | 空 | 最多 1,024 个非空路径 |
| `classes("stable-id")` | 声明有界的类 Escape Hatch。 | 空 | `exact` 或包 `prefix`；只能使用类保护维度 |
| `resources("stable-id")` | 声明有界的资源 Escape Hatch。 | 空 | `exact` 或名称 `prefix`；只能使用资源保护维度 |

Escape Hatch 保护维度：

| 维度 | 含义 | 适用配置块 |
| --- | --- | --- |
| `REACHABILITY` | 声明运行时仍需到达该目标，并在处理过程中保留对应目标。 | 类或资源 |
| `ORIGINAL_IDENTITY` | 在 Kaleido 与 R8 命名过程中保留原始类身份。 | 类 |
| `DESCRIPTOR_CLOSURE` | 将保护扩展到所选类描述符引用的项目自有类型。 | 类 |
| `RUNTIME_ATTRIBUTES` | 保留框架运行时所需的注解、泛型签名与类关联属性。 | 类 |
| `RESOURCE_NAME` | 保留选中资源的 Entry 名称。 | 资源 |
| `PACKAGED_PATH` | 保留选中文件型资源在 AAB 中的打包路径。 | 资源 |

#### 签名字段与外部配置

| 字段 | 含义 |
| --- | --- |
| `keyStoreFile` | 用于最终 AAB 签名的上传密钥库文件。 |
| `storePassword` | 密钥库密码，建议通过惰性 Secret Provider 提供。 |
| `keyAlias` | 上传密钥别名。 |
| `keyPassword` | 私钥密码，建议通过惰性 Secret Provider 提供。 |
| `expectedCertificateSha256` | 预期上传证书的 SHA-256 指纹，用于拒绝错误密钥；填写 64 位十六进制摘要。 |

等价的外部配置名称：

| 值 | 环境变量 | Gradle Property |
| --- | --- | --- |
| 密钥库 | `KALEIDO_UPLOAD_KEYSTORE` | `kaleido.uploadSigning.keyStoreFile` |
| 密钥库密码 | `KALEIDO_UPLOAD_STORE_PASSWORD` | `kaleido.uploadSigning.storePassword` |
| 密钥别名 | `KALEIDO_UPLOAD_KEY_ALIAS` | `kaleido.uploadSigning.keyAlias` |
| 私钥密码 | `KALEIDO_UPLOAD_KEY_PASSWORD` | `kaleido.uploadSigning.keyPassword` |
| 证书 SHA-256 | `KALEIDO_UPLOAD_CERTIFICATE_SHA256` | `kaleido.uploadSigning.expectedCertificateSha256` |

### 构建产物

成功的 Release 构建会把已签名 AAB 发布到 AGP 标准位置，例如：

```text
app/build/outputs/bundle/release/app-release.aab
```

完整 Release Evidence Set 位于：

```text
app/build/reports/kaleido/<variant>/release-evidence-set/
```

还原崩溃堆栈时，应使用同一个 Evidence Set 中与该 AAB 精确对应的组合映射。失败的
构建不会发布不完整或过期的成功证据集。

### 常见错误

- `KLD-ADOPTION-*`：确保在 `com.android.application` 之后应用 Kaleido，并且只
  应用到 Application 模块。
- `KLD-TOPOLOGY-*`：声明精确且启用混淆的 `release` 构建类型，并移除不支持的
  AAB 变换所有者或模块拓扑。
- `KLD-CONFIG-*`：根据错误信息修正 DSL 字段、范围、Profile 或 Compose 前置条件。
- `KLD-PROTECTION-*`：缩小选择器范围、使用对应类型的保护维度、确保能匹配项目
  自有目标，并填写审核原因。
- `KLD-SIGNING-*`：由同一个来源提供全部五个字段，并验证预期证书指纹。
- `KLD-COMPAT-*`：改用兼容性矩阵中的精确版本组合。

先阅读第一条硬错误中的 `reason` 与 `repair`，修复后重新执行同一个 Variant。

### 更多文档

- [接入、Profile、DSL 与签名](docs/public/adoption.md)
- [兼容性与性能门禁](docs/public/compatibility-and-performance.md)
- [证据、诊断、映射与 Retrace](docs/public/evidence-and-diagnostics.md)
- [威胁模型与安全边界](docs/public/security-model.md)
- [升级与不可变发布策略](docs/public/upgrade-and-release.md)
- [许可证与来源说明](THIRD_PARTY_NOTICES.md)
- [安全问题报告](SECURITY.md)
- [参与贡献](CONTRIBUTING.md)
- [更新日志](CHANGELOG.md)
