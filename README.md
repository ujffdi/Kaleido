# Kaleido

[简体中文](README.zh-CN.md) | [English](README.md)

Kaleido is an Apache-2.0 Gradle plugin for deterministic, auditable Android
Release AAB hardening. Apply one plugin, optionally configure one `kaleido {}`
block, and keep using the normal Release bundle task.

## What it does

Kaleido runs four capability families in one automatic pipeline:

1. Generates deterministic Kotlin code, XML resources, strings, and optional
   Manifest components. The opt-in Compose Generator belongs to this family.
2. Rewrites eligible application class names and keeps supported Manifest/XML
   class references synchronized.
3. Obfuscates and optimizes final-AAB resources while preserving resource IDs
   and declared protected names and paths.
4. Generates deterministic R8 dictionaries and publishes raw and composed
   mappings for the exact Release artifact.

Both `SAFE` and `FULL` run all four families. `FULL` only unlocks explicitly
selected Activity generation and resource operations; selecting it performs no
deletion or filtering by itself.

Kaleido raises the cost of inspecting or tampering with an Android release. It
does not guarantee store approval, review evasion, or absolute protection
against every runtime loading mechanism.

## Requirements

Kaleido `0.1.0` is developed and tested with:

| Host | Android Gradle Plugin | Gradle | JDK | Build Tools | compileSdk |
| --- | --- | --- | --- | --- | --- |
| macOS arm64 | 9.2.0 | 9.4.1 | 17 | 36.0.0 | 36 |

Additional requirements:

- Apply Kaleido to an Android application module after
  `com.android.application`.
- Declare an exact `release` build type and enable R8 minification.
- Use AGP built-in Kotlin.
- Provide one complete upload-signing source for every Release variant built.
- Compose generation additionally requires an already Compose-enabled Consumer
  Project, `org.jetbrains.kotlin.plugin.compose`, and Compose Runtime on the
  Release compile classpath.

Other hosts and toolchain versions have not been verified yet. They are not
publication gates. Dynamic Feature, Asset Pack, AI Pack, test-only, Android
library, and KMP targets are outside the MVP topology.

## Quick start

### 1. Resolve the plugin

Make the Gradle Plugin Portal available in `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
```

Apply Kaleido after the Android application plugin in the application module's
`build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "9.2.0"
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

### 2. Provide upload signing

The shortest secure path is one complete environment-variable source:

```shell
export KALEIDO_UPLOAD_KEYSTORE=/secure/path/upload.p12
export KALEIDO_UPLOAD_STORE_PASSWORD='store-password'
export KALEIDO_UPLOAD_KEY_ALIAS='upload'
export KALEIDO_UPLOAD_KEY_PASSWORD='key-password'
export KALEIDO_UPLOAD_CERTIFICATE_SHA256='64-character-certificate-sha256'
```

Keep credentials in a protected secret store. Do not commit the keystore or
passwords.

### 3. Build

No `kaleido {}` block is required. Plugin-only adoption selects `SAFE` and Safe
Defaults v1:

```shell
./gradlew :app:bundleRelease
```

For flavors, use the normal exact task, for example
`./gradlew :app:bundlePaidRelease`.

Safe Defaults v1 uses `<namespace>.kaleido.generated`, 4 packages, 4 classes per
package, 4 methods per class, 8 layouts, 16 XML drawables, 32 strings, no
Activity, and no Compose generation.

The executable developer example is
[`samples/kaleido-sample`](samples/kaleido-sample). It builds Kaleido and
baseline AABs from shared Consumer inputs for comparison.

The published [Sample AAB validation report](https://ujffdi.github.io/Kaleido/sample-aab-validation/)
provides concrete before/after evidence, Dexcount metrics, and downloadable
comparison artifacts.

## Complete `kaleido {}` example

The following Kotlin DSL block documents every supported product-intent
parameter. Copy only the controls your project needs. Comments state the
meaning, default, range, and Profile boundary.

```kotlin
kaleido {
    // Adoption policy. Default: SAFE. Allowed: SAFE or FULL.
    // FULL unlocks explicit Activity/resource controls but enables none by itself.
    profile.set(com.tongsr.kaleido.gradle.dsl.KaleidoProfile.FULL)

    // Optional deterministic root seed Provider.
    // Default: derived from application ID and exact variant identity.
    // Reports store only its SHA-256 fingerprint.
    seed.set(providers.environmentVariable("KALEIDO_ROOT_SEED"))

    generation {
        // Base package for generated code.
        // Default: <namespace>.kaleido.generated. Must be a legal dotted package.
        packageBase.set("com.example.app.generated")

        // Generated package count. Default: 4. Range: 1..64.
        packageCount.set(30)

        // Ordinary classes generated per package. Default: 4. Range: 1..64.
        classesPerPackage.set(10)

        // Methods generated per ordinary class. Default: 4. Range: 1..128.
        methodsPerClass.set(10)

        // Generated XML layout count. Default: 8. Range: 1..256.
        layoutCount.set(20)

        // Generated XML drawable count. Default: 16. Range: 1..512.
        drawableCount.set(20)

        // Generated string resource count. Default: 32. Range: 1..4096.
        stringCount.set(50)

        // Inert, non-exported generated Activity count.
        // Default: 0. Range: 0..64. A nonzero value requires FULL.
        activityCount.set(2)

        compose {
            // Enables internal Compose Runtime-only generated functions.
            // Default: false. Available in SAFE and FULL when prerequisites exist.
            enabled.set(true)

            // Generated Kotlin file/JVM facade count. Default: 4. Range: 1..64.
            fileCount.set(4)

            // Generated @Composable functions per file. Default: 4. Range: 1..32.
            // fileCount * functionsPerFile must not exceed 512.
            functionsPerFile.set(4)
        }
    }

    resources {
        // Exact native-library basenames to delete. Default: empty. FULL only.
        // Use lib*.so names without a directory; an empty set deletes nothing.
        nativeLibrariesToDelete.add("libobsolete.so")

        // Exact permitted META-INF entries to delete. Default: empty. FULL only.
        // Permitted families: INDEX.LIST, DEPENDENCIES, LICENSE*, and NOTICE*.
        metadataToDelete.add("META-INF/DEPENDENCIES")

        // Enables replacement of strings proven unused by the file below.
        // Default: false. FULL only. Requires confirmedUnusedStringsFile.
        replaceUnusedStrings.set(true)

        // UTF-8 file containing one exact string name per line.
        // Blank lines and # comments are ignored; configuring the file also enables replacement.
        confirmedUnusedStringsFile.set(
            layout.projectDirectory.file("kaleido-unused-strings.txt")
        )

        // Languages to retain while filtering other language configurations.
        // Default: empty, meaning no filtering. FULL only. Use canonical tags.
        retainedLanguages.addAll("en", "zh-CN")
    }

    protection {
        // Exact application class identities whose original names must remain stable.
        // Default: empty. Maximum: 1,024 nonblank entries.
        originalClassNames.add("com.example.app.RuntimeEntry")

        // Exact application resource entry names that must not be renamed.
        // Default: empty. Maximum: 1,024 nonblank entries.
        resourceNames.add("dynamic_icon")

        // Exact file-backed resource paths that must remain stable in the AAB.
        // Default: empty. Maximum: 1,024 nonblank entries.
        packagedPaths.add("res/layout/protected_screen.xml")

        // Bounded class Escape Hatch. The stable ID must be globally unique.
        classes("runtime-entry") {
            // Select exactly one application class. Use prefix(...) for a bounded package.
            exact("com.example.app.RuntimeEntry")

            // Preserve reachability, original name, project-owned descriptor closure,
            // and runtime annotations/signatures/association attributes.
            dimensions.addAll(
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.REACHABILITY,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.ORIGINAL_IDENTITY,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.DESCRIPTOR_CLOSURE,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.RUNTIME_ATTRIBUTES,
            )

            // Required nonblank review reason. Maximum: 512 characters.
            reason.set("The runtime loads this entry by its exact class name")
        }

        // Bounded resource Escape Hatch with another globally unique stable ID.
        resources("dynamic-icons") {
            // Select a bounded resource-name family. exact(...) selects one resource.
            prefix("dynamic_")

            // Preserve resource entry names and file-backed AAB paths.
            dimensions.addAll(
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.RESOURCE_NAME,
                com.tongsr.kaleido.gradle.dsl.KaleidoProtectionDimension.PACKAGED_PATH,
            )

            // Required nonblank review reason. Maximum: 512 characters.
            reason.set("A server response resolves this bounded resource family")
        }
    }

    // Optional top-level signing source applied to every eligible Release variant.
    // All five fields are atomic: a partial source fails without fallback merging.
    signing {
        // Upload keystore file. Keep it outside source control.
        keyStoreFile.set(layout.projectDirectory.file("upload.p12"))

        // Upload keystore password Provider.
        storePassword.set(providers.environmentVariable("UPLOAD_STORE_PASSWORD"))

        // Upload key alias.
        keyAlias.set("upload")

        // Upload private-key password Provider.
        keyPassword.set(providers.environmentVariable("UPLOAD_KEY_PASSWORD"))

        // Expected upload certificate SHA-256: exactly 64 hexadecimal characters.
        expectedCertificateSha256.set(
            providers.environmentVariable("UPLOAD_CERTIFICATE_SHA256")
        )
    }
}
```

An exact variant may replace the complete top-level signing source. Variant
names are exact—wildcards are not accepted:

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

`kaleido-unused-strings.txt` accepts `unused_title` or
`string/unused_title`, one unique resource name per line:

```text
# Confirmed unused by application-level analysis
unused_title
string/obsolete_message
```

## Profiles

| Capability | `SAFE` | `FULL` |
| --- | --- | --- |
| Ordinary Kotlin/resource generation | Always runs | Always runs |
| Compose Generator | Explicit opt-in | Explicit opt-in |
| Activity generation | Not permitted | Explicit nonzero `activityCount` |
| Native/metadata deletion | Not permitted | Explicit bounded selectors |
| Unused-string replacement | Not permitted | Explicit confirmed-unused file |
| Language filtering | Not permitted | Explicit retained-language set |
| Class/resource/R8 protection | Always runs | Always runs |

## Signing precedence

Kaleido selects one complete source in this order:

1. Exact-variant `signing("variantName")` DSL.
2. Top-level `signing {}` DSL.
3. Complete `KALEIDO_UPLOAD_*` environment variables.
4. Complete `kaleido.uploadSigning.*` Gradle properties.

Equivalent external names:

| Value | Environment variable | Gradle property |
| --- | --- | --- |
| Keystore | `KALEIDO_UPLOAD_KEYSTORE` | `kaleido.uploadSigning.keyStoreFile` |
| Store password | `KALEIDO_UPLOAD_STORE_PASSWORD` | `kaleido.uploadSigning.storePassword` |
| Key alias | `KALEIDO_UPLOAD_KEY_ALIAS` | `kaleido.uploadSigning.keyAlias` |
| Key password | `KALEIDO_UPLOAD_KEY_PASSWORD` | `kaleido.uploadSigning.keyPassword` |
| Certificate SHA-256 | `KALEIDO_UPLOAD_CERTIFICATE_SHA256` | `kaleido.uploadSigning.expectedCertificateSha256` |

A partial higher-precedence source fails. Kaleido never fills its missing fields
from a lower-precedence source.

## Outputs

A successful `release` build publishes:

```text
app/build/outputs/bundle/release/app-release.aab
app/build/reports/kaleido/release/release-evidence-set/
```

The Release Evidence Set contains `artifact-report.txt`, the evidence manifest,
and mappings under `mappings/`. Retrace an obfuscated crash with
`composed-mapping.txt` from the same evidence set as the exact AAB. A failed
build publishes no partial or stale success set.

## Common failures

- `KLD-ADOPTION-*`: apply Kaleido after `com.android.application` and only to an
  application module.
- `KLD-TOPOLOGY-*`: declare a minified exact `release` build type and remove
  unsupported final-AAB owners or module topology.
- `KLD-CONFIG-*`: correct the named field, range, Profile, or Compose
  prerequisite.
- `KLD-PROTECTION-*`: use a bounded selector, matching dimensions, a real
  project-owned target, and a review reason.
- `KLD-SIGNING-*`: provide all five fields from one source and verify the upload
  certificate fingerprint.
- `KLD-COMPAT-*`: use a tested AGP, Gradle, and JDK configuration and follow the
  diagnostic repair.

Read the first hard diagnostic's `reason` and `repair`, fix it, then rerun the
same exact variant.

## More documentation

- [Adoption, profiles, DSL, and signing](docs/public/adoption.md)
- [Evidence, diagnostics, mappings, and retrace](docs/public/evidence-and-diagnostics.md)
- [Threat model and security boundaries](docs/public/security-model.md)
- [Upgrade and immutable release policy](docs/public/upgrade-and-release.md)
- [License and provenance](THIRD_PARTY_NOTICES.md)
- [Security reporting](SECURITY.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)
