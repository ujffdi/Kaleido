# Comprehensive Sample

## English

This is Kaleido's single developer-facing Sample App and manual AAB comparison tool.
Both modules use `com.tongsr.kaleido.sample`, the same SDK and R8 configuration, and
the same source tree under `app/src/main`:

- `baseline` does not apply Kaleido.
- `app` applies `io.github.ujffdi.kaleido` with `FULL` and enables every bounded
  generation and Full-only resource-operation family represented by the public DSL.

The shared screen combines an XML layout with a real Material 3 `ComposeView`. It
loads the independently authored arrow vectors, shapes, selectors, color selector,
strings, dimensions, styles, public and kept resources, Night and `zh-CN`
configurations, Raw, XML, Assets, and a protected runtime class. No downloaded PNG,
filename, pixel data, or binary content is part of this project, and the Manifest has
no launcher icon declaration.

The controlled Full-only inputs are deliberately non-production data:

- `libobsolete.so` is deleted while `libkeep.so` remains.
- Application-owned `META-INF/DEPENDENCIES` is deleted.
- `confirmed_unused_label` is listed in `app/unused-strings.txt` and replaced.
- `zh-CN` remains while the French configuration is filtered.

Neither native test payload is loaded by the application.

### Build both AABs

Kaleido owns final AAB signing. Create a disposable key from this directory; never
commit a production key or password:

```shell
mkdir -p build/sample-signing
keytool -genkeypair -alias upload -keyalg RSA -keysize 2048 -validity 30 \
  -dname "CN=Kaleido Comprehensive Sample" -storetype PKCS12 \
  -keystore build/sample-signing/upload.p12 \
  -storepass kaleido-comprehensive-sample \
  -keypass kaleido-comprehensive-sample

export KALEIDO_UPLOAD_KEYSTORE="$PWD/build/sample-signing/upload.p12"
export KALEIDO_UPLOAD_STORE_PASSWORD='kaleido-comprehensive-sample'
export KALEIDO_UPLOAD_KEY_ALIAS='upload'
export KALEIDO_UPLOAD_KEY_PASSWORD='kaleido-comprehensive-sample'
export KALEIDO_UPLOAD_CERTIFICATE_SHA256="$(
  keytool -exportcert -alias upload \
    -keystore "$KALEIDO_UPLOAD_KEYSTORE" \
    -storepass "$KALEIDO_UPLOAD_STORE_PASSWORD" -rfc |
  openssl x509 -outform der |
  shasum -a 256 | awk '{print $1}'
)"

../../gradlew clean :baseline:bundleRelease :app:bundleRelease
```

Successful output is:

- `baseline/build/outputs/bundle/release/baseline-release.aab`
- `app/build/outputs/bundle/release/app-release.aab`
- `app/build/reports/kaleido/release/release-evidence-set/`

Repository release automation may override `samplePluginRepository`,
`sampleAgpVersion`, and `sampleKaleidoVersion` to consume an unpublished packaged
plugin marker.

### Validate and compare

Validate both bundles with the repository-pinned bundletool, then generate APK Sets
and install them one at a time because the application IDs are identical. The screen
must list only PASS checks and Logcat must contain
`KALEIDO_RESOURCE_PROBE_PASS`.

Use the Release Evidence Set as the primary conclusion:

- `release-evidence-set-manifest.properties` contains
  `publicationResult=PUBLISHED`.
- Ordinary Application resources appear in `mappings/resource-mapping.txt`.
- `runtime_label`, `public_label`, `kept_label`, `sample_status`, and the explicitly
  protected class/layout identities remain stable for their declared dimensions.
- The duplicate arrows retain distinct resource IDs. If AAPT2 emits identical
  compiled payloads, their final file references converge on one payload path.
- `base/assets/probe_control.json` retains the baseline path and bytes.
- The baseline retains the controlled Native, Metadata, French, and unused-string
  inputs; Kaleido applies the configured deletion, filtering, and replacement.

Do not require resource IDs to match across the independent baseline and Kaleido
compilations. Validate ID preservation only across Kaleido's own Bundle Rewrite
boundary. Raw AAB size and hash are observations, not proof by themselves.

## 中文

这是 Kaleido 唯一面向开发者的 Sample App，同时也是手动 AAB 对比工具。两个模块
使用相同的 `com.tongsr.kaleido.sample`、SDK、R8 配置，以及 `app/src/main` 下的
同一份源码与资源：

- `baseline` 不应用 Kaleido。
- `app` 应用 `io.github.ujffdi.kaleido`，选择 `FULL`，并覆盖公开 DSL 中边界明确
  的全部生成能力和 Full-only 资源操作。

共享页面同时使用 XML Layout 与真实 Material 3 `ComposeView`，会读取独立绘制的箭头
Vector、Shape、Selector、Color Selector、String、Dimen、Style、public/keep 资源、
Night、`zh-CN`、Raw、XML、Assets 和受保护运行时类。工程不包含下载 PNG 的文件名、
像素或二进制内容，Manifest 也不声明 Launcher Icon。

Full-only 对照输入全部是非生产测试数据：Kaleido 删除 `libobsolete.so` 并保留
`libkeep.so`，删除 Application 自有 `META-INF/DEPENDENCIES`，替换清单中确认未使用
的字符串，保留 `zh-CN` 并过滤法语配置。应用不会加载两个 Native 测试文件。

按照英文命令创建一次性签名环境，然后执行：

```shell
../../gradlew clean :baseline:bundleRelease :app:bundleRelease
```

分别使用固定 bundletool 验证两个 AAB，再依次生成并安装 APK Set。页面必须全部 PASS，
Logcat 必须出现 `KALEIDO_RESOURCE_PROBE_PASS`。最终以 Release Evidence Set、映射、
受保护身份、重复资源 ID/路径、Assets 字节和 Full-only 操作结果作为结论；不要仅凭
AAB 大小或哈希判断插件是否有效，也不要要求两次独立编译产生相同资源 ID。
