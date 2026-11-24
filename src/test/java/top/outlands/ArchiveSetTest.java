package top.outlands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.outlands.foundation.boot.ArchiveIndex;
import top.outlands.foundation.boot.ArchiveSet;
import top.outlands.foundation.boot.ClassPathIndex;
import top.outlands.foundation.install.ZipFingerprint;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One archive, one handle: opening the same jar twice must hand back the same index, and the class path index must
 * reuse what the set already has instead of reading the central directory again.
 */
class ArchiveSetTest {

    @Test
    void openingTheSameArchiveTwiceHandsBackTheSameIndex(@TempDir Path dir) throws Exception {
        final Path jar = jar(dir, "one.jar", Map.of("a/B.class", "b"));
        final ArchiveSet set = new ArchiveSet();

        final ArchiveIndex first = set.open(jar);
        final ArchiveIndex second = set.open(jar);

        assertSame(first, second);
        assertEquals(1, set.size());
        assertEquals(1, first.packages().size());
    }

    @Test
    void theSameFileUnderAnotherSpellingIsTheSameArchive(@TempDir Path dir) throws Exception {
        final Path jar = jar(dir, "spell.jar", Map.of("a/B.class", "b"));
        final ArchiveSet set = new ArchiveSet();

        final ArchiveIndex first = set.open(jar);
        final ArchiveIndex second = set.open(dir.resolve(".").resolve("spell.jar").resolve("..").resolve("spell.jar"));

        assertSame(first, second, "the path is normalised before it is used as a key");
        assertEquals(1, set.size());
    }

    @Test
    void anArchiveThatIsNotOpenIsNotThere(@TempDir Path dir) throws Exception {
        final Path jar = jar(dir, "later.jar", Map.of("a/B.class", "b"));
        final ArchiveSet set = new ArchiveSet();

        assertNull(set.of(jar));
        set.open(jar);
        assertNotNull(set.of(jar));
    }

    @Test
    void theFingerprintMatchesTheOneComputedFromTheFile(@TempDir Path dir) throws Exception {
        final Path jar = jar(dir, "finger.jar", Map.of("a/B.class", "b", "assets/x.txt", "text"));
        final ArchiveSet set = new ArchiveSet();

        final ArchiveIndex index = set.open(jar);

        assertEquals(ZipFingerprint.of(jar), index.fingerprint());
        assertSame(index.fingerprint(), index.fingerprint(), "the aggregate is computed once");
    }

    @Test
    void theClassPathIndexReusesWhatTheSetHasOpen(@TempDir Path dir) throws Exception {
        final Path jar = jar(dir, "shared.jar", Map.of("a/B.class", "b"));
        final ArchiveSet set = new ArchiveSet();
        final ArchiveIndex opened = set.open(jar);

        final ClassPathIndex index = new ClassPathIndex(new URL[] {jar.toUri().toURL()}, set);

        assertSame(opened, set.of(jar), "no second handle for a jar the set already had");
        assertEquals(1, set.size());
        final URL found = (URL) index.lookup("a/B.class");
        assertNotNull(found);
        assertTrue(found.toExternalForm().endsWith("a/B.class"));
        assertEquals("b", new String((byte[]) index.read("a/B.class"), StandardCharsets.UTF_8));
    }

    private static Path jar(Path dir, String name, Map<String, String> entries) throws IOException {
        final Path file = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(entries).entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }
}
