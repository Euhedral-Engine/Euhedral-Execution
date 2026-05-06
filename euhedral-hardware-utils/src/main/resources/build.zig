const std = @import("std");

pub fn build(b: *std.Build) void {
    const java_home =
        if (std.process.getEnvVarOwned(b.allocator, "JAVA_HOME")) |val| val else |_| blk: {
            const result = std.process.Child.run(.{
                .allocator = b.allocator,
                .argv = &.{ "asdf", "which", "java" },
            }) catch break :blk b.allocator.dupe(u8, "/usr/lib/jvm/default") catch "/usr/lib/jvm/default";

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
            break :blk b.allocator.dupe(u8, "/usr/lib/jvm/default") catch "/usr/lib/jvm/default";
        };
    defer b.allocator.free(java_home);

    const macos_sdk =
        if (std.process.getEnvVarOwned(b.allocator, "MACOS_SDK")) |val| val else |_| blk: {
            const search_dirs = [_][]const u8{ "/opt", "/usr/local/SDK", "/usr/lib/apple/SDKs" };
            var highest_ver: u32 = 0;
            var highest_path: ?[]const u8 = null;

            for (search_dirs) |base_dir| {
                var dir = std.fs.cwd().openDir(base_dir, .{ .iterate = true }) catch continue;
                defer dir.close();

                var it = dir.iterate();
                while (it.next() catch null) |entry| {
                    if (entry.kind != .directory) continue;

                    if (std.mem.startsWith(u8, entry.name, "MacOSX") and std.mem.endsWith(u8, entry.name, ".sdk")) {
                        const ver_str = entry.name["MacOSX".len .. entry.name.len - ".sdk".len];

                        const parsed_ver =
                            if (std.mem.indexOfScalar(u8, ver_str, '.')) |dot_idx|
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

            if (highest_path) |path| {
                break :blk path;
            }
            break :blk b.allocator.dupe(u8, "/opt/MacOSX26.sdk") catch "/opt/MacOSX26.sdk";
        };
    defer b.allocator.free(macos_sdk);

    const common_flags = [_][]const u8{
        "-O3",
        "-fno-stack-check",
        "-fno-exceptions",
        "-fno-rtti",
        "-fvisibility=hidden",
    };

    const jni_include = b.pathJoin(&.{ java_home, "include" });
    const jni_win32 = b.pathJoin(&.{ jni_include, "win32" });
    const jni_darwin = b.pathJoin(&.{ jni_include, "darwin" });
    const jni_linux = b.pathJoin(&.{ jni_include, "linux" });

    const ArchMap = struct { target: []const u8, suffix: []const u8 };
    const arches = [_]ArchMap{
        .{ .target = "x86_64", .suffix = "x64" },
        .{ .target = "aarch64", .suffix = "arm64" },
    };

    const OsMap = struct { name: []const u8, dir: []const u8, out_dir: []const u8, ext: []const u8 };
    const targets = [_]OsMap{
        .{ .name = "linux", .dir = "./linux", .out_dir = "bin/linux", .ext = "so" },
        .{ .name = "osx", .dir = "./osx", .out_dir = "bin/osx", .ext = "dylib" },
        .{ .name = "windows", .dir = "./windows", .out_dir = "bin/windows", .ext = "dll" },
    };

    for (targets) |os| {
        var dir = std.fs.cwd().openDir(os.dir, .{ .iterate = true }) catch continue;
        defer dir.close();

        if (std.fs.cwd().openDir(os.out_dir, .{ .iterate = true })) |temp| {
            var bin_dir = temp;
            defer bin_dir.close();

            var bin_it = bin_dir.iterate();
            while (bin_it.next() catch null) |bin_entry| {
                if (bin_entry.kind != .file) continue;

                const bin_ext = std.fs.path.extension(bin_entry.name);
                if (!std.mem.eql(u8, bin_ext, b.fmt(".{s}", .{os.ext}))) continue;

                const bin_stem = std.fs.path.stem(bin_entry.name);

                const suffix_idx = std.mem.lastIndexOfScalar(u8, bin_stem, '_') orelse continue;
                const original_source_stem = bin_stem[0..suffix_idx];

                const expected_source_name = b.fmt("{s}.cpp", .{original_source_stem});

                dir.access(expected_source_name, .{}) catch {
                    std.debug.print("Source missing. Pruning stale binary: {s}/{s}\n", .{ os.out_dir, bin_entry.name });
                    bin_dir.deleteFile(bin_entry.name) catch {};
                };
            }
        } else |_| {}

        var it = dir.iterate();
        while (it.next() catch null) |entry| {
            if (entry.kind != .file) continue;

            const file_ext = std.fs.path.extension(entry.name);
            if (!std.mem.eql(u8, file_ext, ".cpp")) continue;

            const file_stem = std.fs.path.stem(entry.name);

            for (arches) |arch| {
                const target_str = if (std.mem.eql(u8, os.name, "windows"))
                    b.fmt("{s}-windows-gnu", .{arch.target})
                else if (std.mem.eql(u8, os.name, "linux"))
                    b.fmt("{s}-linux-musl", .{arch.target})
                else
                    b.fmt("{s}-macos.11.0", .{arch.target});

                const target_query = std.Target.Query.parse(.{ .arch_os_abi = target_str }) catch continue;
                const resolved_target = b.resolveTargetQuery(target_query);

                const mod = b.createModule(.{
                    .target = resolved_target,
                    .optimize = .ReleaseFast,
                });

                const lib = b.addLibrary(.{
                    .name = b.fmt("{s}_{s}", .{ file_stem, arch.suffix }),
                    .root_module = mod,
                    .linkage = .dynamic,
                });

                mod.addCSourceFile(.{
                    .file = b.path(b.pathJoin(&.{ os.dir, entry.name })),
                    .flags = &common_flags,
                });

                mod.link_libcpp = false;
                mod.link_libc = true;
                mod.strip = true;

                mod.addIncludePath(.{ .cwd_relative = jni_include });
                if (std.mem.eql(u8, os.name, "windows")) {
                    mod.addIncludePath(.{ .cwd_relative = jni_win32 });
                    mod.linkSystemLibrary("psapi", .{});
                    mod.linkSystemLibrary("kernel32", .{});
                } else if (std.mem.eql(u8, os.name, "linux")) {
                    mod.addIncludePath(.{ .cwd_relative = jni_linux });
                } else if (std.mem.eql(u8, os.name, "osx")) {
                    mod.addIncludePath(.{ .cwd_relative = jni_darwin });

                    const fw_path = b.pathJoin(&.{ macos_sdk, "System/Library/Frameworks" });
                    mod.addFrameworkPath(.{ .cwd_relative = fw_path });
                    mod.addSystemIncludePath(.{ .cwd_relative = b.pathJoin(&.{ macos_sdk, "usr/include" }) });
                    mod.addLibraryPath(.{ .cwd_relative = b.pathJoin(&.{ macos_sdk, "usr/lib" }) });
                    mod.linkFramework("CoreFoundation", .{});
                }

                const out_filename = b.fmt("{s}_{s}.{s}", .{ file_stem, arch.suffix, os.ext });
                const out_rel_path = b.pathJoin(&.{ os.out_dir, out_filename });

                const install = b.addInstallFileWithDir(
                    lib.getEmittedBin(),
                    .prefix,
                    out_rel_path,
                );

                b.getInstallStep().dependOn(&install.step);
            }
        }
    }
}
