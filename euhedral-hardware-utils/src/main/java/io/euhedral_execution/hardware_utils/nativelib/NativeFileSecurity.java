package io.euhedral_execution.hardware_utils.nativelib;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

        /// Production lookup. The current user is the owner of a file this process just created in the validated
        /// `probeParent`; that is exact for the effective uid and, unlike `user.name`, still works for arbitrary
        /// container uids that have no passwd entry.
        OwnerLookup DEFAULT = new OwnerLookup() {
            @Override
            public UserPrincipal ownerOf(Path path) throws IOException {
                return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            }

            @Override
            public UserPrincipal currentUser(Path probeParent) throws IOException {
                // The probe supplies the identity for verify() ownership checks (on Linux too); the parent check
                // reads the effective uid from /proc/self/status when it is readable and uses the probe only
                // otherwise. (The owner of /proc/self is unreliable: root for non-dumpable processes.)
                // verifySafeParent has required a parent owned by this user or by root (by root alone when it is
                // shared-writable), so other users cannot unlink and recreate the probe. Residue: a non-shared
                // parent owned by another user who can swap the probe is an operator misconfiguration of the
                // extract directory.
                Path probe = Files.createTempFile(
                        probeParent, ".euhedral-owner-probe-", null, posixAttributes(probeParent, PRIVATE_FILE));
                UserPrincipal owner;
                try {
                    owner = ownerOf(probe);
                } catch (IOException | RuntimeException e) {
                    try {
                        Files.deleteIfExists(probe);
                    } catch (IOException | RuntimeException cleanup) {
                        e.addSuppressed(cleanup);
                    }
                    throw e;
                }
                Files.deleteIfExists(probe);
                return owner;
            }
        };

        UserPrincipal ownerOf(Path path) throws IOException;

        UserPrincipal currentUser(Path probeParent) throws IOException;
    }

    /// Numeric uid source, independent of any file inside the extraction parent. `effectiveUid` is read from
    /// `/proc/self/status` (Linux); it is `-1` when unavailable (e.g. macOS), in which case the probe identity is used.
    interface UnixIds {

        UnixIds DEFAULT = new UnixIds() {
            @Override
            public long effectiveUid() throws IOException {
                Path status = Path.of("/proc/self/status");
                if (!Files.isReadable(status)) {
                    return -1;
                }
                return parseEffectiveUid(Files.readString(status, StandardCharsets.UTF_8));
            }

            @Override
            public long ownerUid(Path path) throws IOException {
                return unsignedUid(Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS));
            }
        };

        /// `unix:uid` is an Integer holding the 32-bit uid; widen it as unsigned so a uid >= 2^31 does not go negative.
        static long unsignedUid(Object attribute) {
            return attribute instanceof Long l ? l : Integer.toUnsignedLong(((Number) attribute).intValue());
        }

        /// Parses the `Uid:` line (real, effective, saved, filesystem) and returns the EFFECTIVE (second) uid.
        static long parseEffectiveUid(String status) throws IOException {
            for (String line : status.split("\n")) {
                if (line.startsWith("Uid:")) {
                    String[] fields = line.substring(4).trim().split("\\s+");
                    if (fields.length >= 2) {
                        try {
                            return Long.parseLong(fields[1]);
                        } catch (NumberFormatException e) {
                            throw new IOException("native-loader: malformed Uid line in /proc/self/status", e);
                        }
                    }
                }
            }
            throw new IOException("native-loader: no Uid line in /proc/self/status");
        }

        long effectiveUid() throws IOException;

        long ownerUid(Path path) throws IOException;
    }

    private static FileAttribute<?>[] posixAttributes(Path parentDirectory, Set<PosixFilePermission> permissions)
            throws IOException {
        if (!Files.getFileStore(parentDirectory).supportsFileAttributeView("posix")) {
            return NO_ATTRIBUTES;
        }
        return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(permissions)};
    }

    /// A failed ownership, mode, link or hash verification, as opposed to an ordinary I/O failure.
    final class VerificationException extends IOException {

        private static final long serialVersionUID = 1L;

        VerificationException(String message) {
            super(message);
        }
    }

    /// Rejects an extraction parent into which another user could plant or rename entries. Only POSIX policies
    /// enforce this.
    default void verifySafeParent(Path parent) throws IOException {}

    final class PosixSecurity implements NativeFileSecurity {

        private final OwnerLookup ownerLookup;
        private final UnixIds unixIds;
        private volatile UserPrincipal currentUser;

        PosixSecurity() {
            this(OwnerLookup.DEFAULT, UnixIds.DEFAULT);
        }

        PosixSecurity(OwnerLookup ownerLookup) {
            this(ownerLookup, UnixIds.DEFAULT);
        }

        PosixSecurity(OwnerLookup ownerLookup, UnixIds unixIds) {
            this.ownerLookup = Objects.requireNonNull(ownerLookup, "ownerLookup");
            this.unixIds = Objects.requireNonNull(unixIds, "unixIds");
        }

        private static void set(Path path, String permissions) throws IOException {
            PosixFileAttributeView view =
                    Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                throw new IOException("native-loader: POSIX file attributes are unavailable for " + path);
            }
            view.setPermissions(PosixFilePermissions.fromString(permissions));
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
                throw new VerificationException("native-loader: " + path + " is a symbolic link");
            }
            if (directory ? !basic.isDirectory() : !basic.isRegularFile()) {
                throw new VerificationException(
                        "native-loader: " + path + " is not a " + (directory ? "directory" : "regular file"));
            }
            UserPrincipal owner = ownerLookup.ownerOf(path);
            UserPrincipal expected = currentUser(probeParent);
            if (!expected.equals(owner)) {
                throw new VerificationException("native-loader: " + path + " is not owned by the current user (owner="
                        + owner + ", expected=" + expected + ")");
            }
            Set<PosixFilePermission> actual;
            try {
                actual = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException e) {
                throw new IOException("native-loader: POSIX file attributes are unavailable for " + path, e);
            }
            Set<PosixFilePermission> wanted = directory ? PRIVATE_DIRECTORY : PRIVATE_FILE_EXECUTABLE;
            if (!actual.equals(wanted)) {
                throw new VerificationException(
                        "native-loader: " + path + " permissions are " + PosixFilePermissions.toString(actual)
                                + "; expected " + PosixFilePermissions.toString(wanted));
            }
        }

        @Override
        public FileAttribute<?>[] directoryAttributes(Path parentDirectory) throws IOException {
            return posixAttributes(parentDirectory, PRIVATE_DIRECTORY);
        }

        @Override
        public FileAttribute<?>[] markerAttributes(Path parentDirectory) throws IOException {
            return posixAttributes(parentDirectory, PRIVATE_FILE);
        }

        @Override
        public FileAttribute<?>[] libraryAttributes(Path parentDirectory) throws IOException {
            return posixAttributes(parentDirectory, PRIVATE_FILE_EXECUTABLE);
        }

        /// Parent rules (POSIX, `unix` view available):
        /// - group/other-writable without sticky: rejected (entries could be renamed or replaced);
        /// - group/other-writable with sticky: must be owned by root, because a sticky directory's owner can still
        ///   delete or rename entries and clear the bit;
        /// - otherwise: owned by root or by the current user.
        /// The current user is the effective uid from `/proc/self/status` where readable, else the owner of a probe
        /// file (macOS). Not checked, and left to the operator who configures `-Dio.euhedral.native.extract.dir`:
        /// ancestors of the parent (a non-sticky, other-writable ancestor lets the parent itself be renamed), and a
        /// non-shared parent owned by another user when the identity came from the probe.
        @Override
        public void verifySafeParent(Path directory) throws IOException {
            boolean unix = Files.getFileStore(directory).supportsFileAttributeView("unix");
            boolean sticky = false;
            int mode;
            if (unix) {
                mode = (Integer) Files.getAttribute(directory, "unix:mode", LinkOption.NOFOLLOW_LINKS);
                sticky = (mode & 01000) != 0;
            } else {
                // Without the unix view the sticky bit cannot be observed, so any shared-writable parent is unsafe.
                int bits = 0;
                Set<PosixFilePermission> permissions;
                try {
                    permissions = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
                } catch (UnsupportedOperationException e) {
                    throw new IOException("native-loader: POSIX file attributes are unavailable for " + directory, e);
                }
                for (PosixFilePermission permission : permissions) {
                    bits |= switch (permission) {
                        case GROUP_WRITE -> 020;
                        case OTHERS_WRITE -> 02;
                        default -> 0;
                    };
                }
                mode = bits;
            }
            boolean shared = (mode & 022) != 0;
            if (shared && !sticky) {
                throw new VerificationException("native-loader: extraction parent " + directory + " has mode "
                        + String.format("%04o", mode & 07777)
                        + ", which is group- or other-writable without the sticky bit; use a private directory");
            }
            if (!unix) {
                return;
            }
            long owner = unixIds.ownerUid(directory);
            if (owner == 0) {
                return;
            }
            boolean ours;
            if (shared) {
                ours = false;
            } else {
                long effective = unixIds.effectiveUid();
                ours = effective >= 0
                        ? owner == effective
                        : currentUser(directory).equals(ownerLookup.ownerOf(directory));
            }
            if (!ours) {
                throw new VerificationException("native-loader: extraction parent " + directory + " is owned by uid "
                        + owner + "; it must be owned by root"
                        + (shared ? " (it is shared-writable)" : " or the current user")
                        + ". Set -Dio.euhedral.native.extract.dir to a private directory you own.");
            }
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
                    throw new VerificationException(
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
