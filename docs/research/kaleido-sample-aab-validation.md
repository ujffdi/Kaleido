# Kaleido Sample 双 AAB 验证报告

验证日期：2026-09-02<br>
验证环境：macOS arm64、JDK 17、Gradle 9.4.1、Android Build Tools 36.0.0<br>
插件版本：本仓库当前源码发布到隔离 Functional Test Maven 仓库的 `0.1.0-dev`

## 最终结论

| AAB | 定位 | 结论 |
| --- | --- | --- |
| [`baseline-without-plugin.aab`](../../build/reports/kaleido/sample-aab-validation/artifacts/baseline-without-plugin.aab) | 与插件包共享同一份 Sample `src/main`，未应用 Kaleido 插件 | **有效对照（未应用插件）** |
| [`plugin-enabled.aab`](../../build/reports/kaleido/sample-aab-validation/artifacts/plugin-enabled.aab) | 应用 Kaleido `FULL` 配置 | **Kaleido 插件有效** |

结论依据不是文件大小或字符串搜索，而是最终 AAB 的 Manifest、资源表、编译 XML、全部 DEX、ZIP 条目、映射、签名、bundletool APK Set 生成结果和 Release Evidence Set 的交叉验证。四项核心能力和四类 Android 组件的强制检查全部通过。

本报告属于静态与可控构建证据。未安装或启动 APK，因此不声称设备运行、所有运行时路径、所有设备、Google Play 审核或商店接受性已经得到证明。

## 产物身份

| 产物 | SHA-256 | 大小 |
| --- | --- | ---: |
| baseline-without-plugin.aab | `77a2dd9311ed17a886dce489fb41a4899b428fdc8c8a82fd2ef4ca6837087c09` | 约 1.8 MiB |
| plugin-enabled.aab | `935fd1e9fae270af17a90ba23a76bc1cc867a62e0561da7f3583fbb1d9c4c8c8` | 约 3.3 MiB |
| baseline.apks | `17107ba4c7b6e3421ed3260211246af5a994e0c1ba76fd747f520a824974d9dc` | 约 1.3 MiB |
| plugin-enabled.apks | `6314fe23eb2365a8c342a78a5b38fa060e3930eb5fdf4e969c03b63eaff22b9c` | 约 4.6 MiB |

两份 AAB 均通过项目固定的 bundletool 1.18.1 校验，并成功为 `pixel-api-36-arm64.json` 生成 APK Set。baseline AAB 按普通 Android release 默认行为保持未签名；插件 AAB 由 Kaleido 完成发布签名。

## 四项核心能力

| 核心能力 | baseline 观察 | 插件 AAB 观察 | 判定 |
| --- | --- | --- | --- |
| AndroidJunkCode 等价生成 | 无 Kaleido 生成清单或生成组件 | 生成清单记录 1,238 个普通/Activity 类、60,000 个普通方法、50 个 layout、40 个 drawable、50 个 string、38 个 Activity；Compose 为 16 个 facade、512 个函数，最终保留在 2 个 DEX 中 | **通过** |
| XmlClassGuard 等价保护 | Activity、Service、Receiver、Provider、自定义 View 均保持源码类名 | 类映射、Manifest、编译 XML 与 DEX 对同一目标同步改名；声明保护的 `RuntimeProtectedEntry` 保持原描述符并被保留 | **通过** |
| AABResGuard 等价处理 | `libobsolete.so`、`META-INF/DEPENDENCIES`、法语 `locale_probe` 和未使用字符串仍存在 | 删除 `libobsolete.so` 与 `META-INF/DEPENDENCIES`；删除 `confirmed_unused_label`；保留默认/zh-CN 且过滤 fr；重复 drawable 指向同一物理路径；资源名与路径按映射转换，受保护项保持稳定 | **通过** |
| 确定性 R8 处理 | 普通 R8 输出，无 Kaleido Release Evidence Set | raw Kaleido、raw R8、composed mapping 齐全；两次无缓存构建的全部映射、资源映射、确定性证据清单和未签名 AAB 逐字节一致 | **通过** |

Release Evidence Set 的 10 个阶段 `stage.01` 至 `stage.10` 全部为 `PASS`，`diagnostics.count=0`，`publicationResult=PUBLISHED`。对应证据位于 [`second/release-evidence-set`](../../build/reports/kaleido/sample-aab-validation/second/release-evidence-set/)。

## Android 四类组件与 XML 引用闭环

| 类型 | baseline 最终名称 | 插件最终名称 | Manifest / DEX | 判定 |
| --- | --- | --- | --- | --- |
| Activity | `com.tongsr.kaleido.sample.MainActivity` | `com.tongsr.kaleido.sample.k0fdd3c.C0fdd3c6f42` | Manifest 和 DEX 均命中新名称，插件 DEX 中原类描述符不存在 | **通过** |
| Service | `com.tongsr.kaleido.sample.StaticProbeService` | `com.tongsr.kaleido.sample.k74f8f2.C74f8f267e8` | Manifest 和 DEX 同步 | **通过** |
| Receiver | `com.tongsr.kaleido.sample.StaticProbeReceiver` | `com.tongsr.kaleido.sample.kf11dba.Cf11dba09ef` | Manifest 和 DEX 同步 | **通过** |
| Provider | `com.tongsr.kaleido.sample.StaticProbeProvider` | `com.tongsr.kaleido.sample.kd0bf18.Cd0bf18f520` | Manifest 和 DEX 同步；authority 保持业务值不变 | **通过** |
| XML 自定义 View | `com.tongsr.kaleido.sample.StaticProbeView` | `com.tongsr.kaleido.sample.kf77a70.Cf77a70ae59` | `protected_probe_layout.xml` 的编译 XML 与 DEX 同步 | **通过** |

此外，插件生成的 38 个非导出 Activity 全部出现在最终 Manifest 中。上述名称来自 raw/composed mapping，并使用 `aapt2 dump xmltree` 与 `dexdump` 对最终产物反向核对，不依赖源码推断。

## 资源与保护项明细

- `sample_status`：在插件资源表中仍为原名，符合资源名保护声明。
- `protected_probe_layout`：最终资源名和 `base/res/layout/protected_probe_layout.xml` 路径均保持不变，符合名称与打包路径保护声明。
- `RuntimeProtectedEntry`：baseline 中被普通 R8 移除；插件 AAB 中以原始类描述符存在，证明 Kaleido 的可达性与原身份保护实际生效。
- `locale_probe`：baseline 具有 default、fr、zh-CN；插件包仅保留 default、zh-CN。
- `confirmed_unused_label`：baseline 资源表存在；插件资源表不存在。
- `libobsolete.so` 与 `base/root/META-INF/DEPENDENCIES`：只存在于 baseline；`libkeep.so` 在两包中均保留。
- `ic_arrow_left_long` 与其 duplicate、`ic_arrow_up_right_long` 与其 duplicate：资源 ID 仍各自存在，但每对文件引用归并到同一规范化物理路径。

## 生成规模与 Compose 修复

原配置 `44 × 33` 同时违反 Compose Generator 的公开边界（每文件最多 32 个函数、总数最多 512），本次按已确认方案改为 `16 × 32 = 512`。

第一次按 512 规模编译时还暴露了一个真实缺陷：生成器把全部函数串成跨文件、深度 512 的调用链，Compose 编译器在 `ComposerParamTransformer` 中发生 `StackOverflowError`。修复将调用边限制在每个文件内部，保持总量、确定性和闭环无环图不变，同时把最大路径深度限制为 32。新增契约回归测试先稳定失败，修复后通过；原始 60,000 普通方法加 512 Compose 函数的 release 构建随后成功。

这项修复遵循最小行为改动：没有提高编译 JVM 栈、没有降低用户确认的 512 总量，也没有改变公开 DSL。

## 签名、结构与可重复性

- 插件 AAB 的 `jarsigner -verify` 结果为已验证；签名证书 SHA-256 为 `2aab083453565aca922c100e7001a520027b55af50afa05c3ec2e6c2f262c5b6`，与声明的测试上传证书一致。
- `signatureCoverageValidated=true`、`certificateMatched=true`、`bundletoolValidated=true`。
- 两次 `:app:clean :app:bundleRelease --no-build-cache` 的未签名 AAB SHA-256 均为 `1291547ffcbeabf6ffe402dd36c0e6fe5ebeb84eaa93cc73251695efd55e3af7`。
- 两次确定性证据清单 SHA-256 均为 `15f47961a0765135a8de7ef3a4cfa482044405bdf23d5911902ddd2a425768b0`，四份 mapping 文件逐字节一致。
- 两次最终签名 AAB 哈希不同，这是 JAR 签名元数据允许的非确定性边界；Evidence Set 分别记录并绑定了各次签名产物。

## 执行过的验证

1. `:kaleido-gradle-plugin:test`：通过，包含新的 Compose 调用深度回归契约。
2. 干净、关闭 Build Cache 的 baseline + plugin release AAB 联合构建：通过，110 个任务执行成功。
3. 第二次干净、关闭 Build Cache 的 plugin release AAB 构建：通过，验证未签名与确定性证据复现。
4. bundletool 1.18.1：两份 AAB 均通过结构校验，并成功生成固定设备规格 APK Set。
5. `bundletool dump manifest/resources`、`aapt2 dump xmltree`、Build Tools 36.0.0 `dexdump`、ZIP 条目、mapping 和 Evidence Set 交叉检查：通过。
6. `jarsigner -verify`：插件 AAB 完整签名通过；baseline 被确认是未签名对照。
7. `git diff --check`：通过。

## 证明边界

本次刻意未使用 JADX/reverse-skills，也未启动模拟器或真机。AAB 是结构化 ZIP + protobuf + DEX 产物，上述问题可由 bundletool、AAPT2、DEX 工具、映射和插件自有证据完成更直接的验证。若后续要证明启动、资源加载、四类组件实例化或交互行为，应另做设备运行测试；该项不影响本报告对“插件是否真实参与构建并正确改变最终 AAB”的静态结论。
