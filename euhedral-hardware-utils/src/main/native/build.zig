const std = @import("std");
const builtin = @import("builtin");

const manifest_name = "native-products.json";
const catalog_path = "META-INF/euhedral/native-products.tsv";
const manifest_limit = 1024 * 1024;
const catalog_limit = 64 * 1024;

const expected_jni_headers = [_][]const u8{
  "io_euhedral_execution_hardware_utils_linux_LinuxAffinity.h",
  "io_euhedral_execution_hardware_utils_macos_MacosAffinity.h",
  "io_euhedral_execution_hardware_utils_macos_MacosResources.h",
  "io_euhedral_execution_hardware_utils_macos_MacosSystemLayout.h",
  "io_euhedral_execution_hardware_utils_windows_WindowsAffinity.h",
  "io_euhedral_execution_hardware_utils_windows_WindowsResources.h",
  "io_euhedral_execution_hardware_utils_windows_WindowsSystemLayout.h",
};

const SourceRules = struct {
  compiledExtensions: []const []const u8,
  passiveExtensions: []const []const u8,
  recursive: bool,
  followSymlinks: bool,
};

const RuntimeAlias = struct {
  match: []const u8,
  value: []const u8,
};

const OperatingSystem = struct {
  id: []const u8,
  runtimeAliases: []const RuntimeAlias,
};

const Architecture = struct {
  id: []const u8,
  zig: []const u8,
  runtimeAliases: []const []const u8,
  elfMachine: []const u8,
  peMachine: []const u8,
  machoCpu: []const u8,
};

const Component = struct {
  id: []const u8,
  os: []const u8,
  sourceRoots: []const []const u8,
  outputStem: []const u8,
  extension: []const u8,
};

const GatePolicy = struct {
  id: []const u8,
  format: []const u8,
  allowedLibraries: []const []const u8,
  forbiddenLibraryFragments: []const []const u8,
  maximumSymbolVersion: ?[]const u8 = null,
  installNameTemplate: ?[]const u8 = null,
  minimumDeploymentTarget: ?[]const u8 = null,
  minimumRuntime: ?[]const u8 = null,
};

const Product = struct {
  id: []const u8,
  component: []const u8,
  architecture: []const u8,
  libc: []const u8,
  zigTarget: []const u8,
  gatePolicy: []const u8,
  resourcePath: []const u8,
  buildOrder: u32,
  loadOrder: u32,
  signingIdentifier: ?[]const u8 = null,
};

const Manifest = struct {
  schemaVersion: u32,
  sourceRules: SourceRules,
  operatingSystems: []const OperatingSystem,
  architectures: []const Architecture,
  components: []const Component,
  gatePolicies: []const GatePolicy,
  products: []const Product,
};

const CatalogOsAlias = struct {
  canonical: []const u8,
  match_kind: []const u8,
  value: []const u8,
};

const CatalogArchAlias = struct {
  alias: []const u8,
  canonical: []const u8,
};

const StageJniHeaders = struct {
  step: std.Build.Step,
  jni_h: std.Build.LazyPath,
  jni_md_h: std.Build.LazyPath,
  output_directory: []const u8,

  fn create(
    b: *std.Build,
    jni_h: std.Build.LazyPath,
    jni_md_h: std.Build.LazyPath,
    output_directory: []const u8,
  ) *StageJniHeaders {
    const stage = b.allocator.create(StageJniHeaders) catch @panic("OOM");
    stage.* = .{
      .step = .init(.{
        .id = .custom,
        .name = "stage target-local JNI headers",
        .owner = b,
        .makeFn = make,
      }),
      .jni_h = jni_h.dupe(b),
      .jni_md_h = jni_md_h.dupe(b),
      .output_directory = b.dupePath(output_directory),
    };
    jni_h.addStepDependencies(&stage.step);
    jni_md_h.addStepDependencies(&stage.step);
    return stage;
  }

  fn make(step: *std.Build.Step, options: std.Build.Step.MakeOptions) !void {
    _ = options;
    const b = step.owner;
    const stage: *StageJniHeaders = @fieldParentPtr("step", step);
    const io = b.graph.io;
    const cwd = std.Io.Dir.cwd();
    try cwd.createDirPath(io, stage.output_directory);
    var output = try cwd.openDir(io, stage.output_directory, .{});
    defer output.close(io);

    if (!step.inputs.populated()) {
      try step.addWatchInput(stage.jni_h);
      try step.addWatchInput(stage.jni_md_h);
    }
    var unchanged = true;
    const inputs = [_]struct { source: std.Build.LazyPath, name: []const u8 }{
      .{ .source = stage.jni_h, .name = "jni.h" },
      .{ .source = stage.jni_md_h, .name = "jni_md.h" },
    };
    for (inputs) |input| {
      const source_path = input.source.getPath2(b, step);
      const status = try std.Io.Dir.updateFile(.cwd(), io, source_path, output, input.name, .{});
      unchanged = unchanged and status == .fresh;
    }
    step.result_cached = unchanged;
  }
};

pub fn build(b: *std.Build) void {
  if (builtin.zig_version.major != 0 or builtin.zig_version.minor != 16 or builtin.zig_version.patch != 0) {
    fatal("Zig 0.16.0 is required, found {f}", .{builtin.zig_version});
  }
  const java_home = requiredAbsoluteOption(b, "java-home", "JDK 21 home");
  const generated_jni = requiredAbsoluteOption(b, "generated-jni", "generated JNI root");
  const output_root = requiredAbsoluteOption(b, "output-root", "generated native resource root");
  const macos_sdk = requiredAbsoluteOption(b, "macos-sdk", "macOS SDK root");
  const rcodesign = requiredAbsoluteOption(b, "rcodesign", "rcodesign executable");
  validateToolInputs(b, java_home, generated_jni, macos_sdk, rcodesign);

  const manifest_bytes = std.Io.Dir.cwd().readFileAlloc(
    b.graph.io,
    manifest_name,
    b.allocator,
    .limited(manifest_limit + 1),
  ) catch |err| fatal("cannot read {s}: {s}", .{ manifest_name, @errorName(err) });
  if (manifest_bytes.len > manifest_limit) fatal("{s} exceeds {d} bytes", .{ manifest_name, manifest_limit });
  validateManifestEncoding(manifest_bytes);
  validateJsonDepthAndNulls(b, manifest_bytes);

  const manifest = std.json.parseFromSliceLeaky(Manifest, b.allocator, manifest_bytes, .{
    .duplicate_field_behavior = .@"error",
    .ignore_unknown_fields = false,
    .allocate = .alloc_always,
  }) catch |err| fatal("invalid {s}: {s}", .{ manifest_name, @errorName(err) });
  validateManifest(b, &manifest);

  const declarations_dir = b.pathJoin(&.{ generated_jni, "declarations" });
  validateJniHeaderInventory(b, declarations_dir);

  const jni_files = b.addWriteFiles();
  const cached_jni_h = jni_files.addCopyFile(.{ .cwd_relative = b.pathJoin(&.{ java_home, "include", "jni.h" }) }, "jni.h");
  const cached_jni_md_h = jni_files.addCopyFile(b.path("include/jni_md.h"), "jni_md.h");
  const generated_jni_include = b.pathJoin(&.{ generated_jni, "include" });
  const stage_jni_headers = StageJniHeaders.create(b, cached_jni_h, cached_jni_md_h, generated_jni_include);

  b.resolveInstallPrefix(output_root, .{});

  const signer_version = b.addSystemCommand(&.{ rcodesign, "--version" });
  signer_version.expectStdOutEqual("apple-codesign 0.29.0\n");

  const common_flags = &.{
    "-fno-exceptions",
    "-fno-rtti",
    "-fvisibility=hidden",
  };

  for (manifest.products) |product| {
    const component = findComponent(&manifest, product.component);
    const architecture = findArchitecture(&manifest, product.architecture);
    const policy = findGatePolicy(&manifest, product.gatePolicy);
    const sources = discoverSources(b, &manifest.sourceRules, component);
    const target_query = std.Target.Query.parse(.{ .arch_os_abi = product.zigTarget }) catch |err|
      fatal("product {s} has invalid Zig target {s}: {s}", .{ product.id, product.zigTarget, @errorName(err) });

    const library = b.addLibrary(.{
      .name = product.id,
      .root_module = b.createModule(.{
        .target = b.resolveTargetQuery(target_query),
        .optimize = .ReleaseSafe,
        .link_libc = true,
        .link_libcpp = false,
        .strip = true,
        .pic = true,
        .stack_protector = true,
        .stack_check = true,
        .omit_frame_pointer = false,
        .unwind_tables = .async,
        .sanitize_c = .trap,
      }),
      .linkage = .dynamic,
      .use_llvm = true,
      .use_lld = !std.mem.eql(u8, component.os, "macos"),
    });
    library.bundle_compiler_rt = false;
    library.lto = .none;
    library.link_z_relro = true;
    library.link_z_lazy = false;
    library.link_z_defs = true;
    library.linker_allow_shlib_undefined = false;
    library.linker_dynamicbase = true;
    library.dll_export_fns = false;
    library.step.dependOn(&stage_jni_headers.step);

    library.root_module.addIncludePath(.{ .cwd_relative = generated_jni_include });
    library.root_module.addIncludePath(.{ .cwd_relative = declarations_dir });
    for (component.sourceRoots) |root| library.root_module.addIncludePath(b.path(root));
    for (sources) |source| {
      library.root_module.addCSourceFile(.{
        .file = b.path(source),
        .flags = common_flags,
        .language = .cpp,
      });
    }

    if (std.mem.eql(u8, component.os, "macos")) {
      library.root_module.addSystemIncludePath(.{ .cwd_relative = b.pathJoin(&.{ macos_sdk, "usr", "include" }) });
      library.root_module.addLibraryPath(.{ .cwd_relative = b.pathJoin(&.{ macos_sdk, "usr", "lib" }) });
      library.install_name = expandInstallName(b, policy.installNameTemplate.?, outputFilename(b, component, architecture));
      library.entitlements = b.path("include/empty-entitlements.plist").getPath(b);
    } else if (std.mem.eql(u8, component.os, "windows")) {
      // The JNI library has no C/C++ static initialization. Omitting the
      // MinGW CRT DLL entry avoids its stdio-based pseudo-relocator.
      library.entry = .disabled;
    }

    const staged_source = if (std.mem.eql(u8, component.os, "macos")) blk: {
      const sign = b.addSystemCommand(&.{
        rcodesign,
        "sign",
        "--binary-identifier",
        product.signingIdentifier.?,
        "--code-signature-flags",
        "runtime",
        "--timestamp-url",
        "none",
      });
      sign.step.dependOn(&signer_version.step);
      // rcodesign reports normal signing progress on stderr. Declare it
      // expected so Zig does not render a successful run as a warning.
      sign.expectStdErrMatch("signing ");
      sign.expectExitCode(0);
      sign.addFileArg(library.getEmittedBin());
      const signed = sign.addOutputFileArg(outputFilename(b, component, architecture));

      const inspect = b.addSystemCommand(&.{ rcodesign, "print-signature-info" });
      inspect.step.dependOn(&signer_version.step);
      inspect.addFileArg(signed);
      inspect.expectStdOutMatch(product.signingIdentifier.?);
      inspect.expectStdOutMatch("CodeDirectory");
      inspect.expectStdOutMatch("sha256");
      inspect.expectStdOutMatch("CodeSignatureFlags(ADHOC | RUNTIME)");

      const install = b.addInstallFile(signed, product.resourcePath);
      install.step.dependOn(&inspect.step);
      b.getInstallStep().dependOn(&install.step);
      break :blk signed;
    } else blk: {
      const emitted = library.getEmittedBin();
      const install = b.addInstallFile(emitted, product.resourcePath);
      b.getInstallStep().dependOn(&install.step);
      break :blk emitted;
    };
    _ = staged_source;
  }

  const catalog = generateCatalog(b, &manifest);
  if (catalog.len > catalog_limit) fatal("generated catalog exceeds {d} bytes", .{catalog_limit});
  const catalog_files = b.addWriteFiles();
  const catalog_source = catalog_files.add(catalog_path, catalog);
  const catalog_install = b.addInstallFile(catalog_source, catalog_path);
  b.getInstallStep().dependOn(&catalog_install.step);
}

fn requiredAbsoluteOption(b: *std.Build, name: []const u8, description: []const u8) []const u8 {
  const value = b.option([]const u8, name, description) orelse fatal("missing -D{s}", .{name});
  if (value.len == 0 or !std.fs.path.isAbsolute(value)) fatal("-D{s} must be a nonempty absolute path", .{name});
  return value;
}

fn validateToolInputs(
  b: *std.Build,
  java_home: []const u8,
  generated_jni: []const u8,
  macos_sdk: []const u8,
  rcodesign: []const u8,
) void {
  requireKind(b, b.pathJoin(&.{ java_home, "include", "jni.h" }), .file, "JDK jni.h");
  const release_path = b.pathJoin(&.{ java_home, "release" });
  requireKind(b, release_path, .file, "JDK release metadata");
  const release = std.Io.Dir.cwd().readFileAlloc(b.graph.io, release_path, b.allocator, .limited(64 * 1024)) catch |err|
    fatal("cannot read JDK release metadata: {s}", .{@errorName(err)});
  if (std.mem.indexOf(u8, release, "JAVA_VERSION=\"21.") == null) {
    fatal("-Djava-home must identify a JDK 21 installation", .{});
  }
  requireKind(b, generated_jni, .directory, "generated JNI root");
  requireKind(b, b.pathJoin(&.{ macos_sdk, "usr" }), .directory, "macOS SDK usr tree");
  requireKind(b, b.pathJoin(&.{ macos_sdk, "System" }), .directory, "macOS SDK System tree");
  requireKind(b, rcodesign, .file, "rcodesign executable");
  const stat = std.Io.Dir.cwd().statFile(b.graph.io, rcodesign, .{ .follow_symlinks = false }) catch |err|
    fatal("cannot inspect rcodesign: {s}", .{@errorName(err)});
  if (std.Io.File.Permissions.has_executable_bit and (@intFromEnum(stat.permissions) & 0o111) == 0) {
    fatal("rcodesign is not executable: {s}", .{rcodesign});
  }
}

fn requireKind(b: *std.Build, path: []const u8, expected: std.Io.File.Kind, label: []const u8) void {
  const stat = std.Io.Dir.cwd().statFile(b.graph.io, path, .{ .follow_symlinks = false }) catch |err|
    fatal("missing or unreadable {s} at {s}: {s}", .{ label, path, @errorName(err) });
  if (stat.kind != expected) fatal("{s} is not a {s}: {s}", .{ label, @tagName(expected), path });
}

fn validateManifestEncoding(bytes: []const u8) void {
  if (bytes.len == 0 or bytes[bytes.len - 1] != '\n') fatal("{s} must end with one LF", .{manifest_name});
  if (bytes.len >= 2 and bytes[bytes.len - 2] == '\n') fatal("{s} must end with exactly one newline", .{manifest_name});
  if (std.mem.startsWith(u8, bytes, "\xef\xbb\xbf")) fatal("{s} must not contain a BOM", .{manifest_name});
  if (std.mem.indexOfScalar(u8, bytes, '\r') != null) fatal("{s} must use LF line endings", .{manifest_name});
  if (!std.unicode.utf8ValidateSlice(bytes)) fatal("{s} is not valid UTF-8", .{manifest_name});
}

fn validateJsonDepthAndNulls(b: *std.Build, bytes: []const u8) void {
  const root = std.json.parseFromSliceLeaky(std.json.Value, b.allocator, bytes, .{
    .duplicate_field_behavior = .@"error",
    .allocate = .alloc_always,
  }) catch |err| fatal("invalid {s}: {s}", .{ manifest_name, @errorName(err) });
  validateJsonValue(root, 1, "$");
}

fn validateJsonValue(value: std.json.Value, depth: usize, path: []const u8) void {
  if (depth > 16) fatal("{s} exceeds maximum JSON depth at {s}", .{ manifest_name, path });
  switch (value) {
    .null => fatal("null is not allowed in {s} at {s}", .{ manifest_name, path }),
    .array => |array| for (array.items) |child| validateJsonValue(child, depth + 1, path),
    .object => |object| {
      var iterator = object.iterator();
      while (iterator.next()) |entry| validateJsonValue(entry.value_ptr.*, depth + 1, entry.key_ptr.*);
    },
    else => {},
  }
}

fn validateManifest(b: *std.Build, manifest: *const Manifest) void {
  if (manifest.schemaVersion != 1) fatal("unsupported manifest schemaVersion {d}", .{manifest.schemaVersion});
  if (!manifest.sourceRules.recursive or manifest.sourceRules.followSymlinks) {
    fatal("sourceRules must require recursive discovery without following symlinks", .{});
  }
  validateExtensionSet(manifest.sourceRules.compiledExtensions, "compiledExtensions");
  validateExtensionSet(manifest.sourceRules.passiveExtensions, "passiveExtensions");
  for (manifest.sourceRules.compiledExtensions) |compiled| for (manifest.sourceRules.passiveExtensions) |passive| {
    if (std.mem.eql(u8, compiled, passive)) fatal("source extension appears in both compiled and passive sets: {s}", .{compiled});
  };

  if (manifest.operatingSystems.len == 0 or manifest.architectures.len == 0 or manifest.components.len == 0 or
    manifest.gatePolicies.len == 0 or manifest.products.len == 0)
    {
      fatal("manifest inventories must be nonempty", .{});
    }

  for (manifest.operatingSystems, 0..) |os, index| {
    validateId(os.id, "operating system id");
    ensureUniqueId(OperatingSystem, manifest.operatingSystems, index, os.id, "operating system");
    if (os.runtimeAliases.len == 0) fatal("operating system {s} has no runtime aliases", .{os.id});
    for (os.runtimeAliases, 0..) |alias, alias_index| {
      if (!std.mem.eql(u8, alias.match, "exact") and !std.mem.eql(u8, alias.match, "prefix")) {
        fatal("operating system {s} has invalid alias match {s}", .{ os.id, alias.match });
      }
      validateOsAlias(alias.value, os.id);
      for (os.runtimeAliases[0..alias_index]) |prior| if (std.mem.eql(u8, alias.value, prior.value) and
        std.mem.eql(u8, alias.match, prior.match))
        {
          fatal("operating system {s} repeats alias {s}", .{ os.id, alias.value });
        };
      for (manifest.operatingSystems[0..index]) |prior_os| for (prior_os.runtimeAliases) |prior| {
        if (aliasesOverlap(alias, prior)) fatal("ambiguous OS aliases {s} and {s}", .{ alias.value, prior.value });
      };
    }
  }

  for (manifest.architectures, 0..) |architecture, index| {
    validateId(architecture.id, "architecture id");
    validateArchitectureAlias(architecture.zig, architecture.id);
    ensureUniqueId(Architecture, manifest.architectures, index, architecture.id, "architecture");
    if (architecture.runtimeAliases.len == 0) fatal("architecture {s} has no aliases", .{architecture.id});
    for (architecture.runtimeAliases, 0..) |alias, alias_index| {
      validateArchitectureAlias(alias, architecture.id);
      for (architecture.runtimeAliases[0..alias_index]) |prior| if (std.mem.eql(u8, alias, prior))
        fatal("architecture {s} repeats alias {s}", .{ architecture.id, alias });
      for (manifest.architectures[0..index]) |prior_arch| for (prior_arch.runtimeAliases) |prior| {
        if (std.mem.eql(u8, alias, prior)) fatal("architecture alias {s} is ambiguous", .{alias});
      };
    }
  }

  for (manifest.components, 0..) |component, index| {
    validateId(component.id, "component id");
    ensureUniqueId(Component, manifest.components, index, component.id, "component");
    _ = findOperatingSystem(manifest, component.os);
    if (component.sourceRoots.len == 0) fatal("component {s} has no source roots", .{component.id});
    for (component.sourceRoots, 0..) |root, root_index| {
      validatePath(root, "source root");
      for (component.sourceRoots[0..root_index]) |prior| if (std.mem.eql(u8, root, prior))
        fatal("component {s} repeats source root {s}", .{ component.id, root });
    }
    validateFilenamePart(component.outputStem, "component output stem");
    validateFilenamePart(component.extension, "component extension");
    _ = discoverSources(b, &manifest.sourceRules, &component);
  }

  for (manifest.gatePolicies, 0..) |policy, index| {
    validateId(policy.id, "gate policy id");
    ensureUniqueId(GatePolicy, manifest.gatePolicies, index, policy.id, "gate policy");
    validateStringSet(policy.allowedLibraries, policy.id, "allowedLibraries");
    validateStringSet(policy.forbiddenLibraryFragments, policy.id, "forbiddenLibraryFragments");
    if (std.mem.eql(u8, policy.format, "elf")) {
      if (policy.maximumSymbolVersion == null or policy.installNameTemplate != null or
        policy.minimumDeploymentTarget != null or policy.minimumRuntime != null)
        fatal("ELF gate policy {s} has an invalid field set", .{policy.id});
    } else if (std.mem.eql(u8, policy.format, "macho")) {
      if (policy.maximumSymbolVersion != null or policy.installNameTemplate == null or
        policy.minimumDeploymentTarget == null or policy.minimumRuntime != null)
        fatal("Mach-O gate policy {s} has an invalid field set", .{policy.id});
    } else if (std.mem.eql(u8, policy.format, "pe")) {
      if (policy.maximumSymbolVersion != null or policy.installNameTemplate != null or
        policy.minimumDeploymentTarget != null or policy.minimumRuntime == null)
        fatal("PE gate policy {s} has an invalid field set", .{policy.id});
    } else fatal("gate policy {s} has invalid format {s}", .{ policy.id, policy.format });
  }

  for (manifest.products, 0..) |product, index| {
    validateId(product.id, "product id");
    ensureUniqueId(Product, manifest.products, index, product.id, "product");
    const component = findComponent(manifest, product.component);
    const architecture = findArchitecture(manifest, product.architecture);
    _ = findGatePolicy(manifest, product.gatePolicy);
    validatePath(product.resourcePath, "product resource path");
    if (product.buildOrder == 0 or product.loadOrder == 0) fatal("product {s} orders must be positive", .{product.id});
    const expected_filename = outputFilename(b, component, architecture);
    if (!std.mem.eql(u8, std.fs.path.basename(product.resourcePath), expected_filename)) {
      fatal("product {s} filename must be {s}", .{ product.id, expected_filename });
    }
    if (!std.mem.startsWith(u8, product.zigTarget, architecture.zig) or
      product.zigTarget.len == architecture.zig.len or product.zigTarget[architecture.zig.len] != '-')
      {
        fatal("product {s} target does not match architecture {s}", .{ product.id, architecture.id });
      }
    if (std.mem.eql(u8, component.os, "linux")) {
      if (!std.mem.eql(u8, product.libc, "glibc") and !std.mem.eql(u8, product.libc, "musl"))
        fatal("Linux product {s} must select glibc or musl", .{product.id});
      if (product.signingIdentifier != null) fatal("Linux product {s} must not have signingIdentifier", .{product.id});
    } else {
      if (!std.mem.eql(u8, product.libc, "none")) fatal("non-Linux product {s} must use libc=none", .{product.id});
      if (std.mem.eql(u8, component.os, "macos")) {
        const identifier = product.signingIdentifier orelse fatal("macOS product {s} needs signingIdentifier", .{product.id});
        validateSigningIdentifier(identifier, product.id);
      } else if (product.signingIdentifier != null) fatal("product {s} must not have signingIdentifier", .{product.id});
    }
    for (manifest.products[0..index]) |prior| {
      if (std.mem.eql(u8, product.zigTarget, prior.zigTarget)) fatal("products repeat target {s}", .{product.zigTarget});
      if (std.mem.eql(u8, product.resourcePath, prior.resourcePath)) fatal("products repeat resource path {s}", .{product.resourcePath});
      if (product.buildOrder == prior.buildOrder) fatal("products repeat buildOrder {d}", .{product.buildOrder});
      if (std.mem.eql(u8, product.component, prior.component) and
        std.mem.eql(u8, product.architecture, prior.architecture) and
        std.mem.eql(u8, product.libc, prior.libc))
        fatal("products repeat component/architecture/libc combination", .{});
      const prior_component = findComponent(manifest, prior.component);
      if (std.mem.eql(u8, component.os, prior_component.os) and
        std.mem.eql(u8, product.architecture, prior.architecture) and product.loadOrder == prior.loadOrder)
        fatal("products repeat loadOrder for {s}/{s}", .{ component.os, product.architecture });
    }
  }
}

fn validateExtensionSet(extensions: []const []const u8, label: []const u8) void {
  if (extensions.len == 0) fatal("{s} must be nonempty", .{label});
  for (extensions, 0..) |extension, index| {
    if (extension.len < 2 or extension[0] != '.') fatal("invalid extension in {s}: {s}", .{ label, extension });
    validateFilenamePart(extension[1..], label);
    for (extensions[0..index]) |prior| if (std.mem.eql(u8, extension, prior))
      fatal("duplicate extension in {s}: {s}", .{ label, extension });
  }
}

fn validateStringSet(values: []const []const u8, owner: []const u8, label: []const u8) void {
  if (values.len == 0) fatal("{s} {s} must be nonempty", .{ owner, label });
  for (values, 0..) |value, index| {
    validateCatalogField(value, label);
    for (values[0..index]) |prior| if (std.mem.eql(u8, value, prior))
      fatal("{s} repeats {s} value {s}", .{ owner, label, value });
  }
}

fn validateId(value: []const u8, label: []const u8) void {
  if (value.len == 0 or value.len > 64 or value[0] < 'a' or value[0] > 'z') fatal("invalid {s}: {s}", .{ label, value });
  for (value[1..]) |character| if (!isLowerAlphaNumeric(character) and character != '-')
    fatal("invalid {s}: {s}", .{ label, value });
}

fn validateFilenamePart(value: []const u8, label: []const u8) void {
  if (value.len == 0) fatal("empty {s}", .{label});
  for (value) |character| if (!isAsciiPathCharacter(character) or character == '/')
    fatal("invalid {s}: {s}", .{ label, value });
}

fn validatePath(value: []const u8, label: []const u8) void {
  if (value.len == 0 or std.fs.path.isAbsolute(value) or value[0] == '/' or value[value.len - 1] == '/')
    fatal("invalid {s}: {s}", .{ label, value });
  var components = std.mem.splitScalar(u8, value, '/');
  while (components.next()) |component| {
    if (component.len == 0 or std.mem.eql(u8, component, ".") or std.mem.eql(u8, component, ".."))
      fatal("non-normalized {s}: {s}", .{ label, value });
    for (component) |character| if (!isAsciiPathCharacter(character) or character == '/')
      fatal("invalid {s}: {s}", .{ label, value });
  }
}

fn validateOsAlias(value: []const u8, owner: []const u8) void {
  if (value.len == 0 or value[0] == ' ' or value[value.len - 1] == ' ') fatal("invalid OS alias for {s}: {s}", .{ owner, value });
  var previous_space = false;
  for (value) |character| {
    const valid = isLowerAlphaNumeric(character) or character == '-' or character == ' ';
    if (!valid or (character == ' ' and previous_space)) fatal("invalid OS alias for {s}: {s}", .{ owner, value });
    previous_space = character == ' ';
  }
}

fn validateArchitectureAlias(value: []const u8, owner: []const u8) void {
  if (value.len == 0) fatal("empty architecture alias for {s}", .{owner});
  for (value) |character| if (!isLowerAlphaNumeric(character) and character != '-' and character != '_')
    fatal("invalid architecture alias for {s}: {s}", .{ owner, value });
}

fn validateSigningIdentifier(value: []const u8, owner: []const u8) void {
  if (value.len == 0 or value.len > 255 or value[0] == '.' or value[value.len - 1] == '.')
    fatal("invalid signing identifier for {s}", .{owner});
  var previous_dot = false;
  for (value) |character| {
    const valid = std.ascii.isAlphanumeric(character) or character == '.' or character == '-';
    if (!valid or (character == '.' and previous_dot)) fatal("invalid signing identifier for {s}", .{owner});
    previous_dot = character == '.';
  }
}

fn validateCatalogField(value: []const u8, label: []const u8) void {
  if (value.len == 0 or std.ascii.isWhitespace(value[0]) or std.ascii.isWhitespace(value[value.len - 1]))
    fatal("invalid empty or padded {s}", .{label});
  for (value) |character| if (character == '\t' or character == '\r' or character == '\n' or character == 0)
    fatal("invalid control character in {s}", .{label});
}

fn aliasesOverlap(left: RuntimeAlias, right: RuntimeAlias) bool {
  if (std.mem.eql(u8, left.match, "exact") and std.mem.eql(u8, right.match, "exact"))
    return std.mem.eql(u8, left.value, right.value);
  if (std.mem.eql(u8, left.match, "prefix") and std.mem.eql(u8, right.match, "prefix"))
    return std.mem.startsWith(u8, left.value, right.value) or std.mem.startsWith(u8, right.value, left.value);
  const exact = if (std.mem.eql(u8, left.match, "exact")) left.value else right.value;
  const prefix = if (std.mem.eql(u8, left.match, "prefix")) left.value else right.value;
  return std.mem.startsWith(u8, exact, prefix);
}

fn ensureUniqueId(comptime T: type, values: []const T, index: usize, id: []const u8, label: []const u8) void {
  for (values[0..index]) |prior| if (std.mem.eql(u8, id, prior.id)) fatal("duplicate {s} id {s}", .{ label, id });
}

fn findOperatingSystem(manifest: *const Manifest, id: []const u8) *const OperatingSystem {
  for (manifest.operatingSystems) |*os| if (std.mem.eql(u8, os.id, id)) return os;
  fatal("unknown operating system {s}", .{id});
}

fn findArchitecture(manifest: *const Manifest, id: []const u8) *const Architecture {
  for (manifest.architectures) |*architecture| if (std.mem.eql(u8, architecture.id, id)) return architecture;
  fatal("unknown architecture {s}", .{id});
}

fn findComponent(manifest: *const Manifest, id: []const u8) *const Component {
  for (manifest.components) |*component| if (std.mem.eql(u8, component.id, id)) return component;
  fatal("unknown component {s}", .{id});
}

fn findGatePolicy(manifest: *const Manifest, id: []const u8) *const GatePolicy {
  for (manifest.gatePolicies) |*policy| if (std.mem.eql(u8, policy.id, id)) return policy;
  fatal("unknown gate policy {s}", .{id});
}

fn discoverSources(b: *std.Build, rules: *const SourceRules, component: *const Component) []const []const u8 {
  var sources: std.ArrayList([]const u8) = .empty;
  var all_files: std.ArrayList([]const u8) = .empty;
  for (component.sourceRoots) |root| {
    var directory = std.Io.Dir.cwd().openDir(b.graph.io, root, .{ .iterate = true, .follow_symlinks = false }) catch |err|
      fatal("component {s} source root {s} is missing or unreadable: {s}", .{ component.id, root, @errorName(err) });
    defer directory.close(b.graph.io);
    var walker = directory.walk(b.allocator) catch @panic("OOM");
    defer walker.deinit();
    var root_file_count: usize = 0;
    while (walker.next(b.graph.io) catch |err| fatal("cannot walk source root {s}: {s}", .{ root, @errorName(err) })) |entry| {
      const relative = b.pathJoin(&.{ root, entry.path });
      if (entry.kind == .directory) continue;
      if (entry.kind != .file) fatal("component {s} source root contains non-regular entry {s}", .{ component.id, relative });
      validatePath(relative, "native source path");
      const extension = std.fs.path.extension(relative);
      const compiled = containsString(rules.compiledExtensions, extension);
      const passive = containsString(rules.passiveExtensions, extension);
      if (!compiled and !passive) fatal("component {s} has unrecognized source file {s}", .{ component.id, relative });
      for (all_files.items) |prior| if (std.mem.eql(u8, prior, relative))
        fatal("component {s} discovers source twice: {s}", .{ component.id, relative });
      const owned = b.dupe(relative);
      all_files.append(b.allocator, owned) catch @panic("OOM");
      root_file_count += 1;
      if (compiled) sources.append(b.allocator, owned) catch @panic("OOM");
    }
    if (root_file_count == 0) fatal("component {s} source root {s} is empty", .{ component.id, root });
  }
  if (sources.items.len == 0) fatal("component {s} has no compiled sources", .{component.id});
  std.mem.sort([]const u8, sources.items, {}, lessUtf8);
  return b.allocator.dupe([]const u8, sources.items) catch @panic("OOM");
}

fn validateJniHeaderInventory(b: *std.Build, declarations_dir: []const u8) void {
  var directory = std.Io.Dir.cwd().openDir(b.graph.io, declarations_dir, .{ .iterate = true, .follow_symlinks = false }) catch |err|
    fatal("generated JNI declaration directory is missing: {s}", .{@errorName(err)});
  defer directory.close(b.graph.io);
  var found: std.ArrayList([]const u8) = .empty;
  var iterator = directory.iterate();
  while (iterator.next(b.graph.io) catch |err| fatal("cannot inspect generated JNI declarations: {s}", .{@errorName(err)})) |entry| {
    if (entry.kind != .file) fatal("unexpected non-file in generated JNI declarations: {s}", .{entry.name});
    found.append(b.allocator, b.dupe(entry.name)) catch @panic("OOM");
  }
  std.mem.sort([]const u8, found.items, {}, lessUtf8);
  const expected = b.allocator.dupe([]const u8, &expected_jni_headers) catch @panic("OOM");
  std.mem.sort([]const u8, expected, {}, lessUtf8);
  if (found.items.len != expected.len) fatal("expected {d} generated JNI headers, found {d}", .{ expected.len, found.items.len });
  for (found.items, expected) |actual, wanted| if (!std.mem.eql(u8, actual, wanted))
    fatal("unexpected generated JNI header {s}; expected {s}", .{ actual, wanted });
}

fn outputFilename(b: *std.Build, component: *const Component, architecture: *const Architecture) []const u8 {
  return b.fmt("{s}_{s}.{s}", .{ component.outputStem, architecture.id, component.extension });
}

fn expandInstallName(b: *std.Build, template: []const u8, filename: []const u8) []const u8 {
  const marker = "{outputFilename}";
  const index = std.mem.indexOf(u8, template, marker) orelse fatal("install name template lacks {s}", .{marker});
  if (std.mem.indexOfPos(u8, template, index + marker.len, marker) != null) fatal("install name template repeats {s}", .{marker});
  return b.fmt("{s}{s}{s}", .{ template[0..index], filename, template[index + marker.len ..] });
}

fn generateCatalog(b: *std.Build, manifest: *const Manifest) []const u8 {
  var writer: std.Io.Writer.Allocating = .init(b.allocator);
  writer.writer.writeAll("schema\t1\n") catch @panic("OOM");

  var os_aliases: std.ArrayList(CatalogOsAlias) = .empty;
  for (manifest.operatingSystems) |os| for (os.runtimeAliases) |alias| {
    validateCatalogField(os.id, "catalog OS id");
    validateCatalogField(alias.match, "catalog OS match");
    validateCatalogField(alias.value, "catalog OS alias");
    os_aliases.append(b.allocator, .{ .canonical = os.id, .match_kind = alias.match, .value = alias.value }) catch @panic("OOM");
  };
  std.mem.sort(CatalogOsAlias, os_aliases.items, {}, lessCatalogOsAlias);
  for (os_aliases.items) |alias| writer.writer.print("os\t{s}\t{s}\t{s}\n", .{ alias.match_kind, alias.value, alias.canonical }) catch @panic("OOM");

  var arch_aliases: std.ArrayList(CatalogArchAlias) = .empty;
  for (manifest.architectures) |architecture| for (architecture.runtimeAliases) |alias| {
    validateCatalogField(alias, "catalog architecture alias");
    validateCatalogField(architecture.id, "catalog architecture id");
    arch_aliases.append(b.allocator, .{ .alias = alias, .canonical = architecture.id }) catch @panic("OOM");
  };
  std.mem.sort(CatalogArchAlias, arch_aliases.items, {}, lessCatalogArchAlias);
  for (arch_aliases.items) |alias| writer.writer.print("arch\t{s}\t{s}\n", .{ alias.alias, alias.canonical }) catch @panic("OOM");

  const products = b.allocator.dupe(Product, manifest.products) catch @panic("OOM");
  std.mem.sort(Product, products, manifest, lessCatalogProduct);
  for (products) |product| {
    const component = findComponent(manifest, product.component);
    writer.writer.print("product\t{s}\t{s}\t{s}\t{s}\t{d}\t/{s}\n", .{
      product.id,
      component.os,
      product.architecture,
      product.libc,
      product.loadOrder,
      product.resourcePath,
    }) catch @panic("OOM");
  }
  return writer.toOwnedSlice() catch @panic("OOM");
}

fn lessProductBuildOrder(_: void, left: Product, right: Product) bool {
  return left.buildOrder < right.buildOrder;
}

fn lessCatalogOsAlias(_: void, left: CatalogOsAlias, right: CatalogOsAlias) bool {
  const canonical_order = std.mem.order(u8, left.canonical, right.canonical);
  if (canonical_order != .eq) return canonical_order == .lt;
  const match_order = std.mem.order(u8, left.match_kind, right.match_kind);
  if (match_order != .eq) return match_order == .lt;
  return std.mem.order(u8, left.value, right.value) == .lt;
}

fn lessCatalogArchAlias(_: void, left: CatalogArchAlias, right: CatalogArchAlias) bool {
  return std.mem.order(u8, left.alias, right.alias) == .lt;
}

fn lessCatalogProduct(manifest: *const Manifest, left: Product, right: Product) bool {
  const left_component = findComponent(manifest, left.component);
  const right_component = findComponent(manifest, right.component);
  const left_os = indexOfOperatingSystem(manifest, left_component.os);
  const right_os = indexOfOperatingSystem(manifest, right_component.os);
  if (left_os != right_os) return left_os < right_os;
  const left_arch = indexOfArchitecture(manifest, left.architecture);
  const right_arch = indexOfArchitecture(manifest, right.architecture);
  if (left_arch != right_arch) return left_arch < right_arch;
  if (left.loadOrder != right.loadOrder) return left.loadOrder < right.loadOrder;
  return std.mem.order(u8, left.id, right.id) == .lt;
}

fn indexOfOperatingSystem(manifest: *const Manifest, id: []const u8) usize {
  for (manifest.operatingSystems, 0..) |os, index| if (std.mem.eql(u8, os.id, id)) return index;
  unreachable;
}

fn indexOfArchitecture(manifest: *const Manifest, id: []const u8) usize {
  for (manifest.architectures, 0..) |architecture, index| if (std.mem.eql(u8, architecture.id, id)) return index;
  unreachable;
}

fn containsString(values: []const []const u8, wanted: []const u8) bool {
  for (values) |value| if (std.mem.eql(u8, value, wanted)) return true;
  return false;
}

fn lessUtf8(_: void, left: []const u8, right: []const u8) bool {
  return std.mem.order(u8, left, right) == .lt;
}

fn isLowerAlphaNumeric(character: u8) bool {
  return (character >= 'a' and character <= 'z') or (character >= '0' and character <= '9');
}

fn isAsciiPathCharacter(character: u8) bool {
  return std.ascii.isAlphanumeric(character) or character == '.' or character == '_' or character == '-' or character == '/';
}

fn fatal(comptime format: []const u8, arguments: anytype) noreturn {
  std.debug.panic("native-manifest: " ++ format, arguments);
}
