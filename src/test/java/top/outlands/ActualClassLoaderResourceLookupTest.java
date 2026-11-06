package top.outlands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import top.outlands.foundation.boot.ActualClassLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class ActualClassLoaderResourceLookupTest {
    @TempDir Path directory;

    @Test
    void definitionsResolveOnceAndKeepVirtualReadsAcrossNestedLoads() throws Exception {
        Path jar = jar();
        try (CountingLoader loader = new CountingLoader(jar, true)) {
            Class<?> first = loader.findClass("scoped.First");
            assertSame(loader, first.getClassLoader());
            assertEquals(1, loader.lookups.get("scoped/First.class"));
            assertEquals(1, loader.lookups.get("scoped/Second.class"));
            assertEquals(2, loader.reads);
            assertTrue(loader.virtualReads >= 2);
            assertEquals("lookup-test", first.getPackage().getSpecificationTitle());
            assertSame(first, loader.findClass("scoped.First"));
            assertEquals(2, loader.reads);
        }
        Files.delete(jar); // Also checks that indexed and verification JAR handles were released on Windows.
    }

    @Test
    void excludedTransformersStillUseOneLookup() throws Exception {
        ActualClassLoader.transformerExceptions.add("scoped.");
        try (CountingLoader loader = new CountingLoader(jar(), false)) {
            assertNotNull(loader.findClass("scoped.First"));
            assertEquals(1, loader.lookups.get("scoped/First.class"));
            assertEquals(1, loader.reads);
        } finally {
            ActualClassLoader.transformerExceptions.remove("scoped.");
        }
    }

    @Test
    void signerAndSealedPackageMetadataSurviveSharedRead() throws Exception {
        Path jar = jar();
        Path store = directory.resolve("signing.p12");
        command("keytool", "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-dname", "CN=Loader Test",
                "-validity", "2", "-keystore", store.toString(), "-storepass", "fixturepass", "-keypass", "fixturepass");
        command("jarsigner", "-keystore", store.toString(), "-storepass", "fixturepass", jar.toString(), "fixture");
        try (CountingLoader loader = new CountingLoader(jar, false)) {
            Class<?> type = loader.findClass("scoped.First");
            assertNotNull(type.getProtectionDomain().getCodeSource().getCodeSigners());
            assertEquals(1, type.getProtectionDomain().getCodeSource().getCodeSigners().length);
            assertTrue(type.getPackage().isSealed());
            assertEquals(1, loader.lookups.get("scoped/First.class"));
        }
        Files.delete(jar);
    }

    private static void command(String executable, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = Path.of(System.getProperty("java.home"), "bin", executable + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "")).toString();
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }

    private Path jar() throws IOException {
        Path jar = directory.resolve("classes.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.SPECIFICATION_TITLE, "lookup-test");
        manifest.getMainAttributes().put(Attributes.Name.SEALED, "true");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (String name : new String[]{"scoped/First", "scoped/Second"}) {
                ClassWriter writer = new ClassWriter(0);
                writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
                writer.visitEnd();
                output.putNextEntry(new JarEntry(name + ".class"));
                output.write(writer.toByteArray());
                output.closeEntry();
            }
        }
        return jar;
    }

    private static class CountingLoader extends ActualClassLoader {
        final Map<String, Integer> lookups = new HashMap<>();
        boolean nested;
        int virtualReads;
        int reads;

        CountingLoader(Path jar, boolean nested) throws IOException {
            super(new URL[]{jar.toUri().toURL()}, ActualClassLoader.class.getClassLoader());
            this.nested = nested;
        }

        @Override protected URL findClassFile(String path) {
            lookups.merge(path, 1, Integer::sum);
            return super.findClassFile(path);
        }

        @Override public byte[] getClassBytes(String name) throws IOException {
            virtualReads++;
            if (nested && name.equals("scoped.First")) {
                nested = false;
                try { findClass("scoped.Second"); } catch (ClassNotFoundException error) { throw new IOException(error); }
            }
            return super.getClassBytes(name);
        }

        @Override protected byte[] readFully(InputStream input) throws IOException {
            reads++;
            return super.readFully(input);
        }
    }
}
