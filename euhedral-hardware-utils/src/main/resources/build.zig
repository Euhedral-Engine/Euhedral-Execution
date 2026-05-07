const std = @import("std");

pub fn build(b: *std.Build) void {
    const java_home = if (b.graph.environ_map.get("JAVA_HOME")) |val| val else blk: {
        const result = std.process.run(
            b.allocator,
            b.graph.io,
            .{ .argv = &.{ "asdf", "which", "java" } },
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

    const macos_sdk = if (b.graph.environ_map.get("MACOS_SDK")) |val| val else blk: {
        const search_dirs = [_][]const u8{ "/opt", "/usr/local/SDK", "/usr/lib/apple/SDKs" };
        var highest_ver: u32 = 0;
        var highest_path: ?[]const u8 = null;

        for (search_dirs) |base_dir| {
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
        break :blk b.allocator.dupe(u8, "/opt/MacOSX26.sdk") catch "/opt/MacOSX26.sdk";
    };

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

    const arches = [_]struct { target: []const u8, suffix: []const u8 }{
        .{ .target = "x86_64", .suffix = "x64" },
        .{ .target = "aarch64", .suffix = "arm64" },
    };

    const targets = [_]struct { name: []const u8, dir: []const u8, out_dir: []const u8, ext: []const u8 }{
        .{ .name = "linux", .dir = "./linux", .out_dir = "bin/linux", .ext = "so" },
        .{ .name = "osx", .dir = "./osx", .out_dir = "bin/osx", .ext = "dylib" },
        .{ .name = "windows", .dir = "./windows", .out_dir = "bin/windows", .ext = "dll" },
    };

    for (targets) |os| {
        var dir = std.Io.Dir.cwd().openDir(b.graph.io, os.dir, .{ .iterate = true }) catch continue;
        defer dir.close(b.graph.io);

        if (std.Io.Dir.cwd().openDir(b.graph.io, os.out_dir, .{ .iterate = true })) |bin_dir| {
            defer bin_dir.close(b.graph.io);
            var bin_it = bin_dir.iterate();
            while (bin_it.next(b.graph.io) catch null) |bin_entry| {
                if (bin_entry.kind != .file) continue;
                const bin_ext = std.fs.path.extension(bin_entry.name);
                if (!std.mem.eql(u8, bin_ext, b.fmt(".{s}", .{os.ext}))) continue;

                const bin_stem = std.fs.path.stem(bin_entry.name);
                const suffix_idx = std.mem.lastIndexOfScalar(u8, bin_stem, '_') orelse continue;
                const original_source_stem = bin_stem[0..suffix_idx];
                const expected_source_name = b.fmt("{s}.cpp", .{original_source_stem});

                dir.access(b.graph.io, expected_source_name, .{}) catch {
                    bin_dir.deleteFile(b.graph.io, bin_entry.name) catch {};
                };
            }
        } else |_| {}

        var it = dir.iterate();
        while (it.next(b.graph.io) catch null) |entry| {
            if (entry.kind != .file) continue;
            if (!std.mem.eql(u8, std.fs.path.extension(entry.name), ".cpp")) continue;
            const file_stem = std.fs.path.stem(entry.name);

            for (arches) |arch| {
                const target_str = if (std.mem.eql(u8, os.name, "windows"))
                    b.fmt("{s}-windows-gnu", .{arch.target})
                else if (std.mem.eql(u8, os.name, "linux"))
                        b.fmt("{s}-linux-musl", .{arch.target})
                    else
                        b.fmt("{s}-macos.11.0", .{arch.target});

                const target_query = std.Target.Query.parse(.{ .arch_os_abi = target_str }) catch continue;

                const lib = b.addLibrary(.{
                    .name = b.fmt("{s}_{s}", .{ file_stem, arch.suffix }),
                    .root_module = b.createModule(.{
                        .target = b.resolveTargetQuery(target_query),
                        .optimize = .ReleaseFast,
                        .link_libc = true,
                    }),
                    .linkage = .dynamic,
                });

                lib.root_module.addCSourceFile(.{
                    .file = b.path(b.pathJoin(&.{ os.dir, entry.name })),
                    .flags = &common_flags,
                });

                lib.root_module.addIncludePath(.{ .cwd_relative = jni_include });

                if (std.mem.eql(u8, os.name, "windows")) {
                    lib.root_module.addIncludePath(.{ .cwd_relative = jni_win32 });
                    lib.root_module.linkSystemLibrary("psapi", .{});
                    lib.root_module.linkSystemLibrary("kernel32", .{});
                } else if (std.mem.eql(u8, os.name, "linux")) {
                    lib.root_module.addIncludePath(.{ .cwd_relative = jni_linux });
                } else if (std.mem.eql(u8, os.name, "osx")) {
                    lib.root_module.addIncludePath(.{ .cwd_relative = jni_darwin });
                    const fw_path = b.pathJoin(&.{ macos_sdk, "System/Library/Frameworks" });
                    lib.root_module.addFrameworkPath(.{ .cwd_relative = fw_path });
                    lib.root_module.addSystemIncludePath(.{ .cwd_relative = b.pathJoin(&.{ macos_sdk, "usr/include" }) });
                    lib.root_module.addLibraryPath(.{ .cwd_relative = b.pathJoin(&.{ macos_sdk, "usr/lib" }) });
                    lib.root_module.linkFramework("CoreFoundation", .{});
                }

                const out_filename = b.fmt("{s}_{s}.{s}", .{ file_stem, arch.suffix, os.ext });
                const install = b.addInstallFileWithDir(
                    lib.getEmittedBin(),
                    .prefix,
                    b.pathJoin(&.{ os.out_dir, out_filename }),
                );
                b.getInstallStep().dependOn(&install.step);
            }
        }
    }
}
