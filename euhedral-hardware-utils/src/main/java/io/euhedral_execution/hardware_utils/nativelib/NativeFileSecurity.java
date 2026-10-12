package io.euhedral_execution.hardware_utils.nativelib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

interface NativeFileSecurity {

    static NativeFileSecurity forOperatingSystem(String canonicalOperatingSystem) throws IOException {
        return switch (canonicalOperatingSystem) {
            case "windows" -> new WindowsSecurity();
            case "linux", "macos" -> new PosixSecurity();
            default ->
                throw new IOException("native-loader: unsupported filesystem policy for " + canonicalOperatingSystem);
        };
    }

    FileAttribute<?>[] NO_ATTRIBUTES = new FileAttribute<?>[0];
    Set<PosixFilePermission> PRIVATE_DIRECTORY = PosixFilePermissions.fromString("rwx------");
    Set<PosixFilePermission> PRIVATE_FILE = PosixFilePermissions.fromString("rw-------");
    Set<PosixFilePermission> PRIVATE_FILE_EXECUTABLE = PosixFilePermissions.fromString("rwx------");

    void secureDirectory(Path path) throws IOException;

    void secureMarker(Path path) throws IOException;

    void secureLibrary(Path path) throws IOException;

    default FileAttribute<?>[] directoryAttributes(Path parentDirectory) throws IOException {
        return NO_ATTRIBUTES;
    }

    default FileAttribute<?>[] markerAttributes(Path parentDirectory) throws IOException {
        return NO_ATTRIBUTES;
    }

    default FileAttribute<?>[] libraryAttributes(Path parentDirectory) throws IOException {
        return NO_ATTRIBUTES;
    }

    default void adoptExistingDirectory(Path directory) throws IOException {
        secureDirectory(directory);
    }

    default void verifyPrivateDirectory(Path directory, Path probeParent) throws IOException {}

    default void verifyPrivateLibrary(Path library, Path probeParent) throws IOException {}

    /// Resolves file owners and the identity of the current process. This is the only place that touches owner
    /// information, so tests can simulate a foreign owner without changing real ownership.
    interface OwnerLookup {

        /// Production lookup. The current user is the owner of a file this process has just created in
        /// `probeParent`; that is exact for the effective uid and, unlike `user.name`, still works for arbitrary
        /// container uids that have no passwd entry.
        OwnerLookup DEFAULT = new OwnerLookup() {
            @Override
            public UserPrincipal ownerOf(Path path) throws IOException {
                return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            }

            @Override
            public UserPrincipal currentUser(Path probeParent) throws IOException {
                Path probe = Files.createTempFile(
                        probeParent, ".euhedral-owner-probe-", null, privateFileAttribute(probeParent));
                try {
                    return ownerOf(probe);
                } finally {
                    Files.deleteIfExists(probe);
                }
            }
        };

        UserPrincipal ownerOf(Path path) throws IOException;

        UserPrincipal currentUser(Path probeParent) throws IOException;
    }

    private static FileAttribute<?>[] privateFileAttribute(Path parentDirectory) throws IOException {
        if (!Files.getFileStore(parentDirectory).supportsFileAttributeView("posix")) {
            return NO_ATTRIBUTES;
        }
        return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PRIVATE_FILE)};
    }

    final class PosixSecurity implements NativeFileSecurity {

        private final OwnerLookup ownerLookup;
        private volatile UserPrincipal currentUser;

        PosixSecurity() {
            this(OwnerLookup.DEFAULT);
        }

        PosixSecurity(OwnerLookup ownerLookup) {
            this.ownerLookup = Objects.requireNonNull(ownerLookup, "ownerLookup");
        }

        private static void set(Path path, String permissions) throws IOException {
            PosixFileAttributeView view =
                    Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                throw new IOException("native-loader: POSIX file attributes are unavailable for " + path);
            }
            view.setPermissions(PosixFilePermissions.fromString(permissions));
        }

        private static FileAttribute<?>[] attributes(Path parentDirectory, Set<PosixFilePermission> permissions)
                throws IOException {
            if (!Files.getFileStore(parentDirectory).supportsFileAttributeView("posix")) {
                return NO_ATTRIBUTES;
            }
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(permissions)};
        }

        private UserPrincipal currentUser(Path probeParent) throws IOException {
            UserPrincipal cached = currentUser;
            if (cached == null) {
                cached = ownerLookup.currentUser(probeParent);
                currentUser = cached;
            }
            return cached;
        }

        private void verify(Path path, Path probeParent, boolean directory) throws IOException {
            // Fail closed: any lookup failure propagates as an IOException and aborts the load.
            BasicFileAttributes basic =
                    Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (basic.isSymbolicLink()) {
                throw new IOException("native-loader: " + path + " is a symbolic link");
            }
            if (directory ? !basic.isDirectory() : !basic.isRegularFile()) {
                throw new IOException(
                        "native-loader: " + path + " is not a " + (directory ? "directory" : "regular file"));
            }
            UserPrincipal owner = ownerLookup.ownerOf(path);
            UserPrincipal expected = currentUser(probeParent);
            if (!expected.equals(owner)) {
                throw new IOException("native-loader: " + path + " is not owned by the current user (owner=" + owner
                        + ", expected=" + expected + ")");
            }
            Set<PosixFilePermission> actual;
            try {
                actual = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException e) {
                throw new IOException("native-loader: POSIX file attributes are unavailable for " + path, e);
            }
            Set<PosixFilePermission> wanted = directory ? PRIVATE_DIRECTORY : PRIVATE_FILE_EXECUTABLE;
            if (!actual.equals(wanted)) {
                throw new IOException(
                        "native-loader: " + path + " permissions are " + PosixFilePermissions.toString(actual)
                                + "; expected " + PosixFilePermissions.toString(wanted));
            }
        }

        @Override
        public FileAttribute<?>[] directoryAttributes(Path parentDirectory) throws IOException {
            return attributes(parentDirectory, PRIVATE_DIRECTORY);
        }

        @Override
        public FileAttribute<?>[] markerAttributes(Path parentDirectory) throws IOException {
            return attributes(parentDirectory, PRIVATE_FILE);
        }

        @Override
        public FileAttribute<?>[] libraryAttributes(Path parentDirectory) throws IOException {
            return attributes(parentDirectory, PRIVATE_FILE_EXECUTABLE);
        }

        @Override
        public void adoptExistingDirectory(Path directory) {
            // Never chmod a directory this process did not create; verifyPrivateDirectory decides whether it is usable.
        }

        @Override
        public void verifyPrivateDirectory(Path directory, Path probeParent) throws IOException {
            verify(directory, probeParent, true);
        }

        @Override
        public void verifyPrivateLibrary(Path library, Path probeParent) throws IOException {
            verify(library, probeParent, false);
            // A hard link elsewhere would let another path rewrite the inode after verification. The "unix" view is
            // only consulted on file stores that report it.
            if (Files.getFileStore(library).supportsFileAttributeView("unix")) {
                Object links = Files.getAttribute(library, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
                if (!(links instanceof Integer count) || count != 1) {
                    throw new IOException(
                            "native-loader: " + library + " has " + links + " hard links; expected exactly 1");
                }
            }
        }

        @Override
        public void secureDirectory(Path path) throws IOException {
            set(path, "rwx------");
        }

        @Override
        public void secureMarker(Path path) throws IOException {
            set(path, "rw-------");
        }

        @Override
        public void secureLibrary(Path path) throws IOException {
            set(path, "rwx------");
        }
    }

    final class WindowsSecurity implements NativeFileSecurity {

        private static void secure(Path path, boolean directory) throws IOException {
            AclFileAttributeView view =
                    Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                throw new IOException("native-loader: Windows ACL file attributes are unavailable for " + path);
            }
            UserPrincipal owner = view.getOwner();
            AclEntry.Builder entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) {
                entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            }
            view.setAcl(List.of(entry.build()));
            List<AclEntry> actual = view.getAcl();
            if (actual.size() != 1
                    || actual.get(0).type() != AclEntryType.ALLOW
                    || !actual.get(0).principal().equals(owner)) {
                throw new IOException("native-loader: Windows ACL contains a non-owner entry for " + path);
            }
        }

        @Override
        public void secureDirectory(Path path) throws IOException {
            secure(path, true);
        }

        @Override
        public void secureMarker(Path path) throws IOException {
            secure(path, false);
        }

        @Override
        public void secureLibrary(Path path) throws IOException {
            secure(path, false);
        }
    }
}
