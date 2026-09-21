package cn.sifangguan.hotelaios.dataretention;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Deletes exactly one validated image; never walks or recursively deletes directories. */
@Component
public class ImageFileCleanup {
    private final Path root;

    public ImageFileCleanup(@Value("${app.attachments.root:var/attachments}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public boolean delete(UUID tenantId, String objectKey, long size, String expectedSha) throws Exception {
        String prefix = tenantId + "/";
        if (!objectKey.startsWith(prefix) || objectKey.contains("\\")
                || !objectKey.matches("[a-zA-Z0-9._/-]+")
                || expectedSha == null || !expectedSha.matches("[0-9a-f]{64}")) {
            throw new IOException("UNSAFE_OBJECT_KEY_OR_HASH");
        }
        Path relative = Path.of(objectKey);
        if (relative.isAbsolute() || !relative.normalize().equals(relative)
                || relative.getNameCount() < 4
                || !java.util.Set.of("work-records", "tasks", "daily-reports").contains(relative.getName(1).toString())) {
            throw new IOException("UNSAFE_OBJECT_NAMESPACE");
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !root.toRealPath().equals(root)) throw new IOException("UNSAFE_STORAGE_ROOT");
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("OBJECT_OUTSIDE_STORAGE_ROOT");
        Path cursor = root;
        for (Path component : relative) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) throw new IOException("SYMLINK_NOT_ALLOWED");
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != size) {
            throw new IOException("OBJECT_SIZE_OR_TYPE_MISMATCH");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expectedSha)) {
            throw new IOException("OBJECT_HASH_MISMATCH");
        }
        Files.delete(target);
        return true;
    }
}
