# Phase 1 Hardware Utils Native Build, JNI, Loader, and Packaging

## Status and authority

- Parent plan: `docs/plans/hardware-utils-platform-parity-overhaul.md`
- Inherited completed P0 root commit: `03ff2060`
- P1 root branch: `hardware-utils-overhaul/phase-1-native-build`
- Parent blueprint branch: `hardware-utils-overhaul/phase-1-native-build-blueprint`
- Owning module: `euhedral-hardware-utils`
- Blueprint model: `gpt-5.6-sol`
- Blueprint reasoning effort: `max`
- Status: implementation-ready parent contract; developer review and merge into the P1 root are
  required before either child blueprint starts

This blueprint is subordinate to the parent plan, `AGENTS.md`, and the compiled P0 compatibility
contract. It settles the shared P1 architecture and divides implementation into two sequential
responsibility-scoped children. There is no P1 root implementation action.

If a child discovers that a manifest field, JNI ABI rule, staged path, signing edge, loader
catalog field, extraction policy, or binary gate must change, it returns to this parent blueprint.
It must not make a private incompatible choice.

### Developer decision: ad-hoc-only macOS signing

On 2026-08-01, the developer chose ad-hoc signing for all macOS JNI libraries because no Apple
Developer ID certificate is available. Official signing is not required for the JVM to load these
libraries. This deliberately does not provide Apple's notarized, Gatekeeper-recognized distribution
path: users launching a quarantined application bundle may receive a Gatekeeper warning or need to
approve the application explicitly. Revisit this decision if the project later requires notarized,
low-friction distribution outside the Mac App Store.

## Objective

P1 replaces the source-writing native build with one universal, manifest-driven graph and makes
the resulting eight products safe to package and load. Completion must:

1. build every declared Linux, Windows, and macOS product on the default hardware-module
   lifecycle without a host-only or development mode;
2. make one strict JSON manifest the sole native source-root and product inventory;
3. generate target-correct JNI declarations and ABI definitions without modifying a JDK;
4. stage only generated native resources under `target`, sign macOS outputs before staging, and
   package exactly the intended eight products plus manifest-derived loader metadata;
5. select explicit optimized and hardened native settings and enforce architecture, export,
   import, runtime-floor, and signature gates;
6. remove hardcoded loader product tables, reject unknown architectures, support Linux glibc to
   musl fallback on `LinkageError`, and safely own extracted files on POSIX and Windows;
7. preserve ignored source-tree binaries and caches as user-owned data while making them
   impossible to package; and
8. establish selected-module CI and runtime smoke gates without changing platform topology,
   affinity, resource, or pressure semantics owned by P2-P7.

P1 owns the build and load boundary. It does not fix the known Windows native owner mismatch N01
or the macOS return-type mismatch N02; P6 and P7 retain those corrections.

## Scope

### Shared owned surface

The two children together may edit only:

- `euhedral-hardware-utils/pom.xml`;
- tracked native build inputs relocated under `euhedral-hardware-utils/src/main/native/**`;
- the tracked obsolete `euhedral-hardware-utils/src/main/resources/build.sh` and
  `build.zig`;
- hardware internal loader implementation under
  `src/main/java/io/euhedral_execution/hardware_utils/internal/**`;
- hardware compatibility, manifest, loader, packaging, binary-inspection, and smoke tests;
- module-local build documentation directly made stale by the change;
- `mise.toml`, limited to pinning and exposing the native tool/SDK inputs used by this module;
- the invalid JNI-header preparation portions of `.github/workflows/build.yaml` and
  `.github/workflows/deploy.yaml`;
- one new hardware-specific selected-module workflow;
- the two child blueprints, completion records, validations, audits, root integration validation,
  and this phase's closeout material; and
- the temporary P1 status block in `AGENTS.md` during implementation through audit.

The existing root workflow and deploy workflow Maven commands remain byte-for-byte unchanged.
Their full-reactor results are not P1 evidence.

### Read-only inputs

- P0 blueprint/completion, validation, audit, fixtures, and compiled compatibility contract;
- the existing Java native declarations in the seven native-owning classes;
- native code while a child is not its owner;
- root Maven plugin management and release-profile semantics;
- non-training downstream consumers named by the parent plan; and
- platform implementation code except for include-path or declaration-only edits required by the
  selected JNI build contract.

### Prohibited work

- Any inspection, edit, build, test, documentation, or command under `euhedral-training`.
- A root reactor command introduced for P1 or any P1 command that selects training.
- A root POM/plugin change.
- Topology, affinity, resource-provider, pressure, timer, or executor behavior changes.
- Fixing N01 or N02 before P6/P7, or silently accepting either as correct.
- Adding a host-only, development, selectively packaged, or unsigned default mode.
- Deleting, moving, rewriting, or cleaning ignored `src/main/resources/bin/**` or source-local
  `.zig-cache/**`.
- Editing generated native binaries or generated headers by hand.
- Broad workflow permission, checkout, deployment, or unrelated build cleanup.
- A performance claim based only on build wall time.

## Blueprint evidence

### Current build and package behavior

At blueprint time:

- `build.zig` is under `src/main/resources`, scans platform folders shallowly, hardcodes targets,
  silently skips missing folders, discovers `JAVA_HOME` through the environment or `mise`, scans
  SDK paths, and installs into its source-tree prefix.
- the hardware POM runs `zig build ... --prefix .` during `initialize`;
- source resources exclude some build extensions but do not exclude `bin/**` or headers;
- `src/main/resources/bin` contains 22 ignored binaries: the eight intended aggregates plus 14
  stale per-source products;
- a current `target/classes` inventory contains those 22 binaries and three native headers;
- no ignored binary or `.zig-cache` entry is tracked by Git; and
- the tracked `build.sh` independently writes per-source outputs under source resources.

The parent plan's earlier phrase "one-time version-controlled removal" does not describe the
observed binaries. They are ignored, user-owned artifacts. P1 removes only the tracked obsolete
script and relocates tracked source/build inputs. It excludes and fingerprints ignored artifacts
but never deletes or moves them.

### JNI and loader behavior

There are seven Java classes with native declarations:

```text
io.euhedral_execution.hardware_utils.linux.LinuxAffinity
io.euhedral_execution.hardware_utils.osx.OSXAffinity
io.euhedral_execution.hardware_utils.osx.OSXResources
io.euhedral_execution.hardware_utils.osx.OSXSystemLayout
io.euhedral_execution.hardware_utils.windows.WindowsAffinity
io.euhedral_execution.hardware_utils.windows.WindowsResources
io.euhedral_execution.hardware_utils.windows.WindowsSystemLayout
```

The current native surface includes these deliberate P1 exceptions:

- N01: Java declares
  `Java_io_euhedral_1execution_hardware_1utils_windows_WindowsAffinity_ntSetTimerResolution`,
  while the binary exports the `WindowsTimerResolution` owner;
- N02: Java declares `OSXSystemLayout.getSysctlString(String)` with `jint` return, while native
  code returns `jstring`; and
- an otherwise unreferenced legacy macOS export
  `Java_io_euhedral_1execution_hardware_1utils_osx_OSXResources_getCoreTypeMask`.

N01 and N02 remain exact gate exceptions. The legacy macOS export remains an explicit B06/P7
carry, not an open wildcard. All other JNI exports must match generated declarations.

P0 defect-ledger subjects that name the old resource build path remain historical branch-point
anchors and are not rewritten merely because P1 relocates tracked inputs. Child A changes the P0
native source scan root and adds P1 evidence; it does not regenerate the P0 API/native fixture or
change its approved hash.

`JNIClassLoader` currently maintains a second hardcoded product table, maps any non-x86
architecture to arm64, applies POSIX permissions on every OS, catches `Exception` but not
`LinkageError`, creates an unowned top-level temporary file, and registers a cleaner against a
class object that remains live. P1 replaces those behaviors without changing the public
`JNIClassLoader.load()` trigger.

### Pinned-tool observations

The blueprint inspected the installed Zig 0.16.0 standard library and `zig build --help`. The
selected design uses the observed 0.16 APIs (`addLibrary`, `createModule`, `addSystemCommand`,
`addWriteFiles`, `Run.addFileArg`, `Run.addOutputFileArg`, explicit cache directories, and
install-step dependencies). A child must recheck those exact installed APIs before editing
`build.zig`; it must not substitute recalled pre-0.16 syntax.

The installed tool inputs used for the probes were:

```text
Zig:       /home/bagotay/.local/share/mise/installs/zig/0.16.0/zig
JDK:       /home/bagotay/.local/share/mise/installs/java/21
macOS SDK: /home/bagotay/.local/share/mise/installs/macos-sdk/MacOSX26.1.sdk
rcodesign: /home/bagotay/.local/share/mise/installs/apple-codesign/0.29.0/bin/rcodesign
```

All three installed JDK `jni_md.h` files had the same SHA-256 digest because the existing
workflow had copied the Linux header into the Darwin and Win32 locations. P1 must never consume
those platform subdirectories.

### Linux libc-neutral disposition

A direct Zig 0.16 compile probe targeting `x86_64-linux-none` failed before compilation with a
diagnostic beginning:

```text
error: unable to provide libc for target
```

The existing Linux source includes and calls libc-facing scheduling, process-control, allocation,
and syscall wrappers. Removing that dependency requires the direct-syscall portability work and
kernel-floor proof owned by P5. P1 therefore records the required failed neutrality attempt and
settles the parent-plan fallback now:

- glibc products target glibc 2.17;
- musl products remain separate fallback products; and
- P1 does not raise the Linux floor or claim libc neutrality.

### Current binary observations

`llvm-readobj` showed:

- glibc binaries depend only on `libc.so.6`, with observed imported GLIBC versions no newer than
  2.17;
- musl binaries depend on `libc.so` and carry no GLIBC versions;
- PE machine types are correct but the DLLs export CRT symbols and import Kernel32 plus UCRT
  runtime, stdio, and heap APIs;
- Mach-O deployment targets are macOS 11; their true load dependencies include CoreFoundation,
  libSystem, and libobjc, while their separate `LC_ID_DYLIB` is
  `@rpath/libosx_jni_<arch>.dylib` and does not match the packaged filename; and
- none of the current products exports `JNI_OnLoad` or contains a code signature.

These observations are evidence, not an allowed-import baseline.

## Selected architecture

### Data flow

```text
Java native declarations
        |
        +--> javac -h --> target/generated-jni/declarations/*.h
        |
JAVA_HOME/include/jni.h --copy--> target/generated-jni/include/jni.h
project jni_md.h -------copy----> target/generated-jni/include/jni_md.h

src/main/native/native-products.json
        |
        +--> strict Zig validation
        |       |
        |       +--> deterministic recursive source inventory
        |       +--> eight independent compile products
        |       +--> per-product macOS sign -> verify edges
        |       +--> target/generated-resources/native/bin/**
        |       +--> target/generated-resources/native/META-INF/euhedral/
        |              native-products.tsv
        |
        +--> manifest/catalog conformance tests

target generated resources --exact copy--> target/classes
        |
        +--> module jar
                |
                +--> package and binary integration gates
                +--> runtime catalog parser
                         |
                         +--> ordered candidate extraction
                         +--> System.load
                         +--> bounded cleanup
```

The JSON manifest is the only checked-in product and source-root inventory. The TSV is generated
from it and is the only runtime loader inventory. Java may contain generic schema validation and
selection logic, but no product path, architecture alias, libc fallback, filename, or product
ordering table.

## Native source layout

Relocate tracked native inputs to:

```text
euhedral-hardware-utils/src/main/native/
|-- build.zig
|-- native-products.json
|-- include/
|   `-- jni_md.h
|-- common/
|   `-- jni_onload.cpp
|-- linux/
|   |-- linux_jni.h
|   `-- linux_affinity.cpp
|-- macos/
|   |-- osx_jni.h
|   |-- osx_affinity.cpp
|   |-- osx_resources.cpp
|   `-- osx_system_layout.cpp
`-- windows/
    |-- windows_jni.h
    |-- windows_affinity.cpp
    |-- windows_resources.cpp
    `-- windows_system_layout.cpp
```

The directory name is `macos`; compatibility resource paths and output stems retain `osx`.
Platform umbrella headers become OS-support headers that include target-local `jni.h`. They must
not reproduce Java declarations by hand. Translation units include their generated class header
where N01/N02 permit it. The two known mismatches are isolated and checked as explicit exceptions
rather than made to conflict at compile time.

`common/jni_onload.cpp` exports exactly `JNI_OnLoad(JavaVM *, void *)`, rejects no compatible VM
state, performs no platform initialization, allocates nothing, and returns `JNI_VERSION_1_8`.
All eight products compile that source.

The tracked source-resource `build.sh` is deleted after the new direct graph and module lifecycle
pass. No replacement shell script is added.

## Manifest contract

### File and parser rules

`src/main/native/native-products.json` is UTF-8 without a BOM, uses LF, ends with one newline, and
is at most 1,048,576 bytes. Schema version 1 is strict:

- unknown keys, duplicate object keys, missing keys, nulls, and wrong JSON types fail;
- nesting depth is at most 16;
- IDs and enum-like values match `[a-z][a-z0-9-]{0,63}`;
- OS alias values match `[a-z0-9-]+( [a-z0-9-]+)*`, architecture aliases match
  `[a-z0-9_-]+`, and exact/prefix OS rules may not overlap ambiguously;
- signing identifiers match `[A-Za-z0-9.-]{1,255}` and contain no empty dot component;
- paths contain only ASCII letters, digits, `.`, `_`, `-`, and `/`;
- all paths are relative, normalized with `/`, contain no empty, `.` or `..` component, and stay
  below the native root;
- arrays that are semantic sets contain no duplicate;
- source roots exist, are directories, are readable, and contain at least one compiled source;
- symlinks are not followed and any symlink below a designated root fails;
- overlapping roots may not cause the same normalized canonical file to appear twice;
- only regular files with a compiled or passive extension may occur below a designated root;
- product IDs, target tuples, resource paths, build orders, and component/architecture/libc
  combinations are unique;
- a product's output filename is derived from its component stem, architecture ID, and extension
  and must equal the last component of `resourcePath`;
- `glibc`/`musl` are allowed only for Linux; every Linux product requires one of them and every
  non-Linux product requires `none`;
- every component, architecture, gate policy, and OS referenced by a product exists;
- load order is positive and unique within an OS/architecture pair; and
- the product array is nonempty and internally consistent. The parser is generic; a separate
  active-tree compatibility test, not build-graph logic, requires the checked-in manifest to
  equal the eight P0 product paths.

Zig performs a duplicate-key scan before typed `std.json` parsing. It reports the JSON path and
byte offset for syntax/duplicate failures and the product/component ID for semantic failures.

### Exact schema example

The implementation uses this field set and value inventory. Formatting may be normalized by the
implementation, but no field or semantic value is left to a child to invent.

```json
{
  "schemaVersion": 1,
  "sourceRules": {
    "compiledExtensions": [".c", ".cc", ".cpp", ".cxx"],
    "passiveExtensions": [".h", ".hh", ".hpp", ".hxx", ".inc"],
    "recursive": true,
    "followSymlinks": false
  },
  "operatingSystems": [
    {
      "id": "linux",
      "runtimeAliases": [
        {"match": "exact", "value": "linux"}
      ]
    },
    {
      "id": "macos",
      "runtimeAliases": [
        {"match": "exact", "value": "darwin"},
        {"match": "exact", "value": "mac os x"},
        {"match": "exact", "value": "macos"}
      ]
    },
    {
      "id": "windows",
      "runtimeAliases": [
        {"match": "prefix", "value": "windows"}
      ]
    }
  ],
  "architectures": [
    {
      "id": "x64",
      "zig": "x86_64",
      "runtimeAliases": ["amd64", "x86-64", "x86_64"],
      "elfMachine": "EM_X86_64",
      "peMachine": "IMAGE_FILE_MACHINE_AMD64",
      "machoCpu": "X86_64"
    },
    {
      "id": "arm64",
      "zig": "aarch64",
      "runtimeAliases": ["aarch64", "arm64"],
      "elfMachine": "EM_AARCH64",
      "peMachine": "IMAGE_FILE_MACHINE_ARM64",
      "machoCpu": "ARM64"
    }
  ],
  "components": [
    {
      "id": "linux-jni",
      "os": "linux",
      "sourceRoots": ["common", "linux"],
      "outputStem": "linux_jni",
      "extension": "so"
    },
    {
      "id": "macos-jni",
      "os": "macos",
      "sourceRoots": ["common", "macos"],
      "outputStem": "osx_jni",
      "extension": "dylib"
    },
    {
      "id": "windows-jni",
      "os": "windows",
      "sourceRoots": ["common", "windows"],
      "outputStem": "windows_jni",
      "extension": "dll"
    }
  ],
  "gatePolicies": [
    {
      "id": "linux-glibc",
      "format": "elf",
      "allowedLibraries": ["libc.so.6"],
      "forbiddenLibraryFragments": ["libc++", "libgcc_s", "libstdc++"],
      "maximumSymbolVersion": "GLIBC_2.17"
    },
    {
      "id": "linux-musl",
      "format": "elf",
      "allowedLibraries": ["libc.so"],
      "forbiddenLibraryFragments": ["libc++", "libgcc_s", "libstdc++"],
      "maximumSymbolVersion": "none"
    },
    {
      "id": "macos-11",
      "format": "macho",
      "allowedLibraries": ["/usr/lib/libSystem.B.dylib"],
      "forbiddenLibraryFragments": ["CoreFoundation", "libc++", "libobjc"],
      "installNameTemplate": "@rpath/{outputFilename}",
      "minimumDeploymentTarget": "11.0"
    },
    {
      "id": "windows-10",
      "format": "pe",
      "allowedLibraries": [
        "KERNEL32.dll",
        "api-ms-win-crt-heap-l1-1-0.dll",
        "api-ms-win-crt-runtime-l1-1-0.dll"
      ],
      "forbiddenLibraryFragments": ["libc++", "libgcc", "libstdc++", "stdio"],
      "minimumRuntime": "windows-10"
    }
  ],
  "products": [
    {
      "id": "linux-glibc-x64",
      "component": "linux-jni",
      "architecture": "x64",
      "libc": "glibc",
      "zigTarget": "x86_64-linux-gnu.2.17",
      "gatePolicy": "linux-glibc",
      "resourcePath": "bin/linux/glibc/linux_jni_x64.so",
      "buildOrder": 10,
      "loadOrder": 10
    },
    {
      "id": "linux-glibc-arm64",
      "component": "linux-jni",
      "architecture": "arm64",
      "libc": "glibc",
      "zigTarget": "aarch64-linux-gnu.2.17",
      "gatePolicy": "linux-glibc",
      "resourcePath": "bin/linux/glibc/linux_jni_arm64.so",
      "buildOrder": 20,
      "loadOrder": 10
    },
    {
      "id": "linux-musl-x64",
      "component": "linux-jni",
      "architecture": "x64",
      "libc": "musl",
      "zigTarget": "x86_64-linux-musl",
      "gatePolicy": "linux-musl",
      "resourcePath": "bin/linux/musl/linux_jni_x64.so",
      "buildOrder": 30,
      "loadOrder": 20
    },
    {
      "id": "linux-musl-arm64",
      "component": "linux-jni",
      "architecture": "arm64",
      "libc": "musl",
      "zigTarget": "aarch64-linux-musl",
      "gatePolicy": "linux-musl",
      "resourcePath": "bin/linux/musl/linux_jni_arm64.so",
      "buildOrder": 40,
      "loadOrder": 20
    },
    {
      "id": "macos-x64",
      "component": "macos-jni",
      "architecture": "x64",
      "libc": "none",
      "zigTarget": "x86_64-macos.11.0",
      "gatePolicy": "macos-11",
      "resourcePath": "bin/osx/osx_jni_x64.dylib",
      "buildOrder": 50,
      "loadOrder": 10,
      "signingIdentifier": "io.euhedral.execution.hardware-utils.osx-jni-x64"
    },
    {
      "id": "macos-arm64",
      "component": "macos-jni",
      "architecture": "arm64",
      "libc": "none",
      "zigTarget": "aarch64-macos.11.0",
      "gatePolicy": "macos-11",
      "resourcePath": "bin/osx/osx_jni_arm64.dylib",
      "buildOrder": 60,
      "loadOrder": 10,
      "signingIdentifier": "io.euhedral.execution.hardware-utils.osx-jni-arm64"
    },
    {
      "id": "windows-x64",
      "component": "windows-jni",
      "architecture": "x64",
      "libc": "none",
      "zigTarget": "x86_64-windows-gnu",
      "gatePolicy": "windows-10",
      "resourcePath": "bin/windows/windows_jni_x64.dll",
      "buildOrder": 70,
      "loadOrder": 10
    },
    {
      "id": "windows-arm64",
      "component": "windows-jni",
      "architecture": "arm64",
      "libc": "none",
      "zigTarget": "aarch64-windows-gnu",
      "gatePolicy": "windows-10",
      "resourcePath": "bin/windows/windows_jni_arm64.dll",
      "buildOrder": 80,
      "loadOrder": 10
    }
  ]
}
```

`libc: "none"` is an explicit enum value for non-Linux products, not JSON null. Only macOS products
may contain `signingIdentifier`, and it is required for them.

### Source discovery

For each component, walk every `sourceRoot` recursively without following symlinks. Normalize each
path relative to the native root, validate it, and sort compiled sources by unsigned UTF-8 byte
order. Passive files are validated but not compiled. Missing, empty, unreadable, duplicate, or
unrecognized content is an error; there is no `continue` that drops a component or product.

Every product receives a fresh immutable source list. Product compile steps share source
`LazyPath` inputs but no mutable output directory. Products are declared in `buildOrder`, while
Zig is free to schedule independent nodes concurrently.

Adding a source file beneath an existing designated root requires no build-graph edit. Adding a
new root, component, or product changes only the manifest, subject to schema and active-tree P0
product-set compatibility review.

## Generated JNI contract

### Header generation

The module compiler runs `javac -h` during its ordinary `compile` phase and writes the seven exact
class declaration headers under:

```text
target/generated-jni/declarations
```

The exact filenames are:

```text
io_euhedral_execution_hardware_utils_linux_LinuxAffinity.h
io_euhedral_execution_hardware_utils_osx_OSXAffinity.h
io_euhedral_execution_hardware_utils_osx_OSXResources.h
io_euhedral_execution_hardware_utils_osx_OSXSystemLayout.h
io_euhedral_execution_hardware_utils_windows_WindowsAffinity.h
io_euhedral_execution_hardware_utils_windows_WindowsResources.h
io_euhedral_execution_hardware_utils_windows_WindowsSystemLayout.h
```

The Zig graph requires those seven files and fails on a missing or unexpected generated header.
Generated declarations are target-only inputs; no `.h` file is copied to `target/classes` or a
jar.

The host JDK's target-independent `${java.home}/include/jni.h` is copied by a Zig `WriteFiles` node
to:

```text
target/generated-jni/include/jni.h
```

The checked-in `src/main/native/include/jni_md.h` is copied next to it. Because `jni.h` includes
`jni_md.h` with quotes, this adjacent target-local pair prevents use of the corrupted JDK
platform subdirectories. The source JDK is read-only.

### Target-aware `jni_md.h`

The project-owned header selects only on compiler target macros:

- `_WIN32`: `JNIEXPORT` is `__declspec(dllexport)`, `JNIIMPORT` is
  `__declspec(dllimport)`, `JNICALL` is `__stdcall`, `jint` is a 32-bit Windows `long`, and
  `jlong` is a 64-bit integer;
- non-Windows: `JNIEXPORT` has default visibility, `JNIIMPORT` and `JNICALL` are empty, `jint` is
  a 32-bit `int`, and `jlong` is a 64-bit `long` on the supported LP64 targets; and
- `jbyte` is signed and exactly 8 bits on every target.

Compile-time assertions require:

```text
CHAR_BIT == 8
sizeof(jbyte) == 1
sizeof(jint) == 4
sizeof(jlong) == 8
sizeof(void *) == 8
```

Unknown targets fail preprocessing. There is no host-OS branch.

### Declaration and export comparison

Tests derive expected short JNI names from the generated headers and compare them with each binary.
The allowed externally visible export set is:

1. `JNI_OnLoad`;
2. generated declarations belonging to that product's OS; and
3. only the exact observed N01 replacement symbol, N02 symbol, and legacy macOS
   `getCoreTypeMask` carry described above.

The expected N01 symbol remains missing and the observed wrong-owner symbol remains present until
P6. N02's return descriptor remains classified unverified until P7 because a native symbol does
not encode its return type. No prefix or count wildcard is allowed. PE CRT exports are forbidden.

## Zig graph and build policy

### Required explicit inputs

`build.zig` accepts and validates these inputs:

```text
-Djava-home=<absolute JDK home>
-Dgenerated-jni=<absolute target/generated-jni directory>
-Doutput-root=<absolute target/generated-resources/native directory>
-Dmacos-sdk=<absolute .sdk directory>
-Drcodesign=<absolute rcodesign executable>
```

Maven supplies all non-secret paths. The module's default `mise exec -- mvn ...` build needs no
product-selection or host-mode property. A direct Zig invocation must provide the explicit paths.
The graph never reads `JAVA_HOME`, runs `mise`, searches PATH for a signer, or scans for an SDK.

`mise.toml` pins `apple-codesign = "0.29.0"` and exposes direct inputs:

```text
SDKROOT = "{{env.HOME}}/.local/share/mise/installs/macos-sdk/MacOSX26.1.sdk"
RCODESIGN = "{{env.HOME}}/.local/share/mise/installs/apple-codesign/0.29.0/bin/rcodesign"
ZIG = "{{env.HOME}}/.local/share/mise/installs/zig/0.16.0/zig"
LLVM_READOBJ = "/usr/bin/llvm-readobj"
LLVM_OBJDUMP = "/usr/bin/llvm-objdump"
```

CI extracts the SDK with its `MacOSX26.1.sdk` top-level directory intact so the same `SDKROOT`
contract applies locally and in CI.

The hardware POM maps:

```text
java-home              = ${java.home}
generated-jni          = ${project.build.directory}/generated-jni
output-root            = ${project.build.directory}/generated-resources/native
macos-sdk              = ${env.SDKROOT}
rcodesign              = ${env.RCODESIGN}
```

The exec plugin executable is `${env.ZIG}` and must be an absolute executable reporting 0.16.0.

There are no certificate, password, or signing-mode properties. Maven and Zig always use the
ad-hoc signing path described by the developer decision above.

`java-home` must contain readable `include/jni.h` and `release`; the release metadata must identify
a 21.x JDK for the repository build. `macos-sdk` must directly contain the expected SDK `usr` and
`System` trees. The signer must be an executable regular file and report rcodesign 0.29.0.

The module uses:

```text
--cache-dir target/zig-cache
--global-cache-dir target/zig-global-cache
```

No build cache or output is written below `src`.

### Product graph

The default `install` step depends on all eight product install nodes and catalog installation.
There is no selected-target option. Each non-macOS product has:

```text
validated manifest/source inputs
  -> compile/link to a product-private cache output
  -> install to the exact output-root/resourcePath
```

Each macOS product has:

```text
compile/link unsigned product
  -> rcodesign input -> separate signed output
  -> rcodesign print-signature-info on signed output
  -> install signed output to output-root/resourcePath
```

The install step explicitly depends on signature inspection. It never installs the unsigned
compile output. Every signing node depends only on its own architecture product and immutable
signing inputs; x64 and arm64 may run in parallel.

Default builds use deterministic ad-hoc signatures with:

```text
rcodesign sign
  --binary-identifier <manifest value>
  --code-signature-flags runtime
  --timestamp-url none
  <unsigned input> <separate signed output>
```

The graph has no credentialed signing branch and accepts no certificate or password inputs. It
always disables timestamping and emits an ad-hoc signature with the hardened-runtime flag.

`rcodesign verify` 0.29.0 was observed to reject its own ad-hoc result because it expects CMS.
Cross-build verification therefore uses `print-signature-info`, asserts the exact binary
identifier, CodeDirectory, ad-hoc mode, and code hashes, and proves an
`LC_CODE_SIGNATURE`. A macOS runner performs the authoritative:

```text
/usr/bin/codesign --verify --strict --verbose=4 <packaged-copy>
```

### Optimization and hardening

Every product uses the same selected policy unless an OS-specific linker field below is named:

```text
Zig optimize mode:       ReleaseSafe
C/C++ flags:             -fno-exceptions -fno-rtti -fvisibility=hidden
extra -O flag:           none
PIC:                     true
strip:                   true
stack protector:         true
stack check:             true
omit frame pointer:      false
unwind tables:           async
C sanitization:          trap
red zone:                target default
code model:              default
LTO:                     disabled
bundle compiler runtime: false
link libc:               true
link libc++:             false
ELF RELRO:               true
ELF lazy binding:        false
undefined shared refs:   forbidden
Windows dynamic base:    true
Windows auto DLL export: false
```

`ReleaseSafe` keeps optimized code while retaining enabled safety checks. The redundant `-O3` is
removed. Frame pointers and asynchronous unwind tables are retained for diagnosability. The
supported JNI products require libc today, but no separate C++ or compiler-runtime library is
allowed. Default target red-zone and code-model choices replace unsupported hand tuning.

The graph does not link CoreFoundation, libobjc, libc++, or an application framework; does not add
framework search paths; and does not request header-padding. Each macOS compile sets
`install_name` to the gate policy expanded with that product's exact output filename; this
`LC_ID_DYLIB` is identity metadata, not a load dependency. The only `LC_LOAD_DYLIB` is libSystem.
Windows may import only the three libraries in the manifest gate policy; an implementation that
still needs UCRT stdio must remove the cause or return to this blueprint rather than broaden the
allowlist. Linux imports only its selected libc.

## Generated runtime catalog

The Zig manifest parser emits:

```text
target/generated-resources/native/META-INF/euhedral/native-products.tsv
```

It is UTF-8, LF-only, no BOM, ends with one newline, is at most 65,536 bytes, and contains:

```text
schema	1
os	exact	linux	linux
os	exact	darwin	macos
os	exact	mac os x	macos
os	exact	macos	macos
os	prefix	windows	windows
arch	aarch64	arm64
arch	amd64	x64
arch	arm64	arm64
arch	x86-64	x64
arch	x86_64	x64
product	linux-glibc-x64	linux	x64	glibc	10	/bin/linux/glibc/linux_jni_x64.so
product	linux-musl-x64	linux	x64	musl	20	/bin/linux/musl/linux_jni_x64.so
product	linux-glibc-arm64	linux	arm64	glibc	10	/bin/linux/glibc/linux_jni_arm64.so
product	linux-musl-arm64	linux	arm64	musl	20	/bin/linux/musl/linux_jni_arm64.so
product	macos-x64	macos	x64	none	10	/bin/osx/osx_jni_x64.dylib
product	macos-arm64	macos	arm64	none	10	/bin/osx/osx_jni_arm64.dylib
product	windows-x64	windows	x64	none	10	/bin/windows/windows_jni_x64.dll
product	windows-arm64	windows	arm64	none	10	/bin/windows/windows_jni_arm64.dll
```

The displayed order is exact: header, OS rules sorted by canonical OS/match/value, architecture
aliases sorted by alias, then products by manifest OS declaration order, manifest architecture
declaration order, load order, and product ID. Comparisons within a key use unsigned UTF-8 order.
Fields are tab-separated and cannot contain tabs, CR, LF, NUL, or leading/trailing whitespace.

The generator performs no wall-clock, host, absolute-path, cache, or signing-mode interpolation.
Two runs from the same manifest produce byte-identical catalogs.

## Maven lifecycle and packaging

### Lifecycle order

The hardware POM uses module-local plugin executions in this order:

1. `compile`: the compiler produces Java classes and the seven `javac -h` declaration headers;
2. `process-classes`: a narrowly configured AntRun execution deletes only
   `target/generated-resources/native`, `target/classes/bin`, and
   `target/classes/META-INF/euhedral/native-products.tsv`;
3. `process-classes`: `exec-maven-plugin` invokes the relocated Zig graph with explicit paths and
   target-local caches;
4. `process-classes`: `maven-resources-plugin:copy-resources` copies the generated native resource
   root into `target/classes`; and
5. `package`/`verify`: the normal jar is built, then Failsafe package/binary/runtime integration
   tests inspect that exact jar.

POM declaration order is part of the contract for executions sharing `process-classes`. The
cleanup refuses an empty, source-root, workspace-root, or unresolved property and names only the
three target-owned paths above. Its Ant delete/fileset configuration sets
`followSymlinks="false"` and `removeNotFollowedSymlinks="true"`; a temporary sentinel test proves
a symlink at or below any cleanup root is removed or rejected without touching its external
target. `mvn clean` removes all target caches; warm builds preserve `target/zig-cache` and
`target/zig-global-cache`.

Source resources use an allowlist containing only:

```text
logback-fragments/**
```

Generated resources use an allowlist containing only:

```text
bin/**
META-INF/euhedral/native-products.tsv
```

This makes ignored source `bin/**`, headers, build inputs, caches, and stale products
unpackageable even if they remain on disk.

### Exact jar resource contract

The ordinary module jar contains Java classes, `module-info.class`, and exactly these non-class
files in addition to the eight native entries below:

```text
META-INF/MANIFEST.MF
META-INF/euhedral/native-products.tsv
META-INF/maven/io.euhedral-execution/euhedral-hardware-utils/pom.properties
META-INF/maven/io.euhedral-execution/euhedral-hardware-utils/pom.xml
logback-fragments/euhedral-hardware-utils.xml
```

The exact native entries are:

```text
bin/linux/glibc/linux_jni_x64.so
bin/linux/glibc/linux_jni_arm64.so
bin/linux/musl/linux_jni_x64.so
bin/linux/musl/linux_jni_arm64.so
bin/osx/osx_jni_x64.dylib
bin/osx/osx_jni_arm64.dylib
bin/windows/windows_jni_x64.dll
bin/windows/windows_jni_arm64.dll
```

Directory entries are permitted only when they are parents of an allowed file. No native `.c`,
`.cc`, `.cpp`, `.cxx`, `.h`, `.hpp`, `.zig`, `.zon`, `.sh`, `.json` manifest, PDB, import library,
object, cache file, per-source library, or source absolute path may occur. The standard packaged
module POM is the only allowed build-description source. Package tests derive the class-entry
allowance from `target/classes/**/*.class`; the exact non-class file entry set is fixed above.

For each native product, SHA-256 of the staged file must equal SHA-256 of the bytes read from the
jar entry. For macOS this proves the packaged copy is the signed and structurally verified copy.

### Warm-removal proof

The test does not edit the active manifest. It copies the module's tracked native inputs and
necessary POM context into a temporary isolated tree, performs a package build with tests skipped,
removes one product from the copied manifest, then performs a warm package rebuild without
`clean`, again with tests skipped. The generic schema accepts that internally consistent
seven-product copy; active-tree P0/exact-eight assertions are deliberately not run there. The test
proves the removed resource is absent from generated resources, `target/classes`, catalog, and
jar. The active worktree and all ignored source artifacts remain byte-identical.

## Loader contract

### Internal types and publication

Keep `JNIClassLoader` final with the same public `load()` method. Add package-private internal
types, with names allowed to follow the surrounding convention:

- an immutable `NativeProductCatalog`;
- an immutable `NativeProduct`;
- a `NativeLibraryExtractor`;
- a small `NativeLibrarySystem` load seam whose production implementation calls `System.load`;
  and
- an immutable load result/failure aggregate used only during class initialization and tests.

`JNIClassLoader.load()` triggers a holder class. JVM class initialization is the single
serialization and safe-publication boundary. The parsed catalog and selected result are immutable;
there is no lock, retry state, I/O, or allocation after successful initialization.

The parser reads `/META-INF/euhedral/native-products.tsv` through
`JNIClassLoader.class.getResourceAsStream`. It enforces the catalog size and exact schema, rejects
duplicate/unknown rows and ambiguous alias rules, and proves every product resource exists before
selection. It never scans the jar.

### OS, architecture, and fallback selection

Normalize `os.name` and `os.arch` with `Locale.ROOT`, trim outer ASCII whitespace, collapse
internal ASCII whitespace in OS names to one space, and lowercase. Apply only catalog alias rules:

- exact rules win;
- at most one prefix rule may match;
- an exact/prefix ambiguity fails; and
- an unknown OS or architecture fails with the original property value and the sorted supported
  aliases.

There is no `SystemInfo` call and no x64/arm64 default. This avoids a topology/class-initialization
cycle.

Select products by canonical OS and architecture, then sort by `loadOrder` and product ID. Linux
therefore tries glibc and then musl. Windows and macOS have one candidate. For each candidate,
catch only:

- `IOException`;
- `SecurityException`; and
- `LinkageError`.

Record the failure, clean that candidate's extraction where safe, and continue. Do not catch
`VirtualMachineError`, `ThreadDeath`, or arbitrary `Error`. If all candidates fail, throw one
`ExceptionInInitializerError` with a concise message and add candidate failures as suppressed
causes in attempt order.

### Extraction location and ownership

The internal system property is:

```text
io.euhedral.native.extract.dir
```

Its value, when present, must be an absolute existing writable directory. Otherwise use the
absolute normalized `java.io.tmpdir`. Under that parent, use only:

```text
euhedral-native-v1/load-<pid>-<32-lowercase-hex>/
```

The run directory contains `owner.properties` and at most one candidate library at a time. The
marker is UTF-8/LF and contains schema, PID, and creation epoch milliseconds. Create directories
and files with `CREATE_NEW`, reject symlinks with `NOFOLLOW_LINKS`, and never replace an existing
path.

On a POSIX file store, set the base/run directory to `rwx------`, the marker to `rw-------`, and
the library to `rwx------` through `PosixFileAttributeView`. On Windows, never request a POSIX
view: require `AclFileAttributeView`, set the dedicated base/run/file ACL to one inheritable
full-control allow entry for the current owner, then read it back and reject any effective
non-owner entry. The random name is defense in depth, not the privacy boundary. If a supported
POSIX OS lacks a POSIX view or Windows lacks an ACL view, fail with an actionable message rather
than assume permissions.

Copy through a fixed 64 KiB buffer. Reject an empty resource and any product larger than
67,108,864 bytes. Count with `long`, reject overflow or extra bytes, close the stream before
`System.load`, and compare the final size with the count.

After a successful POSIX `System.load`, unlink the library and remove the marker/run directory
immediately; the mapped library remains loaded. If immediate removal fails, retain it for the
shutdown cleanup. Windows retains its locked DLL until shutdown. Register one shutdown hook only
after the first extraction directory exists; the hook owns only this process's validated run
directory and makes a best-effort non-recursive exact-file cleanup.

At startup, perform a bounded stale cleanup below `euhedral-native-v1`:

- inspect at most 64 children in lexicographic filename order;
- never follow or delete a symlink;
- accept only the exact directory, marker, and native filename grammar;
- require marker PID and directory PID to match;
- require age of at least 24 hours using saturating millisecond arithmetic;
- skip a PID that `ProcessHandle` reports alive;
- where owner attributes exist, require the child owner to equal the current base owner;
- skip any directory with an unexpected entry, oversized file, unreadable marker, invalid
  timestamp, or failed liveness check; and
- delete only the exact recognized library, marker, then empty directory.

Cleanup failure is debug-level diagnostic and never turns a successful load into failure.

### Noexec diagnosis

After all candidates fail, the final error names:

- canonical OS and architecture;
- every attempted resource path and absolute extraction path;
- the exception class and sanitized message for each attempt; and
- the exact remedy:
  `-Dio.euhedral.native.extract.dir=<absolute executable filesystem directory>`.

The message says that a `noexec` mount is a possibility, not a proven cause. It does not claim that
the executable permission bit proves mount executability.

## Binary and runtime gates

### Static inspection

`NativePackagingIT` invokes explicitly supplied `llvm-readobj` and `llvm-objdump` executables from
the same LLVM installation. It parses complete command output, checks process exit status, and
rejects duplicate/missing records. Commands are:

```text
llvm-readobj --file-header --needed-libs --dyn-symbols --version-info <ELF>
llvm-readobj --file-header --coff-imports --coff-exports <PE>
llvm-readobj --file-header --needed-libs --macho-version-min --symbols <Mach-O>
llvm-objdump --macho --private-headers <Mach-O>
```

The module test properties `p1.llvm.readobj` and `p1.llvm.objdump` are required absolute executable
paths during `verify`. The POM defaults to the supported Ubuntu build-host paths under `/usr/bin`;
independently activated module profiles allow absolute `LLVM_READOBJ` and `LLVM_OBJDUMP`
environment values to override those defaults. `mise.toml` supplies the Ubuntu paths, and local
validation on another build host passes both explicitly. Tests never search PATH or silently skip
a missing inspector.

For Mach-O, `llvm-objdump` is authoritative for distinguishing `LC_ID_DYLIB` from
`LC_LOAD_DYLIB`; `llvm-readobj --needed-libs` is only a redundant inventory cross-check. For every
product the test asserts:

- format and machine equal the manifest architecture gate;
- one `JNI_OnLoad` and the exact allowed JNI exports exist;
- no unexpected externally visible export exists;
- needed libraries equal the product policy, not merely a subset;
- glibc imported symbol versions are no newer than 2.17;
- musl contains no GLIBC version requirement;
- PE exports no CRT/compiler helper and imports no stdio, C++, libgcc, or compiler-runtime DLL;
- Mach-O has deployment target exactly 11.0, the exact manifest-expanded `LC_ID_DYLIB`, and only
  libSystem in `LC_LOAD_DYLIB`;
- macOS signature metadata has the exact manifest identifier and hardened-runtime flag; and
- staged and packaged SHA-256 digests match.

The test extracts jar entries to its own target-local temporary directory and always closes and
deletes them. It never reads ignored source binaries.

### Runtime smoke

Add a test-only `NativeLoadSmokeMain` with two modes:

- `load-only`: call `JNIClassLoader.load()` and exit zero only after `JNI_OnLoad` succeeds;
- `linux-get-cpu`: load and call `LinuxAffinity.INSTANCE.getCpu()`, requiring a non-negative
  result.

The CI smoke artifact contains only the ordinary module jar, that compiled test main class at its
package path, and the module's runtime dependency jars under `lib/`. A module-local
`maven-dependency-plugin` execution prepares the runtime jars under `target/native-smoke/lib`
without attaching or publishing another artifact. The jobs run it on the classpath, cap the
bundle at 64 files and 134,217,728 bytes, and reject any source, credential, cache, or unrelated
test fixture.

The required P1 runtime matrix is:

| Environment | Gate |
| --- | --- |
| Linux x64 glibc build JDK 21 | Failsafe selects glibc and `linux-get-cpu` succeeds |
| Linux x64 glibc JDK 17 | the same packaged jar selects glibc and `linux-get-cpu` succeeds |
| Linux x64 musl JDK 17 in a bounded Docker job | glibc fails with `LinkageError`, musl fallback loads, and `linux-get-cpu` succeeds |
| Windows x64 JDK 17 | packaged x64 DLL `load-only` succeeds without POSIX permission calls |
| macOS hosted runner JDK 17 | host-architecture dylib `load-only` and `codesign --verify` succeed |

Cross-built Linux arm64, Windows ARM64, and both architecture-specific full platform calls remain
explicitly `unverified` B06 portions for P5-P7. The static architecture/import/export gates still
apply to all eight products.

Unit tests inject the load seam and synthetic catalogs/resources to prove:

- exact alias matching and every unknown-architecture failure;
- deterministic Linux fallback on `LinkageError`, `IOException`, and `SecurityException`;
- no fallback on an arbitrary `Error`;
- Windows never requests POSIX attributes;
- POSIX immediate cleanup and Windows deferred cleanup;
- bounded copy/size/zero-byte failures;
- safe stale cleanup rejection for symlinks, live PIDs, young entries, unexpected files, invalid
  markers, and entry 65;
- actionable all-candidates/noexec diagnostics; and
- concurrent `load()` callers observe one class-initialization result.

## CI and signing safety

Add `.github/workflows/hardware-utils-native.yaml` with:

- `permissions: contents: read`;
- checkout with `persist-credentials: false`;
- a cross-package Ubuntu job using the pinned JDK 21, Maven 3.9.16, Zig 0.16.0, rcodesign 0.29.0,
  the exact macOS SDK path, and LLVM;
- only
  `mvn -B -pl euhedral-hardware-utils -am verify` as its Maven validation selection;
- target-local Zig caches in the cache key, never source `.zig-cache`;
- upload of the exact jar and a bounded smoke bundle;
- JDK 17 Linux glibc and musl smoke gates in addition to the build-JDK Failsafe gate;
- JDK 17 Windows and macOS load-only jobs consuming that same uploaded jar; and
- macOS verification of both packaged dylib signatures.

No job has a product-selection flag. Cross-package builds all eight products once; runner jobs
test the applicable packaged product.

Remove only the invalid JDK-header-copy steps from both existing workflows. Normalize their SDK
setup and native input environment only as required by the new explicit P1 contract. The deploy
workflow must not prepare, export, validate, or clean up macOS certificate credentials; release
builds use the same ad-hoc signing graph as all other builds. Do not otherwise alter its Maven
command, checkout credential behavior, permissions, deploy logic, or unrelated cache.

## Failure behavior

All failures identify the owning layer:

- `native-manifest:` for schema, discovery, and target metadata;
- `native-jni:` for header inventory, type width, declarations, or export mismatch;
- `native-sign:` for signer input, signature, identifier, or ordering;
- `native-package:` for target cleanup, staged/catalog/jar mismatch, or source contamination; and
- `native-loader:` for catalog, selection, extraction, fallback, or cleanup.

Diagnostics include normalized relative paths and product IDs. They do not dump whole manifests,
absolute home paths unless needed for an extraction remedy, certificate contents, passwords,
environment maps, or binary bytes.

Missing required input, an empty designated folder, a product compile failure, a failed signature
inspection, or one missing product fails the universal build. No product is skipped because the
host cannot execute it.

## Memory, ownership, contamination, and precision

### Memory and publication

The build graph is immutable after configuration. Independent product nodes share read-only
source/header inputs and have product-private outputs. Signing never mutates a compile output in
place.

At runtime, JVM class initialization safely publishes the immutable catalog and load result.
Candidate error lists are confined to initialization. Extraction buffers are fixed at 64 KiB and
released before the loaded state is published. There is no hot-loop impact.

JNI type widths are compile-time exact: 8-bit byte, 32-bit `jint`, 64-bit `jlong`, and 64-bit
pointer. This P1 header work does not change native buffer lengths or platform values; those remain
owned by later phases.

### Artifact and secret contamination

Before and after every implementation build, capture sorted path, type, size, and nanosecond mtime
for existing ignored `src/main/resources/bin/**` and source `.zig-cache/**`; also capture SHA-256
for the bounded native files under `bin/**`. Do not read every potentially large cache payload.
The before/after fingerprints must match, and the fingerprint files live under a temporary
directory, not the workspace.

Also fingerprint tracked `src/main/java` and the source-resource allowlist. Except for reviewed
tracked relocation/deletion in the implementation diff, builds change no source path. Search
generated resources, jars, logs, test reports, and target cache metadata for source absolute paths
and the release-secret environment variable names; no secret values may be materialized.

### Numeric precision

- manifest size: at most 1,048,576 bytes;
- runtime catalog size: at most 65,536 bytes;
- native resource size: `1..67,108,864` bytes;
- extraction buffer: exactly 65,536 bytes;
- stale-cleanup age: at least `86,400,000` ms;
- stale-cleanup scan: at most 64 children;
- PID and timestamps: parsed into signed 64-bit values with overflow rejection;
- age subtraction: saturating, and a future timestamp is never stale;
- build/load order: positive 32-bit integers with duplicate rejection; and
- macOS deployment/glibc versions: parsed numeric tuples, never floating point or lexicographic
  strings.

No generated metadata contains the current time. Credentialed signatures and jar timestamps make
byte-for-byte release-jar reproducibility out of scope; deterministic inventory, ordering,
inputs, catalog bytes, and staged-copy identity remain required.

## Implementation order and child ownership

### Child A - native graph, JNI, signing, and Maven staging

Branch family:

```text
hardware-utils-overhaul/phase-1-native-graph-blueprint
hardware-utils-overhaul/phase-1-native-graph-implementation
hardware-utils-overhaul/phase-1-native-graph-validation
hardware-utils-overhaul/phase-1-native-graph-audit
```

Owned implementation, in dependency order:

1. pin/expose exact native tool inputs in `mise.toml`;
2. relocate tracked native build/source inputs and add the strict JSON manifest;
3. add the target-aware JNI header and common `JNI_OnLoad`;
4. replace `build.zig` with strict discovery, target, hardening, signing, catalog, cache, and
   staging graph;
5. update the module POM for `javac -h`, safe target cleanup, explicit Zig invocation, source
   resource allowlist, and generated-resource copy;
6. update P0 native source discovery for the relocated root while preserving the P0 product and
   N01/N02 contract;
7. delete only the tracked obsolete `build.sh`;
8. remove the invalid header-copy blocks and normalize SDK, Zig, signer, and LLVM inputs in the
   native setup of the two existing workflows, removing deploy certificate handling without
   changing their Maven commands; and
9. add focused manifest/build/signature tests sufficient to validate Child A without loader or
   new runner-workflow design.

Child A must leave a default selected-module `package` producing the exact catalog and eight
products. It may not edit `JNIClassLoader`, add the hardware-specific workflow, or change any
existing workflow behavior outside the exact native setup above.

### Child B - loader, packaging gates, runtime smoke, and CI

Branch family:

```text
hardware-utils-overhaul/phase-1-loader-package-blueprint
hardware-utils-overhaul/phase-1-loader-package-implementation
hardware-utils-overhaul/phase-1-loader-package-validation
hardware-utils-overhaul/phase-1-loader-package-audit
```

Child B starts only after Child A's audit is merged. Owned implementation, in dependency order:

1. implement the immutable catalog parser, product selection, extraction, cleanup, and load seam;
2. rewrite `JNIClassLoader` around that contract without changing its public trigger;
3. add unit tests for aliases, fallback, permissions, size limits, cleanup, diagnostics, and
   initialization;
4. add Failsafe exact-jar, manifest/catalog, binary, signature, warm-removal, and smoke tests;
5. extend only the already settled hardware POM test configuration needed by those gates;
6. add the hardware-specific selected-module workflow and its runtime runner jobs; and
7. verify the Child A existing-workflow native setup remains sufficient for the new gates without
   changing unrelated workflow behavior.

Child B may not change the JSON/TSV schema, product set, source graph, target flags, JNI type
definitions, or signing order. A defect in those contracts returns to Child A or this parent.

## Validation commands

Children substitute exact absolute installed paths through task-specific variables; they do not
repurpose `HOME`.

### Static and scope checks

```text
git status --short
git diff --check
git diff --name-only <child-parent> -- euhedral-training
git ls-files euhedral-hardware-utils/src/main/resources/bin
find euhedral-hardware-utils/src/main/native -type l -print
```

The training diff and tracked source-bin outputs are empty. Do not otherwise inspect training.

### Direct Zig and selected-module lifecycle

```text
zig version
rcodesign --version
zig build --help

zig build \
  -Djava-home=<jdk-21> \
  -Dgenerated-jni=<module>/target/generated-jni \
  -Doutput-root=<module>/target/generated-resources/native \
  -Dmacos-sdk=<MacOSX26.1.sdk> \
  -Drcodesign=<rcodesign-0.29.0> \
  --cache-dir <module>/target/zig-cache \
  --global-cache-dir <module>/target/zig-global-cache

env JAVA_HOME=<jdk-21> ZIG=<zig-0.16.0> SDKROOT=<MacOSX26.1.sdk> RCODESIGN=<rcodesign-0.29.0> \
    LLVM_READOBJ=<llvm-readobj> LLVM_OBJDUMP=<llvm-objdump> \
    <maven-3.9.16>/bin/mvn -B -pl euhedral-hardware-utils -am clean verify
env JAVA_HOME=<jdk-21> ZIG=<zig-0.16.0> SDKROOT=<MacOSX26.1.sdk> RCODESIGN=<rcodesign-0.29.0> \
    LLVM_READOBJ=<llvm-readobj> LLVM_OBJDUMP=<llvm-objdump> \
    <maven-3.9.16>/bin/mvn -B -pl euhedral-hardware-utils -am verify
env JAVA_HOME=<jdk-21> ZIG=<zig-0.16.0> SDKROOT=<MacOSX26.1.sdk> RCODESIGN=<rcodesign-0.29.0> \
    LLVM_READOBJ=<llvm-readobj> LLVM_OBJDUMP=<llvm-objdump> \
    <maven-3.9.16>/bin/mvn -B -pl euhedral-hardware-utils -am verify
```

Use the repository JDK 21 and Maven 3.9.16 explicitly. None of these selections includes training.

### Inventory, binary, and signature checks

```text
jar tf euhedral-hardware-utils/target/euhedral-hardware-utils-*.jar
llvm-readobj --file-header --needed-libs --dyn-symbols --version-info <ELF>
llvm-readobj --file-header --coff-imports --coff-exports <PE>
llvm-readobj --file-header --needed-libs --macho-version-min --symbols <Mach-O>
llvm-objdump --macho --private-headers <Mach-O>
rcodesign print-signature-info <staged-and-packaged-Mach-O>
/usr/bin/codesign --verify --strict --verbose=4 <packaged-Mach-O>
```

Run the Apple command only on a macOS runner. The integration tests parse these results; a visual
command review is supplementary.

### Timing

Before Child A migration, use a temporary
`git archive 03ff2060 -- pom.xml mise.toml euhedral-hardware-utils` tree outside the workspace to
record `/usr/bin/time` wall time and maximum RSS for one clean and two warm current universal
builds. Source writes in that disposable tree are expected and then discarded. Run the same one
clean/two warm sequence for the completed P1 graph in the active module, whose source
fingerprints must remain unchanged.

Record host CPU/memory, JDK, Maven, Zig, SDK, signer, and cache state for both sequences. Compare
descriptively. There is no speed threshold and no throughput claim.

## Acceptance criteria

P1 root integration validation must classify every item:

1. The strict checked-in JSON manifest is the sole source-root/product inventory; its generic
   parser accepts internally consistent product removal, while the active-tree compatibility gate
   requires the exact eight P0 products.
2. Recursive discovery is deterministic and fails on every specified malformed/missing/unknown
   condition.
3. Default module lifecycle builds all products with no host/development selection and no source
   writes.
4. Ignored source binaries/caches are fingerprint-identical and impossible to package.
5. Generated JNI declarations cover exactly seven classes; target-aware ABI widths and Windows
   calling/export macros pass every cross target.
6. N01, N02, and the legacy macOS export are the only exact carried ABI exceptions.
7. Every product exports `JNI_OnLoad` returning JNI 1.8 and passes its available smoke gate.
8. Selected ReleaseSafe/hardening/link settings are present; old `-O3`, disabled protections,
   bundled compiler runtime, SDK search, and framework link are absent; each macOS product has the
   exact filename-matched `LC_ID_DYLIB` and no extra load dependency.
9. Linux fallback products retain glibc 2.17 and musl with no C++/compiler-runtime library.
10. The Zig graph exposes independent per-product compile/sign nodes and signs/verifies the exact
    staged macOS copy.
11. The runtime TSV is byte-deterministic, schema-valid, manifest-derived, and the only loader
    product/alias table.
12. Warm product removal leaves no stale generated, classpath, catalog, or jar entry.
13. The jar has the exact non-class resource inventory and no native source/header/cache/stale
    entry.
14. Packaged native bytes equal staged bytes; all static architecture/export/import/version/
    deployment/signature gates pass.
15. Unknown OS/architecture values fail; Linux retries glibc then musl on the three allowed
    failure classes and no arbitrary `Error`.
16. POSIX and Windows permission paths, bounded extraction, ownership, immediate/deferred cleanup,
    and 64-entry stale scavenging pass deterministic tests.
17. All-candidate failure provides the exact configurable extraction remedy and an honest noexec
    diagnosis.
18. Concurrent callers observe one safely published load result with no post-initialization hot
    path.
19. The hardware-specific workflow uses selected-module commands, read-only permissions, no
    persisted checkout credentials, no signing secrets, and required runner smoke/signature
    gates.
20. Existing root build/deploy Maven commands and unrelated workflow behavior are unchanged; only
    the invalid header blocks and explicit native inputs change.
21. Clean/warm timing is recorded without an unsupported performance claim.
22. B01-B05 and B07 are satisfied; the P1 gate-framework portion of B06 is satisfied and named
    platform runtime portions remain explicitly carried to P5-P7.
23. P0 compatibility and the hardware selected-module test suite pass with no training selection.
24. `git diff --check`, scope checks, source fingerprints, and final `git status --short` pass.

Any material deviation requires developer approval. `unverified` is permitted only for the exact
B06 runtime portions assigned to P5-P7.

## Sizing and split gate

A single implementation context is rejected. The native graph child combines strict parsing,
recursive filesystem discovery, cross-target ABI definitions, eight compiler/linker products,
signing DAG correctness, and Maven lifecycle ordering. The loader/package child combines class
initialization, filesystem security, cross-OS permissions and cleanup, deterministic fallback,
binary parsing, runtime smoke, and release secret handling.

Those responsibilities have a stable producer/consumer boundary (JSON manifest, TSV catalog,
generated resource root, exact product set) and can be implemented sequentially. Combining them
would require one pass to hold unrelated Zig 0.16 APIs, Java filesystem/lifecycle states, Maven
ordering, binary formats, and CI secret policy, making omissions difficult to localize. Splitting
within either child would create more overlap than isolation, so both children pass their own
sizing gate as written.

The mandatory order is:

```text
parent blueprint merge
  -> Child A blueprint -> implementation -> validation -> audit, each merged in order
  -> Child B blueprint -> implementation -> validation -> audit, each merged in order
  -> P1 root integration validation
  -> P1 root conformance audit and authorized closeout
```

No root implementation branch or prompt may run.

## Bounded context envelopes

### Child A required inputs

- `AGENTS.md` and `docs/AGENT_WORKFLOW.md`;
- parent plan P1 section, P0 artifact-index files, P0 closeout, and this blueprint;
- `mise.toml`, root plugin-management snippets, hardware POM/module descriptor;
- seven Java native declaration classes and P0 native compatibility fixtures/tests;
- tracked `build.zig`, `build.sh`, platform native source/header files, and a names-only/size/hash
  ignored artifact fingerprint;
- the native setup portions of `.github/workflows/build.yaml` and
  `.github/workflows/deploy.yaml`;
- Zig 0.16 `std.Build`, `Module`, `Compile`, `Run`, `WriteFile`, JSON, target, and install APIs
  directly from the pinned installation; and
- rcodesign 0.29.0 help plus `llvm-readobj` inspection of current intended aggregate products.

Child A excludes loader implementation, the new hardware-specific workflow, unrelated workflow
logic, non-hardware modules, platform semantics, and training.

### Child A owned outputs

- `docs/blueprints/hardware-utils/phase-1-native-graph-jni-signing.md` and completion record;
- relocated native tree, manifest, JNI ABI header, `JNI_OnLoad`, Zig graph, tracked script
  deletion;
- module POM build/staging/resource configuration;
- focused manifest/build/signing and relocated P0 compatibility tests;
- exact native setup/header-removal and ad-hoc-only deploy edits to the two existing workflows;
- Child A validation and conformance audit; and
- temporary P1 status updates.

### Child B required inputs

- `AGENTS.md` and `docs/AGENT_WORKFLOW.md`;
- parent plan P1 section, P0 exact artifacts/closeout, and this parent blueprint;
- Child A blueprint/completion, validation, conformance audit, and only its finalized catalog,
  staging, signing, and POM handoff diff;
- existing `JNIClassLoader`, internal logging/OS conventions, seven native owner classes, and
  focused loader/P0 tests;
- hardware POM generated-resource contract and exact built jar/catalog;
- Child A's summarized existing-workflow native-input handoff and the new hardware workflow path;
  and
- JDK 17/21 `Files`, attribute-view, `ProcessHandle`, class-initialization, `System.load`, Maven
  Failsafe, LLVM, and runner behavior needed by the owned tests.

Child B excludes native source semantics, Zig schema/graph redesign, non-hardware code, root POM,
unrelated workflow logic, and training.

### Child B owned outputs

- `docs/blueprints/hardware-utils/phase-1-loader-maven-packaging.md` and completion record;
- internal catalog/loader/extraction implementation;
- loader, packaging, binary, warm-removal, and smoke tests plus narrow POM test wiring;
- hardware-specific selected-module workflow;
- Child B validation and conformance audit; and
- temporary P1 status updates.

## Implementation model reassessment

### Context and coupling

The original provisional root implementation is not safe. It would span strict serialization,
filesystem migration, Zig 0.16 graph APIs, target ABI rules, signing dependencies, Maven lifecycle
ordering, Java class initialization, cross-OS file security, cleanup recovery, three binary
formats, and CI secrets in one pass.

Child A still requires coupled reasoning across a schema, recursive filesystem safety, compiler
targets, JNI widths/calling conventions, signing input/output identity, and Maven phase ordering.
Child B still requires coupled reasoning across immutable publication, exception taxonomy,
cross-platform filesystem semantics, bounded cleanup, archive identity, process invocation, and
runner/release behavior. Neither is a low-effort mechanical edit.

The parent blueprint supplies small exact context envelopes and a frozen interface between the
children, but implementation will still require broad compile repair and adversarial tests.

### Capability decision

- Child A implementation: `gpt-5.6-sol`, reasoning effort `high`.
- Child B implementation: `gpt-5.6-sol`, reasoning effort `high`.
- Child validations and audits: `gpt-5.6-sol`, reasoning effort `high`.
- P1 root integration validation and conformance audit: `gpt-5.6-sol`, reasoning effort `high`.

Each child blueprint must rerun this gate against its refined file/test inventory. It may raise
effort or split again, but it may not silently downgrade. The selected root implementation is
`none`; its former prompt is superseded and prohibited.

## Risks and operational prerequisites

- Zig 0.16 build APIs are a compile-time risk; children must consult the installed source.
- `ReleaseSafe` plus strict Windows UCRT policy may expose an implementation conflict. The
  allowlist is not broadened without returning to this blueprint.
- rcodesign 0.29.0 cannot be treated as authoritative for ad-hoc CMS verification; the structural
  cross gate and Apple `codesign` gate are both required.
- GitHub release signing requires the two named protected secrets before a non-snapshot release.
- Docker and hosted Windows/macOS availability are environmental validation prerequisites. A
  missing runner is `unverified`, not a pass.
- Ignored source binaries and caches are user-owned. Their presence is expected; any mutation is a
  blocker.

There are no unresolved architectural decisions. Repository release-secret configuration remains
an explicit operational prerequisite.

## Handoff condition

Handoff this parent blueprint for developer review only when:

- its plan prompt sequence and artifact index name both child lifecycles and root integration
  actions;
- the old root implementation prompt is marked superseded and non-runnable;
- P0 inherited commit `03ff2060` and the child order are recorded;
- every manifest, ABI, signing, loader, extraction, packaging, and gate choice above is settled;
- only planning documentation differs on this branch; and
- `git diff --check` and scope checks pass.

Do not create Child A branches before this parent blueprint is reviewed and merged into the P1
root. Do not start Child B before Child A's audit is reviewed and merged.

## P1 root integration completion record

Formal integration validation was skipped by developer direction. Direct root conformance on
2026-08-01 made no correction and reran no Maven/Zig/runtime command. It classified 21 criteria
`satisfied`, criteria 19/24 `unverified`, and criterion 22 `deviated`; Child A is `ambiguous`
because its artifact chain is absent and Child B is `satisfied`. B01-B05, B07, and the P1 B06
framework are `satisfied`. Hosted Windows/macOS remain unverified. `git diff --check` passed.
P1 remains open pending authorized merge, status removal, closeout update, and root commit record.
See `docs/audits/hardware-utils/phase-1-native-build-jni-packaging-conformance.md`.
