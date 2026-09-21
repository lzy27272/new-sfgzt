package cn.sifangguan.hotelaios.dataretention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ImageFileCleanupTest {
    @TempDir Path root;
    @Test void unsafeNamespacesTraversalAndMismatchedFilesAreNeverDeleted() throws Exception {
        UUID tenant=UUID.randomUUID();
        var cleanup=new ImageFileCleanup(root.toRealPath().toString());
        for(String key: new String[]{"/etc/passwd",tenant+"/../../passwd",tenant+"/tasks/../x/a.jpg",tenant+"/operation-exports/x/a.pdf",
                UUID.randomUUID()+"/work-records/x/a.jpg",tenant+"/tasks/x\\a.jpg"}) {
            assertThatThrownBy(()->cleanup.delete(tenant,key,0,"a".repeat(64))).isInstanceOf(java.io.IOException.class);
        }
        String key=tenant+"/tasks/x/image.jpg";
        Path image=root.resolve(key); Files.createDirectories(image.getParent()); Files.writeString(image,"hello");
        assertThatThrownBy(()->cleanup.delete(tenant,key,5,"a".repeat(64))).hasMessage("OBJECT_HASH_MISMATCH");
        assertThat(Files.readString(image)).isEqualTo("hello");
    }
    @Test void symlinkCannotRedirectDeletion() throws Exception {
        UUID tenant=UUID.randomUUID();
        Path outside=Files.createTempDirectory("image-retention-outside-");
        Path sentinel=outside.resolve("image.jpg"); Files.writeString(sentinel,"keep");
        Path parent=root.resolve(tenant+"/tasks"); Files.createDirectories(parent);
        try { Files.createSymbolicLink(parent.resolve("linked"),outside); }
        catch(UnsupportedOperationException | java.nio.file.FileSystemException e) { org.junit.jupiter.api.Assumptions.abort("symlinks unavailable"); }
        var cleanup=new ImageFileCleanup(root.toRealPath().toString());
        assertThatThrownBy(()->cleanup.delete(tenant,tenant+"/tasks/linked/image.jpg",4,"a".repeat(64)))
                .hasMessage("SYMLINK_NOT_ALLOWED");
        assertThat(Files.readString(sentinel)).isEqualTo("keep");
    }
}
