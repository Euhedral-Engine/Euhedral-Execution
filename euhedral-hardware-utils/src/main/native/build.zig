const std = @import("std");

pub fn build(b: *std.Build) void {
    const optimize = .ReleaseFast;

    const java_home = if (b.graph.environ_map.get("JAVA_HOME")) |val| val else blk: {
        const result = std.process.run(
            b.allocator,
            b.graph.io,
            .{ .argv = &.{ "mise", "which", "java" } },
        ) catch {
            break :blk "/usr/lib/jvm/default";
        };

        defer b.allocator.free(result.stdout);
        defer b.allocator.free(result.stderr);

        if (result.stdout.len > 0) {
            const path = std.mem.trim(u8, result.stdout, " \n\r");
            if (std.fs.path.dirname(path)) |bin_dir| {
                if (std.fs.path.dirname(bin_dir)) |home_dir| {
                    break :blk b.allocator.dupe(u8, home_dir) catch "/usr/lib/jvm/default";
                }
            }
        }
        break :blk "/usr/lib/jvm/default";
    };

    const macos_sdk = blk: {
        if (b.graph.environ_map.get("MACOS_SDK")) |macos_sdk_env| {
            if (macos_sdk_env.len > 0) break :blk macos_sdk_env;
        }

        if (b.graph.environ_map.get("SDKROOT")) |sdkroot| {
            if (sdkroot.len > 0) {
                var check_dir = std.Io.Dir.cwd().openDir(b.graph.io, b.pathJoin(&.{ sdkroot, "usr", "include" }), .{}) catch null;
                if (check_dir) |*cd| {
                    cd.close(b.graph.io);
                    break :blk sdkroot;
                }
            }
        }

        var search_dirs = std.ArrayList([]const u8){
            .items = &.{},
            .capacity = 0,
        };

        if (b.graph.environ_map.get("SDKROOT")) |sdkroot| {
            if (sdkroot.len > 0) search_dirs.append(b.allocator, sdkroot) catch {};
        }

        search_dirs.appendSlice(b.allocator, &.{ "/opt", "/usr/local/SDK", "/usr/lib/apple/SDKs" }) catch {};

        var highest_ver: u32 = 0;
        var highest_path: ?[]const u8 = null;

        for (search_dirs.items) |base_dir| {
            var dir = std.Io.Dir.cwd().openDir(b.graph.io, base_dir, .{ .iterate = true }) catch continue;
            defer dir.close(b.graph.io);

            var it = dir.iterate();
            while (it.next(b.graph.io) catch null) |entry| {
                if (entry.kind != .directory) continue;

                if (std.mem.startsWith(u8, entry.name, "MacOSX") and std.mem.endsWith(u8, entry.name, ".sdk")) {
                    const ver_str = entry.name["MacOSX".len .. entry.name.len - ".sdk".len];

                    const parsed_ver = if (std.mem.indexOfScalar(u8, ver_str, '.')) |dot_idx|
                        std.fmt.parseInt(u32, ver_str[0..dot_idx], 10) catch 0
                    else
                        std.fmt.parseInt(u32, ver_str, 10) catch 0;

                    if (parsed_ver >= 11 and parsed_ver > highest_ver) {
                        highest_ver = parsed_ver;
                        if (highest_path) |p| b.allocator.free(p);
                        highest_path = b.pathJoin(&.{ base_dir, entry.name });
                    }
                }
            }
        }

        if (highest_path) |path| break :blk path;
        break :blk b.allocator.dupe(u8, "/opt/MacOSX.sdk") catch "/opt/MacOSX.sdk";
    };

    const macos_frameworks = b.pathJoin(&.{
        macos_sdk,
        "System/Library/Frameworks",
    });

    const macos_usr_include = b.pathJoin(&.{
        macos_sdk,
        "usr/include",
    });

    const macos_usr_lib = b.pathJoin(&.{
        macos_sdk,
        "usr/lib",
    });

    const sign_macos = b.option(
        bool,
        "sign-macos",
        "Sign macOS binaries",
    ) orelse false;

    const common_flags = [_][]const u8{
        "-O3",
        "-fno-exceptions",
        "-fno-rtti",
        "-fvisibility=hidden",
    };

    const jni_include = b.pathJoin(&.{ java_home, "include" });
    const jni_win32 = b.pathJoin(&.{ jni_include, "win32" });
    const jni_darwin = b.pathJoin(&.{ jni_include, "darwin" });
    const jni_linux = b.pathJoin(&.{ jni_include, "linux" });

    const arches = [_]struct {
        target: []const u8,
        suffix: []const u8,
    }{
        .{ .target = "x86_64", .suffix = "x64" },
        .{ .target = "aarch64", .suffix = "arm64" },
    };

    const libc_variants = [_]struct {
        name: []const u8,
        abi: []const u8,
    }{
        .{ .name = "glibc", .abi = "gnu.2.17" },
        .{ .name = "musl", .abi = "musl" },
    };

    const targets = [_]struct {
        name: []const u8,
        dir: []const u8,
        out_dir: []const u8,
        ext: []const u8,
    }{
        .{
            .name = "linux",
            .dir = "./linux",
            .out_dir = "bin/linux",
            .ext = "so",
        },
        .{
            .name = "osx",
            .dir = "./osx",
            .out_dir = "bin/osx",
            .ext = "dylib",
        },
        .{
            .name = "windows",
            .dir = "./windows",
            .out_dir = "bin/windows",
            .ext = "dll",
        },
    };

    for (targets) |os| {
        var cpp_files = std.ArrayList([]const u8){
            .items = &.{},
            .capacity = 0,
        };

        var dir = std.Io.Dir.cwd().openDir(
            b.graph.io,
            os.dir,
            .{ .iterate = true },
        ) catch continue;

        defer dir.close(b.graph.io);

        var it = dir.iterate();

        while (it.next(b.graph.io) catch null) |entry| {
            if (entry.kind == .file and
                std.mem.eql(
                    u8,
                    std.fs.path.extension(entry.name),
                    ".cpp",
                ))
                {
                    cpp_files.append(
                        b.allocator,
                        b.dupe(
                            b.pathJoin(&.{ os.dir, entry.name }),
                        ),
                    ) catch unreachable;
                }
        }

        if (cpp_files.items.len == 0) {
            continue;
        }

        for (arches) |arch| {
            const is_linux = std.mem.eql(u8, os.name, "linux");

            if (is_linux) {
                for (libc_variants) |libc| {
                    buildNative(
                        b,
                        optimize,
                        os,
                        arch,
                        libc.name,
                        libc.abi,
                        cpp_files.items,
                        common_flags[0..],
                        jni_include,
                        jni_linux,
                        jni_win32,
                        jni_darwin,
                        macos_frameworks,
                        macos_usr_include,
                        macos_usr_lib,
                        sign_macos,
                    );
                }
            } else {
                buildNative(
                    b,
                    optimize,
                    os,
                    arch,
                    null,
                    null,
                    cpp_files.items,
                    common_flags[0..],
                    jni_include,
                    jni_linux,
                    jni_win32,
                    jni_darwin,
                    macos_frameworks,
                    macos_usr_include,
                    macos_usr_lib,
                    sign_macos,
                );
            }
        }
    }
}

fn buildNative(
    b: *std.Build,
    optimize: std.builtin.OptimizeMode,
    os: anytype,
    arch: anytype,
    libc_name: ?[]const u8,
    libc_abi: ?[]const u8,
    cpp_files: []const []const u8,
    common_flags: []const []const u8,
    jni_include: []const u8,
    jni_linux: []const u8,
    jni_win32: []const u8,
    jni_darwin: []const u8,
    macos_frameworks: []const u8,
    macos_usr_include: []const u8,
    macos_usr_lib: []const u8,
    sign_macos: bool,
) void {
    const is_linux = std.mem.eql(u8, os.name, "linux");
    const is_windows = std.mem.eql(u8, os.name, "windows");
    const is_osx = std.mem.eql(u8, os.name, "osx");

    const target_str =
        if (is_windows)
            b.fmt("{s}-windows-gnu", .{arch.target})
        else if (is_linux)
            b.fmt("{s}-linux-{s}", .{
                arch.target,
                libc_abi.?,
            })
        else
            b.fmt("{s}-macos.11.0", .{
                arch.target,
            });

    const target_query = std.Target.Query.parse(.{
        .arch_os_abi = target_str,
    }) catch return;

    const lib_name =
        if (libc_name) |libc|
            b.fmt("{s}_jni_{s}_{s}", .{
                os.name,
                arch.suffix,
                libc,
            })
        else
            b.fmt("{s}_jni_{s}", .{
                os.name,
                arch.suffix,
            });

    const lib = b.addLibrary(.{
        .name = lib_name,
        .root_module = b.createModule(.{
            .target = b.resolveTargetQuery(target_query),
            .optimize = optimize,
            .link_libc = true,
            .link_libcpp = false,
            .strip = true,
            .pic = true,
            .stack_check = false,
            .stack_protector = false,
            .unwind_tables = .none,
            .omit_frame_pointer = true,
            .error_tracing = false,
            .sanitize_thread = false,
            .sanitize_c = .off,
            .valgrind = false,
            .code_model = .small,
            .red_zone = false,
        }),
        .linkage = .dynamic,
        .use_llvm = true,
    });

    lib.link_z_relro = true;
    lib.bundle_compiler_rt = true;

    for (cpp_files) |file_path| {
        lib.root_module.addCSourceFile(.{
            .file = b.path(file_path),
            .flags = common_flags,
            .language = .cpp,
        });
    }

    lib.root_module.addIncludePath(.{
        .cwd_relative = jni_include,
    });

    if (is_windows) {
        lib.root_module.addIncludePath(.{
            .cwd_relative = jni_win32,
        });

        lib.root_module.linkSystemLibrary("psapi", .{});
        lib.root_module.linkSystemLibrary("kernel32", .{});
    } else if (is_linux) {
        lib.root_module.addIncludePath(.{
            .cwd_relative = jni_linux,
        });
    } else if (is_osx) {
        lib.root_module.addIncludePath(.{
            .cwd_relative = jni_darwin,
        });

        lib.root_module.addFrameworkPath(.{
            .cwd_relative = macos_frameworks,
        });

        lib.root_module.addSystemIncludePath(.{
            .cwd_relative = macos_usr_include,
        });

        lib.root_module.addLibraryPath(.{
            .cwd_relative = macos_usr_lib,
        });

        lib.root_module.linkFramework(
            "CoreFoundation",
            .{},
        );

        lib.headerpad_max_install_names = true;
    }

    const out_dir =
        if (is_linux and libc_name != null)
            b.pathJoin(&.{
                os.out_dir,
                libc_name.?,
            })
        else
            os.out_dir;

    const out_filename = b.fmt(
        "{s}_jni_{s}.{s}",
        .{
            os.name,
            arch.suffix,
            os.ext,
        },
    );

    const install = b.addInstallFileWithDir(
        lib.getEmittedBin(),
        .prefix,
        b.pathJoin(&.{
            out_dir,
            out_filename,
        }),
    );

    if (is_osx and sign_macos) {
        const sign_cmd = b.addSystemCommand(&.{
            "rcodesign",
            "sign",
        });

        sign_cmd.addFileArg(lib.getEmittedBin());

        install.step.dependOn(&lib.step);
        sign_cmd.step.dependOn(&install.step);
        b.getInstallStep().dependOn(&sign_cmd.step);
    } else {
        b.getInstallStep().dependOn(&install.step);
    }

}
