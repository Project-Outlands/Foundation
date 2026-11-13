package top.outlands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.outlands.foundation.boot.ActualClassLoader;
import top.outlands.foundation.boot.ClassPathIndex;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The index must answer what {@link URLClassLoader#findResource(String)} answers, or {@link ClassPathIndex#UNKNOWN}. */
class ClassPathIndexTest {

    @Test
    void newDirectoryPackagesShadowLaterJarsAndDeletedFilesFallThrough(@TempDir Path dir) throws Exception {
        Path classes = Files.createDirectory(dir.resolve("classes"));
        Path later = JarBuilder.first(dir, "later.jar").entry("generated/New.class", "jar").build();
        URL[] urls = {classes.toUri().toURL(), later.toUri().toURL()};
        try (ClassPathIndex index = new ClassPathIndex(urls);
             URLClassLoader reference = new URLClassLoader(urls, null)) {
            assertEquals(reference.findResource("generated/New.class"), index.lookup("generated/New.class"));
            Path generated = Files.createDirectories(classes.resolve("generated"));
            Path file = Files.writeString(generated.resolve("New.class"), "new directory class");
            assertEquals(file.toUri().toURL(), index.lookup("generated/New.class"));
            assertEquals(reference.findResource("generated/New.class"), index.lookup("generated/New.class"));
            Files.delete(file);
            assertEquals(reference.findResource("generated/New.class"), index.lookup("generated/New.class"));
        }
    }

    @Test
    void directoriesAddedLaterKeepJarAndManifestOrder(@TempDir Path dir) throws Exception {
        Path first = JarBuilder.first(dir, "first.jar").classPath("generated/").entry("same/Type.class", "first").build();
        Path last = JarBuilder.first(dir, "last.jar").entry("late/Type.class", "last").build();
        URL[] urls = {first.toUri().toURL(), last.toUri().toURL()};
        try (ClassPathIndex index = new ClassPathIndex(urls);
             URLClassLoader reference = new URLClassLoader(urls, null)) {
            Path generated = Files.createDirectories(dir.resolve("generated"));
            Files.writeString(Files.createDirectories(generated.resolve("same")).resolve("Type.class"), "shadowed");
            Path file = Files.writeString(Files.createDirectories(generated.resolve("late")).resolve("Type.class"), "generated");
            assertEquals(reference.findResource("same/Type.class"), index.lookup("same/Type.class"));
            assertEquals(file.toUri().toURL(), index.lookup("late/Type.class"));
            assertEquals(reference.findResource("late/Type.class"), index.lookup("late/Type.class"));
            assertNull(index.lookup("missing/Type.class"));
        }
    }

    @Test
    void nonCanonicalPathsDeferToTheJdk(@TempDir Path dir) throws Exception {
        try (ClassPathIndex index = new ClassPathIndex(new URL[]{dir.toUri().toURL()})) {
            for (String path : List.of("../Outside.class", "./Type.class", "a/../Type.class", "/Type.class", "a/./Type.class")) {
                assertSame(ClassPathIndex.UNKNOWN, index.lookup(path));
            }
        }
    }

    @Test
    void plainJarsAreAnsweredFromTheIndex(@TempDir Path dir) throws Exception {
        JarBuilder.first(dir, "first.jar")
                .entry("one/A.class", "A1")
                .entry("one/B.class", "B1")
                .entry("two/C.class", "C1")
                .build();
        JarBuilder.first(dir, "second.jar")
                .entry("one/A2.class", "A2")
                .entry("three/D.class", "D2")
                .build();
        JarBuilder.first(dir, "third.jar").entry("four/E.class", "E3").build();

        assertMatches(
                urls(dir, "first.jar", "second.jar", "third.jar"),
                List.of("one/A.class", "one/B.class", "one/A2.class", "two/C.class", "three/D.class",
                        "four/E.class", "one/Missing.class", "five/Missing.class"));
    }

    @Test
    void classesInTheDefaultPackageAreIndexed(@TempDir Path dir) throws Exception {
        JarBuilder.first(dir, "default.jar").entry("TopLevel.class", "top-level").build();
        JarBuilder.first(dir, "later.jar").entry("TopLevel.class", "shadowed").build();

        URL[] urls = urls(dir, "default.jar", "later.jar");
        assertMatches(urls, List.of("TopLevel.class", "Missing.class"));
    }

    @Test
    void aClassInSeveralJarsComesFromTheFirstOne(@TempDir Path dir) throws Exception {
        JarBuilder.first(dir, "a.jar").entry("dup/Target.class", "from-a").build();
        JarBuilder.first(dir, "b.jar").entry("dup/Target.class", "from-b").build();

        URL[] urls = urls(dir, "a.jar", "b.jar");
        assertMatches(urls, List.of("dup/Target.class"));
        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            assertEquals("from-a", read(loader, "dup/Target.class"), "the first jar on the classpath wins");
        }
    }

    @Test
    void manifestClassPathIsSearchedRightAfterItsJar(@TempDir Path dir) throws Exception {
        // b.jar is only reachable through a.jar's manifest and shadows c.jar
        JarBuilder.first(dir, "b.jar").entry("shadow/Target.class", "from-b").build();
        JarBuilder.first(dir, "a.jar").classPath("b.jar").entry("only/A.class", "only-a").build();
        JarBuilder.first(dir, "c.jar").entry("shadow/Target.class", "from-c").build();

        URL[] urls = urls(dir, "a.jar", "c.jar");
        assertMatches(urls, List.of("only/A.class", "shadow/Target.class"));
        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            assertEquals("from-b", read(loader, "shadow/Target.class"), "the Class-Path jar shadows a later jar");
        }
    }

    @Test
    void aManifestClassPathEntryAlsoOnTheClasspathKeepsItsPlace(@TempDir Path dir) throws Exception {
        // what ManifestCPParser produces: the Class-Path jars are also appended to the classpath
        JarBuilder.first(dir, "b.jar").entry("shadow/Target.class", "from-b").build();
        JarBuilder.first(dir, "a.jar").classPath("b.jar").entry("only/A.class", "only-a").build();
        JarBuilder.first(dir, "c.jar").entry("shadow/Target.class", "from-c").build();

        URL[] urls = urls(dir, "a.jar", "c.jar", "b.jar");
        assertMatches(urls, List.of("only/A.class", "shadow/Target.class"));
        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            assertEquals("from-b", read(loader, "shadow/Target.class"), "the Class-Path jar is moved, not duplicated");
        }
    }

    @Test
    void multiReleaseJarsServeTheVersionedClass(@TempDir Path dir) throws Exception {
        JarBuilder.first(dir, "multi.jar")
                .multiRelease()
                .entry("mr/Cls.class", "base")
                .entry("META-INF/versions/9/mr/Cls.class", "versioned")
                .build();

        URL[] urls = urls(dir, "multi.jar");
        assertMatches(urls, List.of("mr/Cls.class", "mr/Missing.class"));
        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            URL url = loader.findResource("mr/Cls.class");
            assertNotNull(url);
            assertTrue(url.toExternalForm().contains("META-INF/versions/9/"),
                    () -> "the runtime version's copy must be used, was " + url);
        }
    }

    @Test
    void directoriesAreIndexedToo(@TempDir Path dir) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path packageDir = Files.createDirectories(classes.resolve("pack"));
        Files.writeString(packageDir.resolve("FromDir.class"), "dir");
        JarBuilder.first(dir, "jar.jar").entry("pack/FromJar.class", "jar").entry("pack/FromDir.class", "shadowed").build();

        URL[] urls = {classes.toUri().toURL(), dir.resolve("jar.jar").toUri().toURL()};
        assertMatches(urls, List.of("pack/FromDir.class", "pack/FromJar.class", "pack/Missing.class"));
        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            assertEquals("dir", read(loader, "pack/FromDir.class"), "a directory earlier in the classpath wins");
        }
    }

    @Test
    void addedUrlsAreIndexed(@TempDir Path dir) throws Exception {
        JarBuilder.first(dir, "first.jar").entry("one/A.class", "A1").build();
        JarBuilder.first(dir, "added.jar").entry("late/B.class", "B").build();

        URL[] urls = urls(dir, "first.jar");
        URL added = dir.resolve("added.jar").toUri().toURL();
        try (ClassPathIndex index = new ClassPathIndex(urls);
             AddableClassLoader loader = new AddableClassLoader(urls)) {
            assertNull(index.lookup("late/B.class"), "not on the classpath yet");
            loader.addURL(added);
            index.add(added);
            assertEquals(
                    loader.findResource("late/B.class").toExternalForm(),
                    ((URL) index.lookup("late/B.class")).toExternalForm());
        }
    }

    @Test
    void classpathEntriesTheIndexCannotModelFallBack(@TempDir Path dir) throws Exception {
        JarBuilder.first(dir, "jar.jar").entry("one/A.class", "A1").build();
        URL[] urls = {new URL("http://example.invalid/remote.jar"), dir.resolve("jar.jar").toUri().toURL()};

        try (ClassPathIndex index = new ClassPathIndex(urls)) {
            assertSame(
                ClassPathIndex.UNKNOWN,
                index.lookup("one/A.class"),
                "an entry the index can't read makes everything after it unknown");
            assertSame(ClassPathIndex.UNKNOWN, index.lookup("two/Missing.class"));
        }
    }

    @Test
    void theLoaderUsesTheIndexForClassFiles(@TempDir Path dir) throws Exception {
        // a class file of an included package, in a jar that is not otherwise on the classpath
        Path classFile = Path.of(
                "build", "classes", "java", "main", "top", "outlands", "foundation", "IExplicitTransformer.class");
        JarBuilder.first(dir, "included.jar")
                .entry("top/outlands/foundation/IExplicitTransformer.class", Files.readAllBytes(classFile))
                .build();

        try (ActualClassLoader loader = new ActualClassLoader(
                new URL[] {dir.resolve("included.jar").toUri().toURL()}, ActualClassLoader.class.getClassLoader())) {
            Class<?> loaded = loader.loadClass("top.outlands.foundation.IExplicitTransformer");
            assertSame(loader, loaded.getClassLoader(), "the class must come from the jar");
        }
    }

    @Test
    void theLoaderStillFindsResourcesTheRegularWay(@TempDir Path dir) throws Exception {
        JarBuilder.first(dir, "res.jar").entry("assets/data.txt", "payload").entry("one/A.class", "A1").build();

        try (ActualClassLoader loader = new ActualClassLoader(
                new URL[] {dir.resolve("res.jar").toUri().toURL()}, ActualClassLoader.class.getClassLoader())) {
            try (var stream = loader.getResourceAsStream("assets/data.txt")) {
                assertEquals("payload", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertNull(loader.findResource("assets/missing.txt"));
        }
    }

    /** Asserts that the index and the JDK agree on every path, and that the index answered at all. */
    private static void assertMatches(URL[] urls, List<String> paths) throws IOException {
        try (ClassPathIndex index = new ClassPathIndex(urls);
             URLClassLoader reference = new URLClassLoader(urls, null)) {
            for (String path : paths) {
                final URL expected = reference.findResource(path);
                final Object actual = index.lookup(path);
                assertNotSame(ClassPathIndex.UNKNOWN, actual, () -> "the index gave up on " + path);
                if (expected == null) {
                    assertNull(actual, () -> "the index found " + actual + " where the JDK finds nothing: " + path);
                } else {
                    assertNotNull(actual, () -> "the index found nothing where the JDK finds " + expected + ": " + path);
                    assertEquals(expected.toExternalForm(), ((URL) actual).toExternalForm(), path);
                }
            }
        }
    }

    private static URL[] urls(Path dir, String... names) throws IOException {
        final List<URL> urls = new ArrayList<>(names.length);
        for (String name : names) {
            urls.add(dir.resolve(name).toUri().toURL());
        }
        return urls.toArray(new URL[0]);
    }

    /** A loader whose {@code addURL} is reachable from the test. */
    private static final class AddableClassLoader extends URLClassLoader {
        AddableClassLoader(URL[] urls) {
            super(urls, null);
        }

        @Override
        public void addURL(URL url) {
            super.addURL(url);
        }
    }

    private static String read(URLClassLoader loader, String path) throws IOException {
        try (var stream = loader.getResourceAsStream(path)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Writes a jar with a manifest and the given entries. */
    private static final class JarBuilder {
        private final Path file;
        private final Map<String, byte[]> entries = new LinkedHashMap<>();
        private String classPath;
        private boolean multiRelease;

        private JarBuilder(Path file) {
            this.file = file;
        }

        static JarBuilder first(Path dir, String name) {
            return new JarBuilder(dir.resolve(name));
        }

        JarBuilder entry(String path, String text) {
            entries.put(path, text.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        JarBuilder entry(String path, byte[] bytes) {
            entries.put(path, bytes);
            return this;
        }

        JarBuilder classPath(String value) {
            classPath = value;
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
            if (classPath != null) {
                manifest.append("Class-Path: ").append(classPath).append("\r\n");
            }
            manifest.append("\r\n");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
                out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                out.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    out.putNextEntry(new ZipEntry(entry.getKey()));
                    out.write(entry.getValue());
                    out.closeEntry();
                }
            }
            return file;
        }
    }
}
