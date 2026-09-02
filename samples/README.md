# Kaleido Sample App

## English

[`kaleido-sample`](kaleido-sample) is Kaleido's single developer-facing Sample App.
It is one standalone Gradle project with two Android Application modules that share
the same Kotlin, Manifest, resources, assets, Java resources, and native test inputs:

- `baseline` is an ordinary minified Compose application without Kaleido.
- `app` applies Kaleido with `FULL`, all generation families, Compose Generator,
  Protection Requirements, and the bounded Full-only resource controls.

The project builds both AABs for developer-facing artifact comparison. Focused
plugin behaviors are covered by the plugin's TestKit tests rather than a retained
release-fixture matrix.

## 中文

[`kaleido-sample`](kaleido-sample) 是 Kaleido 唯一面向开发者的 Sample App。它是
一个独立 Gradle 工程，包含两个共享 Kotlin、Manifest、资源、Assets、Java Resources
和 Native 测试输入的 Android Application 模块：

- `baseline` 是不应用 Kaleido 的普通 R8 + Compose 应用。
- `app` 使用 Kaleido `FULL`，覆盖全部生成能力、Compose Generator、Protection
  Requirements，以及边界明确的 Full-only 资源操作。

该工程会同时生成两个 AAB，供开发者对比产物。插件的具体行为由 TestKit 测试覆盖，
不再维护单独的发布验收 Fixture 矩阵。
