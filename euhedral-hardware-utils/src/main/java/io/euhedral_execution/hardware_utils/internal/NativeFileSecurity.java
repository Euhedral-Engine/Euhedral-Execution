package io.euhedral_execution.hardware_utils.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

interface NativeFileSecurity {

    static NativeFileSecurity forOperatingSystem(String canonicalOperatingSystem) throws IOException {
        return switch (canonicalOperatingSystem) {
            case "windows" -> new WindowsSecurity();
            case "linux", "macos" -> new PosixSecurity();
            default ->
                throw new IOException("native-loader: unsupported filesystem policy for " + canonicalOperatingSystem);
        };
    }

    void secureDirectory(Path path) throws IOException;

    void secureMarker(Path path) throws IOException;

    void secureLibrary(Path path) throws IOException;

    final class PosixSecurity implements NativeFileSecurity {

        private static void set(Path path, String permissions) throws IOException {
            PosixFileAttributeView view =
                    Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                throw new IOException("native-loader: POSIX file attributes are unavailable for " + path);
            }
            view.setPermissions(PosixFilePermissions.fromString(permissions));
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
