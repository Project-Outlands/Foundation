package top.outlands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.outlands.foundation.boot.ArchiveIndex;
import top.outlands.foundation.boot.ClassPathIndex;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The index must answer what {@link JarFile} answers: the same entry for a name, the same bytes, the
 * same packages, and a prefix query over the contiguous range of names that start with it.
 */
class ArchiveIndexTest {

    @Test
    void everyEntryIsFoundTheSameWayJarFileFindsIt(@TempDir Path dir) throws Exception {
        final Path jar = JarBuilder.create(dir, "plain.jar")
                .entry("a/One.class", "1")
                .entry("a/Two.class", "2")
                .entry("b/Three.class", "3")
                .entry("Top.class", "top")
                .entry("a/one.class", "lower case sorts late")
                .build();
        try (JarFile file = open(jar)) {
            final ArchiveIndex index = new ArchiveIndex(file);
            for (String name : List.of("a/One.class", "a/Two.class", "b/Three.class", "Top.class", "a/one.class")) {
                final JarEntry expected = file.getJarEntry(name);
                assertNotNull(expected, name);
                assertEquals(name, ArchiveIndex.realName(index.find(name)), name);
            }
            assertNull(index.find("a/Missing.class"));
            assertNull(index.find("a/"));
            assertNull(index.find(""));
        }
    }

    @Test
    void prefixWalksTheContiguousRangeAndKeepLookupsWorking(@TempDir Path dir) throws Exception {
        final Path jar = JarBuilder.create(dir, "prefix.jar")
                .entry("net/minecraft/world/World.class", "1")
                .entry("net/minecraft/world/World$1.class", "2")
                .entry("net/minecraft/world/WorldProvider.class", "3")
                .entry("net/minecraft/item/Item.class", "4")
                .entry("com/example/Thing.class", "5")
                .build();
        try (JarFile file = open(jar)) {
            final ArchiveIndex index = new ArchiveIndex(file);
            final List<String> collected = collect(index, "net/minecraft/world/");
            assertEquals(
                    List.of("net/minecraft/world/World.class", "net/minecraft/world/World$1.class",
                            "net/minecraft/world/WorldProvider.class"),
                    collected,
                    "the scan must be complete and hold nothing else");
            assertEquals(List.of(), collect(index, "net/minecraft/world/Nothing"));
            assertEquals(4, collect(index, "net/").size(), "a shorter prefix that names a directory matches its files");
            int total = 0;
            for (var all = file.entries(); all.hasMoreElements(); ) {
                if (!all.nextElement().getName().endsWith("/")) {
                    total++;
                }
            }
            assertEquals(total, index.forEachWithPrefix("", entry -> { }), "an empty prefix matches everything");
            // the sorted view is what answers now, and it must still agree with JarFile
            assertEquals("net/minecraft/world/World.class",
                    ArchiveIndex.realName(index.find("net/minecraft/world/World.class")));
            assertNull(index.find("net/minecraft/world/Missing.class"));
        }
    }

    @Test
    void aMultiReleaseArchiveServesTheVersionedCopy(@TempDir Path dir) throws Exception {
        final Path jar = JarBuilder.create(dir, "multi.jar")
                .multiRelease()
                .entry("mr/Cls.class", "base")
                .entry("META-INF/versions/9/mr/Cls.class", "versioned")
                .build();
        try (JarFile file = open(jar)) {
            final ArchiveIndex index = new ArchiveIndex(file);
            final List<String> seen = new ArrayList<>();
            index.forEachWithPrefix("mr/", entry -> seen.add(ArchiveIndex.realName(entry)));
            assertEquals(List.of("META-INF/versions/9/mr/Cls.class"), seen,
                    "the versioned copy is served under the plain name");
            assertArrayEquals("versioned".getBytes(StandardCharsets.UTF_8),
                    index.read(index.find("mr/Cls.class")));
        }
    }

    @Test
    void readReturnsTheStoredBytes(@TempDir Path dir) throws Exception {
        final String large = "x".repeat(12_345);
        final Path jar = JarBuilder.create(dir, "bytes.jar")
                .entry("a/Small.class", "small")
                .entry("a/Large.class", large)
                .stored("a/Stored.class", "stored entry")
                .build();
        try (JarFile file = open(jar)) {
            final ArchiveIndex index = new ArchiveIndex(file);
            assertArrayEquals("small".getBytes(StandardCharsets.UTF_8), index.read(index.find("a/Small.class")));
            assertArrayEquals(large.getBytes(StandardCharsets.UTF_8), index.read(index.find("a/Large.class")));
            assertArrayEquals("stored entry".getBytes(StandardCharsets.UTF_8), index.read(index.find("a/Stored.class")),
                    "an uncompressed entry reads through the same path");
            assertNull(index.read(null), "a name that is not there reads as nothing");
        }
    }

    @Test
    void nonAsciiNamesAreFoundToo(@TempDir Path dir) throws Exception {
        final Path jar = JarBuilder.create(dir, "unicode.jar")
                .entry("pkg/Ünïcode.class", "u")
                .entry("pkg/plain.class", "p")
                .build();
        try (JarFile file = open(jar)) {
            final ArchiveIndex index = new ArchiveIndex(file);
            assertEquals("pkg/Ünïcode.class", ArchiveIndex.realName(index.find("pkg/Ünïcode.class")));
            assertEquals(List.of("pkg/Ünïcode.class"), collect(index, "pkg/Ü"));
            assertNull(index.find("pkg/Unicode.class"));
        }
    }

    @Test
    void packagesFollowThePackageTableRule(@TempDir Path dir) throws Exception {
        final Path jar = JarBuilder.create(dir, "packages.jar")
                .entry("TopLevel.class", "top")
                .entry("a/b/C.class", "c")
                .multiRelease()
                .entry("META-INF/versions/9/a/b/C.class", "c9")
                .build();
        try (JarFile file = open(jar)) {
            final Set<String> packages = new LinkedHashSet<>(new ArchiveIndex(file).packages());
            assertTrue(packages.contains(""), "the default package is a package");
            assertTrue(packages.contains("a/b/"));
            assertTrue(packages.contains("META-INF/versions/9/a/b/"));
        }
    }

    @Test
    void theIndexReadsWhatTheUrlStreamReads(@TempDir Path dir) throws Exception {
        final Path jar = JarBuilder.create(dir, "read.jar")
                .entry("one/A.class", "a")
                .entry("two/B.class", "b")
                .build();
        final ClassPathIndex index = new ClassPathIndex(new URL[] {jar.toUri().toURL()});
        for (String path : List.of("one/A.class", "two/B.class")) {
            final URL url = (URL) index.lookup(path);
            assertArrayEquals(url.openStream().readAllBytes(), (byte[]) index.read(path), path);
        }
        assertNull(index.read("one/Missing.class"), "nothing to read means null, not the empty array");
        assertSame(ClassPathIndex.UNKNOWN, index.read("one/A b.class"), "an unindexable path falls back");
    }

    @Test
    void directoryEntriesAreReadToo(@TempDir Path dir) throws Exception {
        final Path classes = Files.createDirectories(dir.resolve("classes"));
        final Path packageDir = Files.createDirectories(classes.resolve("pkg"));
        Files.write(packageDir.resolve("FromDir.class"), "dir".getBytes(StandardCharsets.UTF_8));

        final ClassPathIndex index = new ClassPathIndex(new URL[] {classes.toUri().toURL()});
        assertArrayEquals("dir".getBytes(StandardCharsets.UTF_8), (byte[]) index.read("pkg/FromDir.class"));
        assertNull(index.read("pkg/Missing.class"));
    }

    /** The construction production uses; the one argument constructor does not map multi-release names. */
    private static JarFile open(Path jar) throws IOException {
        return new JarFile(jar.toFile(), false, java.util.zip.ZipFile.OPEN_READ, JarFile.runtimeVersion());
    }

    private static List<String> collect(ArchiveIndex index, String prefix) {
        final List<String> names = new ArrayList<>();
        index.forEachWithPrefix(prefix, entry -> names.add(entry.getName()));
        return names;
    }

    /** Writes a jar with a manifest and the given entries. */
    private static final class JarBuilder {
        private final Path file;
        private final Map<String, byte[]> entries = new LinkedHashMap<>();
        private final Map<String, Boolean> stored = new LinkedHashMap<>();
        private boolean multiRelease;

        private JarBuilder(Path file) {
            this.file = file;
        }

        static JarBuilder create(Path dir, String name) {
            return new JarBuilder(dir.resolve(name));
        }

        JarBuilder entry(String path, String text) {
            entries.put(path, text.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        JarBuilder stored(String path, String text) {
            entry(path, text);
            stored.put(path, Boolean.TRUE);
            return this;
        }

        JarBuilder multiRelease() {
            multiRelease = true;
            return this;
        }

        Path build() throws IOException {
            final StringBuilder manifest = new StringBuilder("Manifest-Version: 1.0\r\n");
            if (multiRelease) {
                manifest.append("Multi-Release: true\r\n");
            }
            manifest.append("\r\n");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
                out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                out.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    final ZipEntry zipEntry = new ZipEntry(entry.getKey());
                    if (stored.containsKey(entry.getKey())) {
                        zipEntry.setMethod(ZipEntry.STORED);
                        zipEntry.setSize(entry.getValue().length);
                        zipEntry.setCrc(crc(entry.getValue()));
                    }
                    out.putNextEntry(zipEntry);
                    out.write(entry.getValue());
                    out.closeEntry();
                }
            }
            return file;
        }

        private static long crc(byte[] bytes) {
            final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(bytes);
            return crc.getValue();
        }
    }
}
