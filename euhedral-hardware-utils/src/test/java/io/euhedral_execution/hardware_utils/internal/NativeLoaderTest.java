package io.euhedral_execution.hardware_utils.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class NativeLoaderTest {

    private static final String CATALOG = """
            schema\t1
            os\texact\tlinux\tlinux
            os\texact\tmac os x\tmacos
            os\tprefix\twindows\twindows
            arch\tamd64\tx64
            arch\tx86_64\tx64
            arch\taarch64\tarm64
            product\tlinux-glibc-x64\tlinux\tx64\tglibc\t10\t/bin/linux/glibc/linux_jni_x64.so
            product\tlinux-musl-x64\tlinux\tx64\tmusl\t20\t/bin/linux/musl/linux_jni_x64.so
            product\tmacos-arm64\tmacos\tarm64\tnone\t10\t/bin/osx/osx_jni_arm64.dylib
            product\twindows-x64\twindows\tx64\tnone\t10\t/bin/windows/windows_jni_x64.dll
            """;

    private static NativeProduct product() {
        return new NativeProduct(
                "linux-glibc-x64", "linux", "x64", "glibc", 10,
                "/bin/linux/glibc/linux_jni_x64.so");
    }

    private static Path staleRun(
            Path base,
            long pid,
            String suffix,
            long created,
            String markerName,
            boolean unexpected) throws Exception {
        Path run = base.resolve("load-" + pid + '-' + suffix);
        Files.createDirectory(run);
        String marker = "schema=1\npid=" + pid + "\ncreatedEpochMillis=" + created + "\n";
        Files.writeString(run.resolve(markerName), marker);
        if (unexpected) {
            Files.writeString(run.resolve("unexpected.txt"), "keep");
        }
        return run;
    }

    private static org.slf4j.Logger logger() {
        return LoggerFactory.getLogger(NativeLoaderTest.class);
    }
    @TempDir
    Path temporaryDirectory;

    @Test
    void appliesOnlyCatalogAliasesAndOrdersFallbacks() throws Exception {
        NativeProductCatalog catalog = catalog();
        assertEquals(List.of("linux-glibc-x64", "linux-musl-x64"),
                catalog.select("  LINUX  ", "AMD64").stream().map(NativeProduct::id).toList());
        assertEquals("macos-arm64", catalog.select(" Mac\t OS   X ", "AARCH64").get(0).id());
        assertEquals("windows-x64", catalog.select("Windows 11", "x86_64").get(0).id());

        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class, () -> catalog.select("Linux", "sparc"));
        assertTrue(unknown.getMessage().contains("sparc"));
        assertTrue(unknown.getMessage().contains("aarch64"));
        assertFalse(unknown.getMessage().contains("arm64"));
    }

    @Test
    void parserRejectsUnknownRowsDuplicatesAndMissingResources() {
        assertThrows(IOException.class,
                () -> NativeProductCatalog.parse(CATALOG.replace("schema\t1", "future\t1"),
                        path -> true));
        assertThrows(IOException.class,
                () -> NativeProductCatalog.parse(CATALOG.replace("arch\tamd64\tx64\n",
                        "arch\tamd64\tx64\narch\tamd64\tx64\n"), path -> true));
        IOException missing = assertThrows(IOException.class,
                () -> NativeProductCatalog.parse(CATALOG, path -> !path.contains("musl")));
        assertTrue(missing.getMessage().contains("missing product resource"));
    }

    @Test
    void fallsBackOnLinkageIoAndSecurityFailures() throws Exception {
        for (FailureKind failureKind : FailureKind.values()) {
            List<String> attempts = new ArrayList<>();
            NativeLibraryExtractor extractor = extractor("linux", path -> {
                if (failureKind == FailureKind.IO && path.contains("glibc")) {
                    throw new IOException("synthetic I/O failure");
                }
                if (failureKind == FailureKind.SECURITY && path.contains("glibc")) {
                    throw new SecurityException("synthetic security failure");
                }
                return new ByteArrayInputStream(new byte[]{1, 2, 3});
            }, new RecordingSecurity());
            NativeLibrarySystem system = path -> {
                attempts.add(path.getFileName().toString());
                if (failureKind == FailureKind.LINKAGE && attempts.size() == 1) {
                    throw new UnsatisfiedLinkError("synthetic link failure");
                }
            };
            NativeLoadResult result = new NativeLibraryLoader(catalog(), extractor, system,
                    logger())
                    .load("linux", "amd64");
            assertEquals("linux-musl-x64", result.product().id(), failureKind.name());
            assertEquals(1, result.failures().size(), failureKind.name());
        }
    }

    @Test
    void neverCatchesAnArbitraryError() throws Exception {
        NativeLibraryExtractor extractor = extractor(
                "linux", path -> new ByteArrayInputStream(new byte[]{1}), new RecordingSecurity());
        AssertionError failure = assertThrows(AssertionError.class,
                () -> new NativeLibraryLoader(catalog(), extractor, path -> {
                    throw new AssertionError("must escape");
                }, logger()).load("linux", "amd64"));
        assertEquals("must escape", failure.getMessage());
    }

    @Test
    void rejectsEmptyResourcesAndReportsActionableAllCandidateFailure() throws Exception {
        NativeLibraryExtractor extractor = extractor(
                "linux", path -> new ByteArrayInputStream(new byte[0]), new RecordingSecurity());
        ExceptionInInitializerError failure = assertThrows(ExceptionInInitializerError.class,
                () -> new NativeLibraryLoader(catalog(), extractor, path -> {
                }, logger())
                        .load("linux", "amd64"));
        assertEquals(2, failure.getSuppressed().length);
        assertInstanceOf(IOException.class, failure.getSuppressed()[0]);
        assertTrue(failure.getMessage().contains("/bin/linux/glibc/linux_jni_x64.so"));
        assertTrue(failure.getMessage().contains("/bin/linux/musl/linux_jni_x64.so"));
        assertTrue(failure.getMessage().contains("noexec mount is a possible cause"));
        assertTrue(failure.getMessage().contains("-Dio.euhedral.native.extract.dir="));
    }

    @Test
    void rejectsAResourceLargerThanTheSixtyFourMebibyteBound() throws Exception {
        NativeLibraryExtractor extractor = extractor("linux", path -> new java.io.InputStream() {
            private long remaining = NativeLibraryExtractor.MAX_LIBRARY_BYTES + 1;

            @Override
            public int read() {
                if (remaining-- > 0) {
                    return 0;
                }
                return -1;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) {
                if (remaining == 0) {
                    return -1;
                }
                int count = (int) Math.min(remaining, length);
                remaining -= count;
                return count;
            }
        }, new RecordingSecurity());
        NativeProduct product = product();
        Path destination = extractor.pathFor(product);
        IOException failure = assertThrows(IOException.class, () -> extractor.extract(product));
        assertTrue(failure.getMessage()
                .contains(Long.toString(NativeLibraryExtractor.MAX_LIBRARY_BYTES)));
        assertFalse(Files.exists(destination));
        extractor.allCandidatesFailed();
    }

    @Test
    void posixCleansImmediatelyWhileWindowsRetainsUntilShutdown() throws Exception {
        RecordingSecurity posixSecurity = new RecordingSecurity();
        NativeLibraryExtractor posix = extractor(
                "linux", path -> new ByteArrayInputStream(new byte[]{1}), posixSecurity);
        NativeLoadResult posixResult = new NativeLibraryLoader(catalog(), posix, path -> {
        }, logger())
                .load("linux", "amd64");
        assertFalse(Files.exists(posixResult.extractionPath()));
        assertTrue(posixSecurity.libraryCalls.get() > 0);

        RecordingSecurity windowsSecurity = new RecordingSecurity();
        NativeLibraryExtractor windows = extractor(
                "windows", path -> new ByteArrayInputStream(new byte[]{1}), windowsSecurity);
        NativeLoadResult windowsResult = new NativeLibraryLoader(catalog(), windows, path -> {
        }, logger())
                .load("Windows 11", "amd64");
        assertTrue(Files.exists(windowsResult.extractionPath()));
        assertEquals(0, windowsSecurity.posixCalls.get(),
                "Windows policy must not request POSIX attributes");
        windows.allCandidatesFailed();
        assertFalse(Files.exists(windowsResult.extractionPath()));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void staleCleanupRejectsUnsafeEntriesAndStopsAfterSixtyFourChildren() throws Exception {
        long now = Instant.parse("2026-08-01T12:00:00Z").toEpochMilli();
        Path parent = temporaryDirectory.resolve("stale-parent");
        Path base = parent.resolve("euhedral-native-v1");
        Files.createDirectories(base);
        Path eligible = staleRun(base, 90001, "11111111111111111111111111111111", now - 86_400_001L,
                "owner.properties", false);
        Path young = staleRun(base, 90002, "22222222222222222222222222222222", now,
                "owner.properties", false);
        long livePid = ProcessHandle.current().pid();
        Path live = staleRun(base, livePid, "33333333333333333333333333333333", now - 86_400_001L,
                "owner.properties", false);
        Path invalid = staleRun(base, 90003, "44444444444444444444444444444444", now - 86_400_001L,
                "bad.properties", false);
        Path unexpected = staleRun(base, 90004, "55555555555555555555555555555555",
                now - 86_400_001L,
                "owner.properties", true);
        Path external = temporaryDirectory.resolve("symlink-target");
        Files.createDirectories(external);
        Path symlink = base.resolve("load-90005-66666666666666666666666666666666");
        Files.createSymbolicLink(symlink, external);

        extractorAt(parent, pid -> pid == livePid).prepare(product());
        assertFalse(Files.exists(eligible));
        assertTrue(Files.exists(young));
        assertTrue(Files.exists(live));
        assertTrue(Files.exists(invalid));
        assertTrue(Files.exists(unexpected));
        assertTrue(Files.isSymbolicLink(symlink));

        Path boundedParent = temporaryDirectory.resolve("bounded-parent");
        Path boundedBase = boundedParent.resolve("euhedral-native-v1");
        Files.createDirectories(boundedBase);
        for (int index = 0; index < 64; index++) {
            Files.createDirectory(boundedBase.resolve(String.format(
                    "load-%05d-00000000000000000000000000000000", index + 1)));
        }
        Path sixtyFifth = staleRun(boundedBase, 99999, "ffffffffffffffffffffffffffffffff",
                now - 86_400_001L, "owner.properties", false);
        extractorAt(boundedParent, pid -> false).prepare(product());
        assertTrue(Files.exists(sixtyFifth), "entry 65 must not be inspected");
    }

    private NativeProductCatalog catalog() throws IOException {
        return NativeProductCatalog.parse(CATALOG, path -> true);
    }

    private NativeLibraryExtractor extractor(
            String operatingSystem,
            NativeLibraryExtractor.ResourceInput resources,
            NativeFileSecurity security) {
        return new NativeLibraryExtractor(
                operatingSystem,
                temporaryDirectory,
                security,
                resources,
                Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC),
                pid -> false,
                new SecureRandom(new byte[]{1, 2, 3, 4}),
                424242,
                logger());
    }

    private NativeLibraryExtractor extractorAt(Path parent,
            java.util.function.LongPredicate processAlive) {
        return new NativeLibraryExtractor(
                "linux",
                parent,
                new RecordingSecurity(),
                path -> new ByteArrayInputStream(new byte[]{1}),
                Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC),
                processAlive,
                new SecureRandom(new byte[]{5, 6, 7, 8}),
                424242,
                logger());
    }

    private enum FailureKind {
        LINKAGE,
        IO,
        SECURITY
    }

    private static final class RecordingSecurity implements NativeFileSecurity {

        private final AtomicInteger posixCalls = new AtomicInteger();
        private final AtomicInteger libraryCalls = new AtomicInteger();

        @Override
        public void secureDirectory(Path path) {
        }

        @Override
        public void secureMarker(Path path) {
        }

        @Override
        public void secureLibrary(Path path) {
            libraryCalls.incrementAndGet();
        }
    }
}
