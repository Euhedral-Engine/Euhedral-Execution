# Gradle Build Configuration

This project uses Gradle 9.6.1 to build, test, and deploy.

## Building the Project

### Prerequisites

Set up your environment. This project uses `mise` to make most of it easy. If you already have mise,
update it as the apple-codesign dependency might not work.

Environment Setup:

```bash
# Install llvm
sudo apt update
sudo apt install -y xz-utils llvm

# Install mise
curl https://mise.run | sh
echo 'eval "$(~/.local/bin/mise activate bash)"' >> ~/.bashrc
exec bash
```

Run this in the project root:

```bash
mise trust
mise install

# Install MacOS SDK
mkdir -p ~/.local/share/mise/installs/macos-sdk
curl --fail --location --silent --show-error \
  https://github.com/joseluisq/macosx-sdks/releases/download/26.1/MacOSX26.1.sdk.tar.xz \
  | tar -xJ -C ~/.local/share/mise/installs/macos-sdk/
```

### Basic Build Commands

```bash
# Full build (compilation + tests)
gradle build integrationTest

# Clean and build
gradle clean build

# Build specific module
gradle :euhedral-core:build

# Run tests only
gradle test

# Compile without tests
gradle assemble

# Maven Central publish
gradle publish
```

## Native Build (euhedral-hardware-utils)

The hardware-utils module includes native libraries built with Zig for Linux, macOS, and Windows:

1. **JNI Header Generation**: Automatically generates headers during Java compilation
2. **Zig Cross-Compilation**: Builds native libraries for all platforms
3. **Resource Packaging**: Bundles native binaries into JAR
4. **Test Infrastructure**: Prepares native smoke test bundles

### Build Phases

```
compileJava (generates JNI headers)
    |
    V
zigBuild (cross-compiles native libraries)
    |
    V
copyNativeResources (packages into JAR)
    |
    V
jar (creates final artifact)
```

## Troubleshooting

### Native build failures

1. Ensure Zig is installed and available on PATH:

```bash
zig version
```

2. Make sure your installations are in the places `mise` says they should be or override `mise.toml`
   locally.

```
SDKROOT = "{{env.HOME}}/.local/share/mise/installs/macos-sdk/MacOSX26.1.sdk"
RCODESIGN = "{{env.HOME}}/.local/share/mise/installs/apple-codesign/apple-codesign-0.29.0/rcodesign"
ZIG = "{{env.HOME}}/.local/share/mise/installs/zig/0.16.0/zig"
LLVM_READOBJ = "/usr/bin/llvm-readobj"
LLVM_OBJDUMP = "/usr/bin/llvm-objdump"
```