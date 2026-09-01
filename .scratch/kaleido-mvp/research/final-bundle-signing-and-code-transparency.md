# Final AAB signing, code transparency, and pre-publication checks

研究日期：2026-08-31

## Scope

本报告只回答三个问题：AGP FinalizeBundleTask 的 code-transparency 阶段究竟签什么；Kaleido 在公开 API 边界内怎样取得重签输入而不泄露凭据；候选 AAB 在原子发布前能完成哪些自动校验。事实锚点是当前工程使用的官方 `com.android.tools.build:gradle:9.2.1` source artifact、Android 官方文档、bundletool 当前源码（提交 `586a43a450712a1067f3d92cf7574dee68226302`）、Gradle 与 JDK 官方文档。

本报告不选择最终 DSL，也不授权实现。

## Decision-ready answer

1. **仅改 `resources.pb`、compiled XML、Manifest 或普通资源，不需要重新生成 code-transparency JWT。** Code transparency 的签名载荷只列出并哈希 feature modules 中 `dex/` 下的 DEX 与 `lib/` 下的 `.so`；Android 官方明确说它不验证 resources、assets、Manifest 或其他非 DEX/`lib/` native-library 文件。但是资源 transform 必须逐字节保留所有被覆盖的 DEX、`.so` 和现有 code-transparency JWT；一旦这些内容或路径变化，就必须重新生成并重新签署 code transparency，不能继续沿用旧 JWT。
2. **任何 post-`SingleArtifact.BUNDLE` 字节变更仍会使 AGP 已生成的上传签名失效，必须重签整个 AAB。** Code transparency 与 AAB 的 JAR 上传签名是两套独立签名；“CT 仍有效”不等于“AAB 仍有有效上传签名”。
3. **AGP 9.2 的公开 Variant API 不提供 resolved signing credentials provider。** Kaleido 不能依赖 AGP 内部 `SigningConfigDataProvider`；要拥有最终 AAB，就必须拥有一组由 Consumer Project 明确配置、惰性 provider-backed 的重签输入，或采用外部签名边界。公开 AGP DSL 中虽然能看到 `storeFile` 和四个 nullable `String`/`File` 字段，但这不是第三方任务可复用的秘密 provider hand-off。
4. **发布门禁必须组合，而不是依赖一个命令：** 完整 JAR 签名与预期上传证书、`bundletool validate`、启用 CT 时的 CT 身份核对，以及至少一次 `bundletool build-apks` 下游生成检查。全部通过后才能把候选文件移动到公共 `SingleArtifact.BUNDLE` 输出位置。

## 1. AGP ordering and what code transparency covers

### Proven facts

AGP 9.2 的 `FinalizeBundleTask` 接收 intermediary bundle、普通 signing config 和可选 code-transparency signing config。它先调用 bundletool `AddTransparencyCommand` 生成带 CT 的临时 bundle，再读取普通 signing config，通过 `AabFlinger` 把结果写成最终 bundle；该输出注册为 `SingleArtifact.BUNDLE`。因此顺序是：

```text
intermediary bundle
  -> add signed code-transparency JWT when configured
  -> apply upload-key JAR signature
  -> public SingleArtifact.BUNDLE
  -> Kaleido post-BUNDLE transform
```

来源：[AGP FinalizeBundleTask](https://android.googlesource.com/platform/tools/base/+/d3f5da7c1a6a20aea90f126e2cea15af08e52ec1/build-system/gradle-core/src/main/java/com/android/build/gradle/internal/tasks/FinalizeBundleTask.kt#121)、[public `SingleArtifact.BUNDLE`](https://android.googlesource.com/platform/tools/base/+/d3f5da7c1a6a20aea90f126e2cea15af08e52ec1/build-system/gradle-api/src/main/java/com/android/build/api/artifact/SingleArtifact.kt#82)。

bundletool 构造 CT metadata 时只扫描 feature modules，筛选条件是 entry path 以模块的 `dex/` 开头，或以 `lib/` 开头且以 `.so` 结尾；它记录 bundle 内路径、类型与 SHA-256。JWT 被放入 `BUNDLE-METADATA/com.android.tools.build.bundletool/code_transparency_signed.jwt`。注入已有外部签名时，bundletool 会在写 bundle 前先验证该签名与当前 code-related files 一致。[CodeTransparencyFactory](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/java/com/android/tools/build/bundletool/transparency/CodeTransparencyFactory.java#L41-L128)、[AddTransparencyCommand](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/java/com/android/tools/build/bundletool/commands/AddTransparencyCommand.java#L323-L375)、[BundleMetadata](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/java/com/android/tools/build/bundletool/model/BundleMetadata.java#L45-L98)。

Android 官方文档给出相同边界：CT JWT 含 DEX 与 native libraries 的哈希；不验证 resources、assets、Manifest 或其他不属于 DEX/`lib/` native libraries 的文件。它还明确说 CT 使用独立于 Play App Signing 的专用密钥；Android OS 安装时也不会验证 CT。[Code transparency for app bundles](https://developer.android.com/guide/app-bundle/code-transparency)

bundletool 的 bundle-mode verifier 会先验证 JWT 签名，再用当前 bundle 重新生成 code-related-file map，并比较路径、类型和哈希；增删改任何被覆盖的 DEX/`.so` 都会失败。[BundleTransparencyCheckUtils](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/java/com/android/tools/build/bundletool/transparency/BundleTransparencyCheckUtils.java#L79-L163)

最后，Android 文档明确提醒：把 CT 文件加入 AAB 会使既有 AAB 签名失效，结果必须再用 upload key 通过 `jarsigner` 重签。这也证明 CT 签名和 AAB JAR 签名是两个独立层次。[Code transparency: adding and resigning](https://developer.android.com/guide/app-bundle/code-transparency#add-code-transparency)

### Kaleido inference

对当前已决定的“只改资源表、compiled XML、Manifest 和普通资源文件”的 bundle rewrite，最小安全策略是：

- 把所有 `*/dex/*.dex`、`*/lib/**/*.so` 与 `BUNDLE-METADATA/com.android.tools.build.bundletool/code_transparency_signed.jwt` 设为禁止改写的保留集合；对每项在 rewrite 前后比较 path 与 SHA-256。
- 若比较相等，保留原 CT JWT，并在最终候选上重新验证即可，不应无意义地重新生成 CT，也不需要接触 CT 私钥。
- 若任一覆盖项的 path 或 bytes 改变，旧 JWT 不再描述新 bundle。此时必须在最终 code 形态上重新生成 CT，并使用 CT 专用密钥重签；如果 Kaleido 没有该明确配置，应 fail closed。
- 无论 CT 是否存在，post-BUNDLE rewrite 都必须移除旧 JAR 签名元数据并对最终所有 entries 重新生成上传签名。

## 2. Provider-backed re-signing boundary

### Proven facts

公开的 `ApplicationVariant.signingConfig` 类型只暴露 `setConfig(...)` 与 v1-v4 signing enablement `Property<Boolean>`；它没有 keystore、alias 或密码 getter。AGP 自己的 `SigningConfigDataProvider`、`SigningConfigProviderParams` 和 `AabFlinger` 位于 `com.android.build.gradle.internal.*`，不属于第三方插件可承诺兼容的公开 API。[ApplicationVariant](https://android.googlesource.com/platform/tools/base/+/d3f5da7c1a6a20aea90f126e2cea15af08e52ec1/build-system/gradle-api/src/main/java/com/android/build/api/variant/ApplicationVariant.kt#57)、[variant SigningConfig](https://android.googlesource.com/platform/tools/base/+/d3f5da7c1a6a20aea90f126e2cea15af08e52ec1/build-system/gradle-api/src/main/java/com/android/build/api/variant/SigningConfig.kt#22)、[FinalizeBundleTask internals](https://android.googlesource.com/platform/tools/base/+/d3f5da7c1a6a20aea90f126e2cea15af08e52ec1/build-system/gradle-core/src/main/java/com/android/build/gradle/internal/tasks/FinalizeBundleTask.kt#70)。

公开 Android DSL `SigningConfig` 的确包含 `File? storeFile` 和 `String? storePassword/keyAlias/keyPassword/storeType`，但它们是普通可变值，不是向第三方 task 暴露的 provider-backed resolved credentials contract。[DSL SigningConfig](https://android.googlesource.com/platform/tools/base/+/d3f5da7c1a6a20aea90f126e2cea15af08e52ec1/build-system/gradle-api/src/main/java/com/android/build/api/dsl/SigningConfig.kt#26)

Gradle 提供惰性的 `ProviderFactory.gradleProperty(...)`、`environmentVariable(...)` 和 `credentials(...)`。官方要求敏感值通过惰性 provider 接到任务并到执行期才解析；Configuration Cache 文档同时警告，可从 task fields 到达的凭据可能进入序列化 task graph，并说明缓存数据虽加密，仍应避免配置期求值。`ProviderFactory.credentials(...)` 当前只原生支持 `PasswordCredentials` 与 `AwsCredentials`，不能单独表达 Android keystore 的 file/type/alias/store password/key password 全部字段。[ProviderFactory](https://docs.gradle.org/current/dsl/org.gradle.api.provider.ProviderFactory.html)、[Configuration Cache and sensitive data](https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:secrets)

Android 官方规定 AAB 用 `jarsigner` 而不是 `apksigner` 签名。JDK 公开 `jdk.security.jarsigner.JarSigner` API 接受 `KeyStore.PrivateKeyEntry`，并能从 `ZipFile` 向 `OutputStream` 写出签名结果；命令行 `jarsigner` 也支持从 `:env` 或 `:file` 读取密码，以及 PKCS#11 protected authentication path。[Android command-line build signing](https://developer.android.com/build/building-cmdline#sign_cmdline)、[JDK JarSigner API](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jartool/jdk/security/jarsigner/JarSigner.html)、[jarsigner options](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jarsigner.html)

AGP 自己的 `FinalizeBundleTask` 标注为 `@DisableCachingByDefault`。这是当前官方实现的事实，但不是 Kaleido 可以调用其内部 signer 的许可。[FinalizeBundleTask](https://android.googlesource.com/platform/tools/base/+/d3f5da7c1a6a20aea90f126e2cea15af08e52ec1/build-system/gradle-core/src/main/java/com/android/build/gradle/internal/tasks/FinalizeBundleTask.kt#66)

### Kaleido inference

可辩护的 MVP 边界是：

- Kaleido DSL 明确接收 keystore file、store type、key alias，以及 store/key password 的 `Provider<String>`；不得从 AGP internal task/provider 反射或窃取这些值。
- 密码 provider 只在 final-sign task 的执行路径解析；不得写入 task output、临时密码文件、日志、异常消息、mapping、Build Scan 自定义值或 Artifact Report。签名任务默认不进入 Build Cache；可缓存边界停在 unsigned deterministic rewrite。
- 优先使用进程内 JDK `JarSigner`：在 task action 中加载 `KeyStore.PrivateKeyEntry`，签到独立候选文件，随后清零临时 `char[]`。这样密码不出现在进程参数、环境转储或工作目录。若使用 CLI，至少用 `-storepass:env`/`-keypass:env` 或受保护 provider；不得把密码字面量放入命令行。
- Artifact Report 只记录非秘密的 signer certificate SHA-256 fingerprint、签名算法、验证结果和配置来源类别，不记录 alias、password、环境变量值或 keystore 内容。

精确的 Kaleido DSL 属性名、Gradle annotation 组合，以及 JKS/PKCS12/PKCS11 的首版支持范围仍是产品/实现决策；一手资料不能替项目作出这些选择。

## 3. Checks before atomic publication

### Proven checks and their limits

| Gate | Proves | Does not prove |
|---|---|---|
| Full JAR verification against expected upload certificate | Every publishable entry is integrity-covered and signer identity matches the expected upload certificate | Play Console will accept the upload; runtime resource semantics |
| `bundletool validate --bundle=<candidate>` | ZIP/module structure、Manifest、resource table、DEX/module relationships and, when CT metadata exists, CT signature/content consistency | AAB JAR upload signature; expected CT certificate identity |
| CT verification against expected public CT certificate | JWT signature identity and equality of current DEX/`.so` path/type/hash set | Resources/Manifest/assets; APK signature |
| `bundletool build-apks --bundle=<candidate> --output=<temp.apks>` | Current bundle can pass bundletool's downstream APK generation path | Installation/runtime behavior on every device; Play-side policy acceptance |

`bundletool validate` opens the ZIP, runs file validators, builds `AppBundle`, then runs semantic validators. Its default validator list includes Android Manifest, resource table, DEX, module/targeting checks and `CodeTransparencyValidator`; if CT is absent that validator is a no-op, and if present but invalid it fails validation.[ValidateBundleCommand](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/java/com/android/tools/build/bundletool/commands/ValidateBundleCommand.java#L72-L83)、[AppBundleValidator](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/java/com/android/tools/build/bundletool/validation/AppBundleValidator.java#L29-L73)、[CodeTransparencyValidator](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/java/com/android/tools/build/bundletool/validation/CodeTransparencyValidator.java#L28-L49)

`bundletool check-transparency --mode=bundle` 可以核对预期 CT 公钥证书并打印结果。不过当前 `CheckTransparencyCommand.execute()` 只打印 `TransparencyCheckResult`，失败分支不抛异常，因此自动化不能未经验证地把 CLI exit code 当作唯一 pass/fail 信号；可使用 `bundletool validate` 保证内容一致性，再对 CT JWT 的 signer certificate fingerprint 做受测的结构化比较，或固定并测试 CLI 输出协议。[CheckTransparencyCommand](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/java/com/android/tools/build/bundletool/commands/CheckTransparencyCommand.java#L213-L321)

JAR 规范说明 `jarsigner` 会签普通文件 entries；Oracle 的 `-strict` 模式把 unsigned entry 作为 code 16 的 severe warning，并把 signer mismatch、证书错误和禁用算法也编码为非零状态。因此 final gate 必须同时验证“所有非签名 entries 有签名”与“证书 fingerprint 是预期值”，而不能只搜索 `jar verified` 文本；对常见自签 upload certificate，也不能在未区分 warning 原因时把任意 strict 非零都笼统当作字节篡改。[JAR file specification](https://docs.oracle.com/en/java/javase/21/docs/specs/jar/jar.html#signed-jar-file)、[jarsigner errors and warnings](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jarsigner.html#errors-and-warnings)

Android 官方建议用 `bundletool build-apks` 在本地测试 Google Play 的 APK 生成路径；未提供 APK signing input 时 bundletool 会尝试使用 debug key。这个 gate 因而可验证 bundle 的下游可消费性，但生成出来的测试 APK signer 与 AAB upload signer 是不同问题。[bundletool: generate APK sets](https://developer.android.com/tools/bundletool#generate_apks)

### Publication inference

Finalize task 应先在目标输出同一 filesystem 的私有临时位置完成：签名、完整读取式 JAR 验证、预期 upload certificate fingerprint、`bundletool validate`、条件式 CT identity/content gate，以及选定的 `build-apks` gate。只有全部成功后，才把候选移动到公共 BUNDLE 路径。

Java `Files.move(..., ATOMIC_MOVE)` 只有在 filesystem 支持时才保证原子移动；跨 filesystem 或不支持的 provider 会失败。Kaleido 若承诺“原子发布”，应让 staging 与 target 同 filesystem，并把不支持原子 move 视为失败，而不是静默回退成 copy/delete。[Files.move](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...))

这些门禁仍不能证明资源重映射的运行时语义、真实设备安装、Google Play 服务端接受或所有 dynamic-feature/device configuration 行为；那些属于后续 APK fixture、设备/测试轨道和发布验收。

## Unknowns and required spikes

- 本报告直接核对了 AGP 9.2.1；AGP 9.0、9.1、9.3 的任务实现与 bundletool 版本是否完全同形，必须由版本矩阵 source check/TestKit fixture 证明，不能从 9.2.1 外推。
- Public `SingleArtifact.BUNDLE` 保证 transformable，但没有公开“把 AGP resolved signing credentials 交给下游 transform”的 contract；如果未来 AGP 增加此 API，应重新评估 Kaleido 自有 signing DSL。
- JDK `JarSigner` 与 AGP `AabFlinger` 在 entry ordering、compression、signature algorithm/default provider 和 reproducibility 上的差异尚未做 fixture。Android 文档认可 `jarsigner` 作为 AAB signer，但不保证两者输出字节相同。
- `ATOMIC_MOVE` 对已存在 Gradle output 的替换语义和所有 MVP 文件系统的支持需要小型 filesystem spike；Java API 不能证明所有 CI/开发机都支持相同行为。
- `bundletool validate`/`build-apks` 证明结构与可生成性，不证明 Kaleido 的 resource mapping 运行时正确。至少要增加带 base/dynamic feature、compiled XML、资源动态查找与重复资源合并的 APK-level fixtures。
- 若以后允许 resource optimization 移动或改写 `.so`、DEX 或任何 code-related path，本报告“保留 CT”结论立即失效，必须重新设计 CT key ownership 与重签流程。
