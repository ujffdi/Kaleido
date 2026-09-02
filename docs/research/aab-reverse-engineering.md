# AAB 逆向能力调查：`P4nda0s/reverse-skills` 与可行工具链

> 调查日期：2026-09-01
>
> 范围：只评估对自有、已授权或合法取得样本的静态/动态分析能力。

## 结论

`P4nda0s/reverse-skills` **不直接支持把 `.aab` 作为输入并完成 Android 代码/资源反编译**。在当前提交 `a2baa31c58a3567977188414da68c8c842057152` 中：

- README 列出的 Android 相关能力是运行时 DEX dump 和 Unity IL2CPP 符号提取，未列出 AAB 解析器、`bundletool`、JADX 或 Apktool 工作流。[README](https://github.com/P4nda0s/reverse-skills/blob/a2baa31c58a3567977188414da68c8c842057152/README.md#L7-L20)
- `rev-dex-dumper` 的输入是已在 Android 设备上运行的应用进程，它通过 ADB/`ptrace` 从内存 dump DEX，不读取 AAB。[工作流](https://github.com/P4nda0s/reverse-skills/blob/a2baa31c58a3567977188414da68c8c842057152/skills/rev-dex-dumper/SKILL.md#L22-L50) [限制](https://github.com/P4nda0s/reverse-skills/blob/a2baa31c58a3567977188414da68c8c842057152/skills/rev-dex-dumper/SKILL.md#L64-L71)
- `rev-u3d-dump` 的 Android 步骤明确是解压 APK，再取 `libil2cpp.so` 和 `global-metadata.dat`；它没有定义 AAB 模块/拆分 APK 处理。[Android APK 步骤](https://github.com/P4nda0s/reverse-skills/blob/a2baa31c58a3567977188414da68c8c842057152/skills/rev-u3d-dump/SKILL.md#L45-L71)
- `rev-symbol` / `rev-struct` 分析 IDA 或 IDA-NO-MCP 已导出的反编译文本、符号和内存数据，是后续语义分析能力，不是 Android 包解析器。[`rev-symbol`](https://github.com/P4nda0s/reverse-skills/blob/a2baa31c58a3567977188414da68c8c842057152/skills/rev-symbol/SKILL.md#L10-L57) [`rev-struct`](https://github.com/P4nda0s/reverse-skills/blob/a2baa31c58a3567977188414da68c8c842057152/skills/rev-struct/SKILL.md#L10-L57)

但 **AAB 可以分析**，有两条主要路线：

1. **直接静态分析**：JADX 官方 README 明确将 `.aab` 列为可接受输入，可尝试从其中的 DEX 生成 Java 表达，并解码 Manifest/资源。[JADX 能力与输入列表](https://github.com/skylot/jadx/blob/8f71a06a8754c40430bb2e623c9731afa14ffa90/README.md#L22-L32)
2. **转换为 APK 后分析/运行**：Android 官方 `bundletool build-apks` 能把 AAB 生成 `.apks` APK Set，可生成设备定向的拆分 APK，或者生成较方便的 universal APK。[Android bundletool 文档](https://developer.android.com/tools/bundletool#generate_apks)

## 为什么不应把 AAB 当成普通 APK

Android 官方定义中，AAB 是上传给应用商店的签名发布产物，代码与资源按模块组织；Google Play 会由它生成 base APK、feature APK 和 configuration APK。AAB 中每个模块的 `manifest/`、`dex/`、`res/`、`lib/` 等目录也与 APK 的目录规则有差异。[Android App Bundle 格式](https://developer.android.com/guide/app-bundle/app-bundle-format)

因此，“解压 AAB 后只看 `base/`”或“只分析一个 universal APK”可能漏掉按设备条件分发的代码/资源，也可能漏掉未声明 `dist:fusing include=true` 的动态特性模块；Android 官方对 `--mode=universal` 特别说明了后一个限制。[`--mode=universal` 限制](https://developer.android.com/tools/bundletool#generate_apks)

## 建议的实用管线

### A. 先做快速静态盘点

```bash
# 直接打开；GUI 适合搜索、跳转和初步调查
jadx-gui app.aab

# 或导出到目录
jadx -d out/jadx app.aab
```

这条路线最快，适合盘点包名、Manifest、DEX 类、字符串、资源和模块。JADX 自身明确警告“大多数情况下无法 100% 反编译所有代码”，所以输出只能视为辅助理解用的近似表达，不是原始源码。[JADX 警告](https://github.com/skylot/jadx/blob/8f71a06a8754c40430bb2e623c9731afa14ffa90/README.md#L18-L25)

如果只需要可读的 Manifest、资源列表或 BundleConfig，`bundletool dump` 可以不转 APK 直接读 AAB：

```bash
bundletool dump manifest --bundle=app.aab
bundletool dump manifest --bundle=app.aab --module=feature_name
bundletool dump resources --bundle=app.aab
bundletool dump config --bundle=app.aab
```

这些命令与 `--module` 的适用范围由 bundletool 官方命令源码直接定义。[`DumpCommand.java`](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/java/com/android/tools/build/bundletool/commands/DumpCommand.java)

### B. 需要 Apktool、安装、动态分析或复现 Play 拆分时

```bash
# 广泛但不保证包含所有动态特性的单 APK
bundletool build-apks \
  --bundle=app.aab \
  --output=app-universal.apks \
  --mode=universal

# 完整保留 APK Set 以盘点所有生成产物
bundletool build-apks \
  --bundle=app.aab \
  --output=app-all.apks

# 或使用真实/手写 device spec 生成设备定向 APK Set
bundletool build-apks \
  --bundle=app.aab \
  --output=app-device.apks \
  --device-spec=device.json
```

`.apks` 是 APK Set archive，可解压后对其中的 base/feature/configuration APK 分别分析；也可用 `bundletool install-apks --apks=app-device.apks` 安装正确的拆分组合。Android 官方同时说明：生成 APK 时若不提供签名信息，bundletool 会尝试使用 debug key 签名。[APK Set 生成与安装](https://developer.android.com/tools/bundletool#generate_apks)

然后按目标选工具：

| 目标 | 建议输入/工具 | `reverse-skills` 角色 |
|---|---|---|
| Java/Kotlin 逻辑、调用关系、字符串 | AAB 或拆分 APK → JADX | 无直接必要；由 JADX 解析 DEX |
| Manifest/资源/smali | APK → Apktool，或 AAB → `bundletool dump` | 无直接支持；Apktool 官方定位为 Android **APK** 逆向工具。[Apktool](https://apktool.org/) |
| 本地 `.so` 逻辑 | 从相应 ABI 的 APK/AAB 模块取 `.so` → IDA/Ghidra | `rev-symbol` / `rev-struct` / `rev-idapython` 可辅助分析已加载的本地二进制 |
| Unity IL2CPP | 拆分 APK 中配对的 `libil2cpp.so` + `global-metadata.dat` | 使用 `rev-u3d-dump`，但须先自行完成 AAB → APK/文件配对 |
| 加固/动态加载 DEX | 安装设备适配的 APK Set，运行到真实 DEX 加载后 | `rev-dex-dumper`；可能需要 root，且依赖 ADB/`ptrace` |

### C. 核对完整性

至少应保留并核对：

- AAB 顶层模块清单，以及 base / dynamic-feature 的 Manifest、DEX、resources、assets 和 native libraries。
- `.apks` 中的 base、feature、ABI、density、language 拆分，并明确当前研究对应的 device spec。
- 对 Unity，`libil2cpp.so` 与 `global-metadata.dat` 必须来自同一构建/模块组合；`rev-u3d-dump` 也将“配对错误”列为空输出的原因。[`rev-u3d-dump` troubleshooting](https://github.com/P4nda0s/reverse-skills/blob/a2baa31c58a3567977188414da68c8c842057152/skills/rev-u3d-dump/SKILL.md#L156-L164)

## 限制与不能承诺的结果

- **不等于恢复原始工程**：DEX 反编译丢失注释、部分名称、源码结构和构建配置；资源重建也不保证与原始项目一致。
- **混淆不可逆**：没有原始 R8/ProGuard mapping 时，已被删除的符号名和语义不能被保证还原；只能依据行为推测并标注置信度。
- **静态分析可能看不到真实 DEX**：加固、动态下载或运行时解密的代码需要授权环境下的动态证据；`rev-dex-dumper` 也明确要求等待真实 DEX 加载。
- **本地生成的 APK 可能不等于 Play 交付的 APK**：本地生成时使用的签名、device spec 与动态模块集合都可能不同；签名校验、Play Integrity 或后端绑定可能使运行行为变化。
- **universal APK 不是完整性证明**：它适合快速静态分析/测试，但不能取代对原始 AAB 模块和完整 APK Set 的盘点。

## 最终建议

如果目标是普通 Android AAB，先用 **JADX 直接读 AAB** 完成快速盘点，同时用 **bundletool 保留完整 APK Set** 作为模块/设备维度的证据；只在需要资源/smali、本地库、Unity IL2CPP 或加固 DEX 时，再将对应拆分 APK 交给 Apktool、IDA/Ghidra 或 `reverse-skills` 的特定 skill。

因此，`reverse-skills` 更适合被定位为 **AAB 预处理之后的专项分析辅助层**，而不是端到端 AAB 逆向解决方案。

## 主要一手资料

- [`P4nda0s/reverse-skills` README（固定提交）](https://github.com/P4nda0s/reverse-skills/blob/a2baa31c58a3567977188414da68c8c842057152/README.md)
- [Android Developers: The Android App Bundle format](https://developer.android.com/guide/app-bundle/app-bundle-format)
- [Android Developers: bundletool](https://developer.android.com/tools/bundletool)
- [`google/bundletool` 官方仓库](https://github.com/google/bundletool)
- [`skylot/jadx` README（固定提交）](https://github.com/skylot/jadx/blob/8f71a06a8754c40430bb2e623c9731afa14ffa90/README.md)
- [Apktool 官方文档站](https://apktool.org/)
