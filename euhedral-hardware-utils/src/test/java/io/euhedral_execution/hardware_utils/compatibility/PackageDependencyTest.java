package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/**
 * Ratchet over package-level dependencies between packages of this module. Edges may only be removed
 * from {@code package-dependencies.tsv}, never added.
 *
 * <p>Uses plain ASM only (no {@code asm-commons}). An edge is collected from every reference visible
 * in the class file: the constant pool (class constants, member and method-type descriptors), the
 * class header, declared field and method descriptors, generic signatures, exceptions, and
 * annotations.
 */
class PackageDependencyTest {
    private static final String ROOT = "io/euhedral_execution/hardware_utils/";
    private static final String BASELINE = "/compatibility/package-dependencies.tsv";
    /** A CONSTANT_Utf8 holds at most 65535 bytes, so it never decodes to more chars than this. */
    private static final int MAX_UTF8_CHARS = 65535;

    @Test
    void packageEdgesMatchBaseline() throws IOException {
        String property = System.getProperty("classes.directory");
        assertNotNull(property, "classes.directory system property is not set");
        Map<String, String> actual = scan(property.split(File.pathSeparator));
        assertFalse(actual.isEmpty(), "no classes found under " + ROOT + " in " + property);
        List<String> baseline = readBaseline();
        assertEquals(
                baseline.stream().distinct().sorted().collect(Collectors.toList()),
                baseline,
                "package-dependencies.tsv must be sorted with no duplicates");

        Set<String> allowed = new TreeSet<>(baseline);
        Map<String, String> added = new TreeMap<>(actual);
        added.keySet().removeAll(allowed);
        Set<String> removed = new TreeSet<>(allowed);
        removed.removeAll(actual.keySet());
        if (added.isEmpty() && removed.isEmpty()) {
            return;
        }

        StringBuilder message = new StringBuilder();
        if (!added.isEmpty()) {
            message.append("Added edges (forbidden; a new package dependency needs explicit approval):");
            added.forEach((edge, className) -> message.append("\n  ")
                    .append(edge.replace("\t", " -> "))
                    .append(" (via ")
                    .append(className)
                    .append(')'));
        }
        if (!removed.isEmpty()) {
            if (!message.isEmpty()) {
                message.append('\n');
            }
            message.append("Removed edges (good - delete these lines from "
                    + "euhedral-hardware-utils/src/test/resources/compatibility/package-dependencies.tsv):");
            for (String edge : removed) {
                message.append("\n  ").append(edge.replace("\t", " -> "));
            }
        }
        fail(message.toString());
    }

    private static List<String> readBaseline() throws IOException {
        try (InputStream in = PackageDependencyTest.class.getResourceAsStream(BASELINE)) {
            assertNotNull(in, "missing baseline resource " + BASELINE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank())
                    .collect(Collectors.toList());
        }
    }

    /** Maps each edge ({@code from\tto}) to the first class, in sorted path order, that produced it. */
    private static Map<String, String> scan(String[] entries) throws IOException {
        Map<String, String> edges = new TreeMap<>();
        for (String entry : entries) {
            Path packageRoot = Path.of(entry).resolve(ROOT);
            if (!Files.isDirectory(packageRoot)) {
                continue;
            }
            List<Path> classFiles;
            try (Stream<Path> files = Files.walk(packageRoot)) {
                classFiles = files.filter(
                                p -> Files.isRegularFile(p) && p.toString().endsWith(".class"))
                        .sorted()
                        .collect(Collectors.toList());
            }
            for (Path classFile : classFiles) {
                scanClass(Files.readAllBytes(classFile), edges);
            }
        }
        return edges;
    }

    private static void scanClass(byte[] bytes, Map<String, String> edges) {
        ClassReader reader = new ClassReader(bytes);
        ClassEdges collector = new ClassEdges(reader.getClassName(), edges);
        collector.scanConstantPool(reader);
        reader.accept(collector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    /** Package of a module-internal internal name; empty for classes directly in the root package. */
    private static String pkg(String internalName) {
        int slash = internalName.lastIndexOf('/');
        if (slash < ROOT.length()) {
            return "";
        }
        return internalName.substring(ROOT.length(), slash).replace('/', '.');
    }

    private static final class ClassEdges extends ClassVisitor {
        private final String className;
        private final String from;
        private final Map<String, String> edges;

        ClassEdges(String className, Map<String, String> edges) {
            super(Opcodes.ASM9);
            this.className = className;
            this.from = pkg(className);
            this.edges = edges;
        }

        void scanConstantPool(ClassReader reader) {
            char[] buffer = new char[MAX_UTF8_CHARS];
            for (int index = 1; index < reader.getItemCount(); index++) {
                int offset = reader.getItem(index);
                if (offset == 0) {
                    continue; // second slot of a long or double constant
                }
                switch (reader.readByte(offset - 1)) {
                    case 7 -> recordInternalName(reader.readUTF8(offset, buffer)); // CONSTANT_Class: name index
                    case 12 -> recordDescriptor(reader.readUTF8(offset + 2, buffer)); // CONSTANT_NameAndType
                    case 16 -> recordDescriptor(reader.readUTF8(offset, buffer)); // CONSTANT_MethodType
                    default -> {
                        // Other constants reference the entries above or no class at all.
                    }
                }
            }
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            if (signature != null) {
                recordSignature(signature);
            }
            recordInternalName(superName);
            if (interfaces != null) {
                for (String anInterface : interfaces) {
                    recordInternalName(anInterface);
                }
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            recordDescriptor(descriptor);
            return new AnnotationEdges();
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            recordDescriptor(descriptor);
            if (signature != null) {
                recordSignature(signature);
            }
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    recordDescriptor(annotationDescriptor);
                    return new AnnotationEdges();
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            recordDescriptor(descriptor);
            if (signature != null) {
                recordSignature(signature);
            }
            if (exceptions != null) {
                for (String exception : exceptions) {
                    recordInternalName(exception);
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    recordDescriptor(annotationDescriptor);
                    return new AnnotationEdges();
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(
                        int parameter, String annotationDescriptor, boolean visible) {
                    recordDescriptor(annotationDescriptor);
                    return new AnnotationEdges();
                }

                @Override
                public AnnotationVisitor visitAnnotationDefault() {
                    return new AnnotationEdges();
                }
            };
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
            recordDescriptor(descriptor);
            if (signature != null) {
                recordSignature(signature);
            }
            return new RecordComponentVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    recordDescriptor(annotationDescriptor);
                    return new AnnotationEdges();
                }
            };
        }

        private void recordSignature(String signature) {
            new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
                @Override
                public void visitClassType(String name) {
                    recordInternalName(name);
                }
            });
        }

        private void recordDescriptor(String descriptor) {
            if (descriptor != null) {
                recordType(Type.getType(descriptor));
            }
        }

        private void recordType(Type type) {
            switch (type.getSort()) {
                case Type.OBJECT -> recordInternalName(type.getInternalName());
                case Type.ARRAY -> recordType(type.getElementType());
                case Type.METHOD -> {
                    for (Type argument : type.getArgumentTypes()) {
                        recordType(argument);
                    }
                    recordType(type.getReturnType());
                }
                default -> {
                    // primitives and void reference no package
                }
            }
        }

        private void recordInternalName(String internalName) {
            if (internalName == null) {
                return;
            }
            if (internalName.startsWith("[")) {
                recordType(Type.getType(internalName));
                return;
            }
            if (!internalName.startsWith(ROOT)) {
                return;
            }
            String to = pkg(internalName);
            if (!to.equals(from)) {
                edges.putIfAbsent(from + "\t" + to, className);
            }
        }

        private final class AnnotationEdges extends AnnotationVisitor {
            AnnotationEdges() {
                super(Opcodes.ASM9);
            }

            @Override
            public void visit(String name, Object value) {
                if (value instanceof Type type) {
                    recordType(type);
                }
            }

            @Override
            public void visitEnum(String name, String descriptor, String value) {
                recordDescriptor(descriptor);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                recordDescriptor(descriptor);
                return this;
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                return this;
            }
        }
    }
}
