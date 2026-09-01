# Transformation algorithm evidence for ticket 17

## Scope

This note records decision-ready primary-source facts for Kaleido's pre-R8 Application-class rename and final-AAB XML/resource rewrite. It deliberately separates format/API facts from the algorithm Kaleido should choose. The already-decided AGP seam remains `ScopedArtifact.CLASSES` with `Scope.PROJECT` before R8, `SingleArtifact.MERGED_MANIFEST` for the merged manifest, and `SingleArtifact.BUNDLE` for dependency-inclusive compiled XML and resources.

## Decision summary

1. Use a **two-pass, closed-world class transform**: inventory and reserve a bijective mapping first, then remap every supported JVM type-bearing surface and write each class at its mapped internal-name path. Do not reinterpret arbitrary `String` constants as types. Parse and rewrite supported Kotlin `@Metadata` structurally; an unsupported metadata version or an unhandled identity-bearing attribute protects the class or fails the Release rather than emitting partially renamed bytecode.
2. Parse AAB manifests and compiled XML as AAPT2 `XmlNode` protobuf messages. Rewrite class names only at a versioned allowlist of semantic element/attribute locations, preserving namespaces, attribute resource IDs, compiled values, ordering, and unrelated strings.
3. Preserve every resource package ID, type ID, entry ID, configuration and value. Resource-name obfuscation changes `Entry.name` and coordinated name-bearing `Reference.name` fields only. File-path obfuscation changes every affected `FileReference.path` and the corresponding module ZIP entry as one atomic mapping.
4. Duplicate-file merge is safe only for **same-module, file-backed `res/` entries with byte-identical payloads and compatible file representation**. Pick one deterministic canonical path, redirect all matching `FileReference.path` values, and remove only the redundant ZIP entries. Resource IDs/names and DEX remain unchanged. Cross-module, `assets/`, `root/`, `lib/`, DEX, metadata, protected-path, unknown-type, and unreferenced-file merging stays out of scope.
5. Build all mappings before writing; canonicalize and collision-check internal names, resource names and ZIP paths; reject many-to-one outputs unless it is an explicitly proven duplicate-resource merge. Serialize protobuf and ZIP output deterministically with fixed schema/toolchain, stable entry order, explicit timestamps and compression settings.
6. `bundletool validate` and `build-apks` are necessary structural/downstream-consumption gates, but neither proves the semantic correctness of Kaleido's class/XML/resource mapping or runtime behavior. Mapping closure and fixture/runtime assertions remain Kaleido-owned.

## 1. Whole-class JVM remapping

### Official format and ASM facts

The JVM class file does not have one isolated "class name" field. Type identity can occur in the class header, field and method descriptors, generic `Signature` attributes, bytecode instructions, exception tables, stack-map frames, annotations and type annotations, bootstrap methods, inner/enclosing-class attributes, module attributes, nest attributes, record components, and permitted-subclass attributes. The JVM specification defines these constant-pool structures and attributes separately ([JVMS 17, class-file format](https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html)).

ASM's `Remapper` has distinct operations for internal names, descriptors, method descriptors, generic signatures, annotation attribute names, fields, methods, record components, packages, modules and invokedynamic/constant-dynamic names. Its `mapValue` recursively handles type-bearing `Type`, `Handle`, and `ConstantDynamic` values; ordinary `String` values are not type-remapped ([ASM 9.10 `Remapper`](https://asm.ow2.io/javadoc/org/objectweb/asm/commons/Remapper.html)). `ClassRemapper` visits annotations, fields, methods, inner/outer classes, modules, nests, records, permitted subclasses and type annotations ([ASM 9.10 `ClassRemapper`](https://asm.ow2.io/javadoc/org/objectweb/asm/commons/ClassRemapper.html)); `MethodRemapper` covers instruction owners/descriptors, stack frames, invokedynamic bootstrap handles/arguments, `ldc` values, local-variable descriptors/signatures and instruction/local/parameter annotations ([ASM 9.10 `MethodRemapper`](https://asm.ow2.io/javadoc/org/objectweb/asm/commons/MethodRemapper.html)).

ASM also documents a material limit: `ClassRemapper` cannot repair dynamically computed strings, type-name-derived hash codes, or some compiler-generated compound strings such as lambda-deserialization material ([ASM 9.10 `ClassRemapper`, limitations](https://asm.ow2.io/javadoc/org/objectweb/asm/commons/ClassRemapper.html)). Therefore an arbitrary UTF-8/string substitution pass is neither complete nor format-safe.

Kotlin stores source-level declarations in the `kotlin.Metadata` annotation. The first-party `kotlin-metadata-jvm` API reads metadata into structured class/file-facade models and writes modified metadata back; Kotlin's documentation explicitly says the resulting annotation must then be written into the class file with a bytecode framework such as ASM. `readStrict` rejects metadata newer than the library understands rather than silently accepting it ([Kotlin Metadata JVM](https://kotlinlang.org/docs/metadata-jvm.html), [`readStrict`](https://kotlinlang.org/api/kotlinx-metadata-jvm/kotlin-metadata-jvm/kotlin.metadata.jvm/-kotlin-class-metadata/-companion/read-strict.html)).

The public AGP class seam remains constrained as follows: `ScopedArtifact.CLASSES` is the class input used for dexing; `Scope.PROJECT` excludes dependencies; `toTransform` supplies jars plus directories and requires the transform to combine them into one output JAR, owning duplicate and `META-INF` merge policy ([AGP `ScopedArtifact`](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/artifact/ScopedArtifact), [`ScopedArtifacts`](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/ScopedArtifacts), [`ScopedArtifactsOperation`](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/ScopedArtifactsOperation), [official `transformAllClasses` recipe](https://github.com/android/gradle-recipes/tree/5e822e2c5e02e3f3ff6ef3ec99dd30eb4b555c27/transformAllClasses)). Java resources/service descriptors are not part of `ScopedArtifact.CLASSES` and must be analyzed through their own protection boundary.

### Kaleido engineering inference

The minimum safe algorithm is:

1. Inventory every Application-module/Kaleido-generated class, its origin and class-file version; parse all type-bearing standard structures, Kotlin metadata version/kind, exact string-reflection/JNI/protection signals, and unknown attributes.
2. Resolve typed Protection Requirements, reserve protected identities, derive all new internal names from the versioned seed policy, and prove a bijection against both transformed and untouched class names before writing bytes.
3. Run `ClassReader -> ClassRemapper -> ClassWriter` with one immutable mapping, and separately parse/remap/write Kotlin metadata. The output entry must be `<mapped-internal-name>.class`.
4. Rescan the output and require reference closure: no old mapped identity may remain in a type-bearing surface, all mapped targets must exist, metadata must parse, and ASM/JVM verification must succeed. Exact protected strings remain unchanged by policy; arbitrary strings remain uninterpreted.

`ClassRemapper` is a strong implementation primitive, not proof of completeness. Non-standard attributes and compiler/runtime conventions need explicit fixtures. If a class contains an identity-bearing surface Kaleido cannot classify or update, the safe result is identity protection or Release failure—not a best-effort rename.

## 2. AAB protobuf XML and `ResourceTable`

### Official format facts

An AAB is a signed ZIP-like publishing artifact organized by module. Each module contains its manifest separately, `resources.pb` describes resources, and module `res/` paths are preserved into generated APKs ([Android App Bundle format](https://developer.android.com/guide/app-bundle/app-bundle-format)). Android also cautions that tools which dynamically modify resource tables can make APKs generated from bundles behave unexpectedly ([About Android App Bundles](https://developer.android.com/guide/app-bundle)).

AAPT2's authoritative protobuf schema defines:

- `ResourceTable -> Package -> Type -> Entry`; the full resource ID is the independent combination `0xPPTTEEEE`, while `Entry.name` is a separate string ([AAPT2 `Resources.proto`, table/package/type/entry](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/Resources.proto)).
- Each `Entry` owns configuration-specific `ConfigValue` records. An `Item` may be a resource `Reference`, string, raw/styled string, `FileReference`, ID or primitive. A `Reference` may carry an ID and/or a name ([AAPT2 `Resources.proto`, values and references](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/Resources.proto#252)).
- `FileReference.path` is the file's path within the eventual APK, normally `res/type-config/entry.ext`, and its type distinguishes PNG, binary XML and protobuf XML ([AAPT2 `FileReference`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/Resources.proto#391)).
- Compiled protobuf XML is an `XmlNode` tree of elements/text. Elements carry namespaces, names, attributes and children; each attribute has raw `value`, optional Android attribute `resource_id`, and optional compiled `Item` ([AAPT2 `XmlNode`/`XmlAttribute`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/Resources.proto#584)).

These structures establish why an ID-preserving rename is possible: changing an `Entry.name` does not itself change its package/type/entry IDs. They also establish why path rewriting cannot be a ZIP-only rename: every `FileReference.path` that targets that file must change with the ZIP entry. Likewise, a name-bearing `Reference.name` can otherwise become stale even when its numeric `Reference.id` is preserved.

### Kaleido engineering inference

Resource-name rewriting should preserve `PackageId`, `TypeId`, `EntryId`, every configuration and every value byte-for-byte except for coordinated name fields. Build the complete original-ID/full-name/new-name table first, then update `Entry.name` and any structurally name-bearing `Reference.name` that resolves to that mapped ID/name. Do not renumber or compact IDs in the MVP. Consequently integer resource IDs embedded in DEX stay valid and DEX need not change.

Compiled-XML class rewriting should parse every in-scope protobuf XML entry, but mutate only recognized semantic sites whose contract says the value is a class identity: selected element names and versioned attributes such as Android component names, navigation destination class names, `tools:context`, and known class-valued framework attributes. Preserve lexical relative-name conventions where the consumer expects them, but resolve against the original package before mapping. Never search-and-replace arbitrary XML text, resource strings, URLs or unrelated attribute values.

After rewriting, reparse every protobuf, prove every changed class reference resolves through the class mapping, prove every resource ID is unchanged, and prove every file reference names an existing unique module entry.

## 3. File-resource path rewriting and byte-identical deduplication

### Official facts

The schema makes a file-backed resource an ordinary resource-table value containing a `FileReference`, so multiple independent resource entries/configurations can legally point at a file path while retaining their own IDs and names ([AAPT2 `Item` and `FileReference`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/Resources.proto#277)). The bundle format keeps module `res/` paths when generating APKs, so a missing/stale path is observable downstream ([Android App Bundle format](https://developer.android.com/guide/app-bundle/app-bundle-format)).

No Android specification cited here promises that equal bytes in different modules, assets, Java resources or native entries are semantically interchangeable. Nor does byte equality prove equality of `FileReference.Type` or of path-coupled runtime behavior.

### Kaleido engineering inference

The bounded dedup operation that leaves DEX and resource IDs unchanged is:

1. Consider only file-backed `res/` entries in the same module after all path protections are resolved.
2. Group by a collision-resistant content digest plus byte length, then confirm byte-for-byte equality; require compatible `FileReference.Type` and any extension/representation invariant used by downstream tools.
3. Select the canonical path by a stable total order (not ZIP encounter order).
4. Redirect every matching `FileReference.path` to that canonical path and remove only the now-unreferenced duplicate ZIP entries.
5. Keep all package/type/entry IDs, entry names, configurations and non-file values unchanged; verify DEX hashes are byte-identical before/after.

Do not merge across modules or outside `res/`; do not merge protected/path-coupled entries, entries with unknown representation, or loose files not completely accounted for by the table. These exclusions are engineering safety boundaries, not guarantees supplied by the protobuf schema.

## 4. Collision, canonicalization and deterministic ZIP output

### Official facts

ZIP stores entry names and per-entry metadata such as modification time, compression method, CRC and sizes. Java's `ZipOutputStream` defaults to DEFLATED output, exposes an explicit compression level, and uses the current time when an entry has no modification time ([JDK `ZipOutputStream`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/zip/ZipOutputStream.html)). Therefore identical logical payloads do not imply identical ZIP bytes unless metadata, method/level, writer/toolchain and entry order are controlled.

### Kaleido engineering inference

Before serialization, normalize internal class names and module-relative ZIP paths into one canonical slash-separated form; reject absolute paths, `.`/`..` traversal, backslashes, empty segments, duplicate normalized paths, illegal resource identifiers, and output names that collide with untouched entries. Name allocation must reserve existing/protected outputs and expand deterministically on collision. Only the explicit duplicate-resource equivalence relation may intentionally collapse multiple old paths to one new path.

For a reproducible unsigned bundle boundary, sort entries by canonical UTF-8 path, use a fixed timestamp and explicit UTF-8 names, set a fixed compression method/level and toolchain version, canonicalize or discard non-semantic ZIP extras/comments, and use deterministic protobuf serialization (or prove stable serialization by repeated-build fixtures). Preserve DEX, native libraries and code-transparency entries byte-for-byte and path-for-path as already required by the Core Pipeline.

## 5. Validation gates and their limits

`bundletool validate` opens the bundle, constructs the bundle model and runs structural/semantic validators for modules, manifests, resources, DEX and related bundle relationships ([bundletool `ValidateBundleCommand`](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/java/com/android/tools/build/bundletool/commands/ValidateBundleCommand.java#L72-L83), [`AppBundleValidator`](https://github.com/google/bundletool/blob/586a43a450712a1067f3d92cf7574dee68226302/src/main/java/com/android/tools/build/bundletool/validation/AppBundleValidator.java#L29-L73)). Android recommends `bundletool build-apks` for locally exercising APK generation from a bundle ([bundletool: generate APK sets](https://developer.android.com/tools/bundletool#generate_apks)).

These checks establish parseability, bundle-model invariants and downstream APK generation. They do **not** know Kaleido's original-to-new class/resource mapping, cannot prove that every semantic XML site was rewritten, cannot prove dynamic resource/reflection/JNI behavior, and do not replace install/runtime fixtures. Kaleido must add its own closure validator plus TestKit fixtures that inspect final DEX/resource IDs, generate APKs for selected device configurations, and exercise Manifest inflation, layout/navigation inflation, resource lookup and deduplicated resources.

## 6. Recommended versioned intermediate artifacts

These are Kaleido design recommendations, not official platform schemas:

- `ClassTransformPlan.v1`: input artifact digests, class origin, original/mapped internal name, class-file/metadata versions, protection dimensions, classified reference sites and mapping-policy version.
- `XmlRewritePlan.v1`: module/ZIP path, protobuf digest, semantic location kind, field path, resolved original identity, mapped identity and lexical rendering rule.
- `ResourceTransformPlan.v1`: numeric resource ID, original/mapped full name, every configuration/file reference, original/mapped path, content digest, protection flags and optional dedup canonical path.
- `BundleEntryPlan.v1`: every input path/digest/method, output path/action, preservation class, collision result and output serialization policy.

Each plan should be immutable, sorted, schema-versioned and hashed before execution. The executor accepts only the plan plus exact artifacts; the validator consumes original artifact, plan, output artifact and emitted mappings. This keeps ASM/protobuf/ZIP mechanics behind deep artifact interfaces and makes unsupported fields fail closed.

## Required implementation spikes

- Compile Java and Kotlin fixtures covering lambdas/string concatenation, annotations, generics, nested/inner classes, nests, records, sealed classes, invokedynamic/constant-dynamic, module metadata and every supported Kotlin metadata kind; then prove reference closure after ASM and R8.
- Pin the exact `kotlin-metadata-jvm` compatibility policy to the supported Kotlin matrix; verify multifile facades/parts, companions, type aliases and newer metadata failure behavior.
- Inspect real AGP-matrix AABs to enumerate every protobuf XML container/path and confirm deterministic parse/serialize behavior, unknown-field preservation and file-reference closure.
- Prove same-module dedup across PNG, nine-patch and protobuf XML fixtures with `bundletool validate`, `build-apks`, APK resource inspection and runtime access. Keep any unproven representation excluded.
- Run repeated unsigned-bundle rewrites across supported JDK/AGP/bundletool versions to determine whether Kaleido can preserve untouched ZIP entry metadata or must canonicalize the entire archive for reproducibility.
