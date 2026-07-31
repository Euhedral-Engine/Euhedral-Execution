package io.euhedral_execution.hardware_utils.compatibility.helpers;

import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;
import static org.objectweb.asm.Opcodes.ACC_ANNOTATION;
import static org.objectweb.asm.Opcodes.ACC_BRIDGE;
import static org.objectweb.asm.Opcodes.ACC_ENUM;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_INTERFACE;
import static org.objectweb.asm.Opcodes.ACC_MANDATED;
import static org.objectweb.asm.Opcodes.ACC_MODULE;
import static org.objectweb.asm.Opcodes.ACC_NATIVE;
import static org.objectweb.asm.Opcodes.ACC_OPEN;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_RECORD;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC_PHASE;
import static org.objectweb.asm.Opcodes.ACC_STRICT;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ACC_TRANSIENT;
import static org.objectweb.asm.Opcodes.ACC_TRANSITIVE;
import static org.objectweb.asm.Opcodes.ACC_VARARGS;
import static org.objectweb.asm.Opcodes.ACC_VOLATILE;

import io.euhedral_execution.hardware_utils.compatibility.ApiSurface;
import io.euhedral_execution.hardware_utils.compatibility.ApiSurface.Entry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;

public final class ApiSurfaceReader {

    public static ApiSurface read(Path classesDirectory) throws IOException {
        if (!Files.isDirectory(classesDirectory) || !Files.isReadable(classesDirectory)) {
            throw new IOException(
                    "classes directory is missing or unreadable: " + classesDirectory);
        }
        List<Path> paths;
        try (Stream<Path> files = Files.walk(classesDirectory)) {
            paths = files.filter(path -> path.toString().endsWith(".class"))
                    .sorted(Comparator.comparing(
                            path -> classesDirectory.relativize(path).toString(),
                            ApiSurface.UTF8_ORDER))
                    .toList();
        }
        List<Path> moduleFiles = paths.stream()
                .filter(path -> classesDirectory.relativize(path).toString()
                        .equals("module-info.class"))
                .toList();
        if (moduleFiles.size() != 1) {
            throw new IOException(
                    "expected exactly one module-info.class, found " + moduleFiles.size());
        }

        List<Entry> entries = new ArrayList<>();
        Set<String> exports = readModule(moduleFiles.get(0), entries);
        if (exports.size() != 5) {
            throw new IOException(
                    "expected exactly five exported packages, found " + exports.size());
        }

        Map<String, int[]> innerAccess = new HashMap<>();
        Map<String, String> outerNames = new HashMap<>();
        for (Path path : paths) {
            if (path.equals(moduleFiles.get(0))) {
                continue;
            }
            new ClassReader(Files.readAllBytes(path)).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visitInnerClass(String name, String outerName, String innerName,
                        int access) {
                    if (outerName != null) {
                        innerAccess.put(name, new int[]{access});
                        outerNames.put(name, outerName);
                    }
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        List<ClassFile> classes = new ArrayList<>();
        for (Path path : paths) {
            if (path.equals(moduleFiles.get(0))) {
                continue;
            }
            ClassReader reader = new ClassReader(Files.readAllBytes(path));
            String name = reader.getClassName();
            String packageName = packageName(name);
            int access = reader.getAccess();
            classes.add(new ClassFile(path, name, access, packageName, outerNames.get(name),
                    innerAccess.getOrDefault(name, new int[]{access})[0]));
        }

        Map<String, ClassFile> byName = new HashMap<>();
        classes.forEach(type -> byName.put(type.name(), type));
        Set<String> included = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            for (ClassFile type : classes) {
                if (!exports.contains(type.packageName()) || included.contains(type.name())) {
                    continue;
                }
                boolean include = type.outerName() == null
                        ? (type.access() & ACC_PUBLIC) != 0
                        : included.contains(type.outerName())
                          && (type.innerAccess() & (ACC_PUBLIC | ACC_PROTECTED)) != 0;
                if (include) {
                    changed |= included.add(type.name());
                }
            }
        } while (changed);

        for (ClassFile type : classes) {
            if (included.contains(type.name())) {
                readType(type.path(), included, entries);
            }
        }
        return new ApiSurface(entries);
    }

    public static List<Entry> readNativeDeclarations(Path classesDirectory) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classesDirectory)) {
            for (Path path : files.filter(file -> file.toString().endsWith(".class")).toList()) {
                if (path.getFileName().toString().equals("module-info.class")) {
                    continue;
                }
                new ClassReader(Files.readAllBytes(path)).accept(new ClassVisitor(Opcodes.ASM9) {
                    private String owner;

                    @Override
                    public void visit(int version, int access, String name, String signature,
                            String superName, String[] interfaces) {
                        this.owner = name;
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                            String signature, String[] exceptions) {
                        if ((access & ACC_NATIVE) != 0) {
                            String key = this.owner + "#" + name + descriptor;
                            String value = "visibility=" + visibility(access)
                                    + ";static=" + ((access & ACC_STATIC) != 0)
                                    + ";jni=" + jniName(this.owner, name);
                            entries.add(new Entry("native", key, value));
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        Map<String, Long> overloadCounts = entries.stream().collect(java.util.stream.Collectors
                .groupingBy(entry -> nativeMethodIdentity(entry.key()),
                        java.util.stream.Collectors.counting()));
        return entries.stream().map(entry -> {
            if (overloadCounts.get(nativeMethodIdentity(entry.key())) == 1L) {
                return entry;
            }
            String descriptor = entry.key().substring(entry.key().indexOf('('));
            String arguments = descriptor.substring(1, descriptor.indexOf(')'));
            String shortName = entry.value().substring(entry.value().indexOf(";jni=") + 5);
            return new Entry(entry.kind(), entry.key(),
                    entry.value() + ";long-jni=" + shortName + "__" + jniEscape(arguments));
        }).toList();
    }

    private static Set<String> readModule(Path path, List<Entry> entries) throws IOException {
        Set<String> exports = new HashSet<>();
        final boolean[] visited = {false};
        new ClassReader(Files.readAllBytes(path)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public ModuleVisitor visitModule(String name, int access, String version) {
                if (visited[0]) {
                    throw new IllegalArgumentException("duplicate module descriptor");
                }
                visited[0] = true;
                entries.add(new Entry("module", "module",
                        "name=" + name + ";access=" + moduleFlags(access)
                                + ";version=" + nullable(version)));
                return new ModuleVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMainClass(String mainClass) {
                        entries.add(new Entry("module-main", mainClass, "-"));
                    }

                    @Override
                    public void visitPackage(String packaze) {
                        entries.add(new Entry("module-package", packaze, "-"));
                    }

                    @Override
                    public void visitRequire(String module, int requiredAccess,
                            String requiredVersion) {
                        entries.add(new Entry("module-requires", module,
                                "access=" + requiresFlags(requiredAccess)
                                        + ";version=" + nullable(requiredVersion)));
                    }

                    @Override
                    public void visitExport(String packaze, int exportAccess, String... modules) {
                        exports.add(packaze.replace('/', '.'));
                        entries.add(new Entry("module-exports", packaze,
                                "access=" + directiveFlags(exportAccess)
                                        + ";targets=" + sorted(modules)));
                    }

                    @Override
                    public void visitOpen(String packaze, int openAccess, String... modules) {
                        entries.add(new Entry("module-opens", packaze,
                                "access=" + directiveFlags(openAccess)
                                        + ";targets=" + sorted(modules)));
                    }

                    @Override
                    public void visitUse(String service) {
                        entries.add(new Entry("module-uses", service, "-"));
                    }

                    @Override
                    public void visitProvide(String service, String... providers) {
                        entries.add(new Entry("module-provides", service, sorted(providers)));
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!visited[0]) {
            throw new IOException("module-info.class contains no module descriptor");
        }
        return exports;
    }

    private static void readType(Path path, Set<String> included, List<Entry> entries)
            throws IOException {
        new ClassReader(Files.readAllBytes(path)).accept(new ClassVisitor(Opcodes.ASM9) {
            private String owner;
            private int recordIndex;

            @Override
            public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces) {
                this.owner = name;
                entries.add(new Entry("type", name,
                        "access=" + typeFlags(access & ~ACC_SUPER & ~ACC_MODULE)
                                + ";super=" + nullable(superName)
                                + ";interfaces=" + sorted(interfaces)
                                + ";signature=" + nullable(signature)));
            }

            @Override
            public void visitNestHost(String nestHost) {
                if (included.contains(nestHost)) {
                    entries.add(new Entry("nest-host", this.owner, nestHost));
                }
            }

            @Override
            public void visitNestMember(String nestMember) {
                if (included.contains(nestMember)) {
                    entries.add(new Entry("nest-member", this.owner + "->" + nestMember, "-"));
                }
            }

            @Override
            public void visitPermittedSubclass(String permittedSubclass) {
                entries.add(new Entry("permitted", this.owner + "->" + permittedSubclass, "-"));
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName,
                    int access) {
                if (this.owner.equals(outerName)
                        && included.contains(name) && included.contains(outerName)) {
                    entries.add(new Entry("nested", outerName + "->" + name,
                            "name=" + nullable(innerName) + ";access="
                                    + typeFlags(access)));
                }
            }

            @Override
            public RecordComponentVisitor visitRecordComponent(String name, String descriptor,
                    String signature) {
                String key = this.owner + "#" + String.format(java.util.Locale.ROOT, "%06d",
                        this.recordIndex++);
                entries.add(new Entry("record", key,
                        "name=" + name + ";descriptor=" + descriptor
                                + ";signature=" + nullable(signature)));
                return null;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                    String signature, Object value) {
                if ((access & (ACC_PUBLIC | ACC_PROTECTED)) != 0) {
                    entries.add(new Entry("field", this.owner + "#" + name,
                            "access=" + fieldFlags(access)
                                    + ";descriptor=" + descriptor
                                    + ";signature=" + nullable(signature)
                                    + ";constant=" + constant(descriptor, value)));
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if ((access & (ACC_PUBLIC | ACC_PROTECTED)) != 0) {
                    entries.add(new Entry("method", this.owner + "#" + name + descriptor,
                            "access=" + methodFlags(access)
                                    + ";signature=" + nullable(signature)
                                    + ";exceptions=" + sorted(exceptions)));
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static String packageName(String binaryName) {
        int separator = binaryName.lastIndexOf('/');
        return separator < 0 ? "" : binaryName.substring(0, separator).replace('/', '.');
    }

    private static String nullable(String value) {
        return value == null ? "-" : value;
    }

    private static String sorted(String[] values) {
        if (values == null || values.length == 0) {
            return "-";
        }
        return Arrays.stream(values).sorted(ApiSurface.UTF8_ORDER)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String typeFlags(int access) {
        List<String> names = new ArrayList<>();
        addFlag(names, access, ACC_PUBLIC, "public");
        addFlag(names, access, ACC_PROTECTED, "protected");
        addFlag(names, access, ACC_STATIC, "static");
        addFlag(names, access, ACC_FINAL, "final");
        addFlag(names, access, ACC_ABSTRACT, "abstract");
        addFlag(names, access, ACC_INTERFACE, "interface");
        addFlag(names, access, ACC_ANNOTATION, "annotation");
        addFlag(names, access, ACC_ENUM, "enum");
        addFlag(names, access, ACC_RECORD, "record");
        addFlag(names, access, ACC_SYNTHETIC, "synthetic");
        return names.isEmpty() ? "-" : String.join(",", names);
    }

    private static String fieldFlags(int access) {
        List<String> names = new ArrayList<>();
        addFlag(names, access, ACC_PUBLIC, "public");
        addFlag(names, access, ACC_PROTECTED, "protected");
        addFlag(names, access, ACC_STATIC, "static");
        addFlag(names, access, ACC_FINAL, "final");
        addFlag(names, access, ACC_VOLATILE, "volatile");
        addFlag(names, access, ACC_TRANSIENT, "transient");
        addFlag(names, access, ACC_ENUM, "enum");
        addFlag(names, access, ACC_SYNTHETIC, "synthetic");
        return names.isEmpty() ? "-" : String.join(",", names);
    }

    private static String methodFlags(int access) {
        List<String> names = new ArrayList<>();
        addFlag(names, access, ACC_PUBLIC, "public");
        addFlag(names, access, ACC_PROTECTED, "protected");
        addFlag(names, access, ACC_STATIC, "static");
        addFlag(names, access, ACC_FINAL, "final");
        addFlag(names, access, ACC_ABSTRACT, "abstract");
        addFlag(names, access, ACC_NATIVE, "native");
        addFlag(names, access, ACC_SYNCHRONIZED, "synchronized");
        addFlag(names, access, ACC_STRICT, "strict");
        addFlag(names, access, ACC_BRIDGE, "bridge");
        addFlag(names, access, ACC_VARARGS, "varargs");
        addFlag(names, access, ACC_SYNTHETIC, "synthetic");
        return names.isEmpty() ? "-" : String.join(",", names);
    }

    private static String moduleFlags(int access) {
        List<String> names = new ArrayList<>();
        addFlag(names, access, ACC_OPEN, "open");
        addFlag(names, access, ACC_SYNTHETIC, "synthetic");
        addFlag(names, access, ACC_MANDATED, "mandated");
        return names.isEmpty() ? "-" : String.join(",", names);
    }

    private static String requiresFlags(int access) {
        List<String> names = new ArrayList<>();
        addFlag(names, access, ACC_TRANSITIVE, "transitive");
        addFlag(names, access, ACC_STATIC_PHASE, "static-phase");
        addFlag(names, access, ACC_SYNTHETIC, "synthetic");
        addFlag(names, access, ACC_MANDATED, "mandated");
        return names.isEmpty() ? "-" : String.join(",", names);
    }

    private static String directiveFlags(int access) {
        List<String> names = new ArrayList<>();
        addFlag(names, access, ACC_SYNTHETIC, "synthetic");
        addFlag(names, access, ACC_MANDATED, "mandated");
        return names.isEmpty() ? "-" : String.join(",", names);
    }

    private static void addFlag(Collection<String> target, int access, int flag, String name) {
        if ((access & flag) != 0) {
            target.add(name);
        }
    }

    private static String constant(String descriptor, Object value) {
        if (value == null) {
            return "-";
        }
        if (value instanceof Integer integer) {
            return switch (descriptor) {
                case "Z" -> "boolean:" + (integer != 0);
                case "C" -> String.format(java.util.Locale.ROOT, "char:U+%04X", integer);
                default -> "int:" + integer;
            };
        }
        if (value instanceof Long number) {
            return "long:" + number;
        }
        if (value instanceof Float number) {
            return Float.isNaN(number)
                    ? "float:NaN:0x" + Integer.toHexString(Float.floatToRawIntBits(number))
                    : "float:" + Float.toHexString(number);
        }
        if (value instanceof Double number) {
            return Double.isNaN(number)
                    ? "double:NaN:0x" + Long.toHexString(Double.doubleToRawLongBits(number))
                    : "double:" + Double.toHexString(number);
        }
        return "string:" + value;
    }

    private static String visibility(int access) {
        if ((access & ACC_PUBLIC) != 0) {
            return "public";
        }
        if ((access & ACC_PROTECTED) != 0) {
            return "protected";
        }
        if ((access & ACC_PRIVATE) != 0) {
            return "private";
        }
        return "package";
    }

    static String jniName(String owner, String method) {
        return "Java_" + jniEscape(owner) + '_' + jniEscape(method);
    }

    private static String nativeMethodIdentity(String key) {
        return key.substring(0, key.indexOf('('));
    }

    private static String jniEscape(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                result.append(character);
            } else if (character == '/') {
                result.append('_');
            } else if (character == '_') {
                result.append("_1");
            } else if (character == ';') {
                result.append("_2");
            } else if (character == '[') {
                result.append("_3");
            } else {
                result.append("_0")
                        .append(String.format(java.util.Locale.ROOT, "%04x", (int) character));
            }
        }
        return result.toString();
    }

    private ApiSurfaceReader() {
    }

    private record ClassFile(Path path, String name, int access, String packageName,
                             String outerName, int innerAccess) {

    }
}
