package top.outlands.foundation.transformer;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The transformers have to patch the real ASM classes, not a stand-in: the patched class files must
 * pass the JVM verifier, the visitors must report ASM9 after construction, and the writer must route
 * its hierarchy lookup through the Foundation delegate.
 */
class ASMTransformerTest {

    private static final int ASM9 = 589824;
    private static final String ASM_PACKAGE = "org.objectweb.asm.";

    @Test
    void visitorConstructorsAreBumpedToAsm9AndStillLoad() throws Exception {
        final Map<String, byte[]> classes = asmClasses();
        final ASMVisitorTransformer transformer = new ASMVisitorTransformer();
        final List<String> visitors = List.of(
                ASM_PACKAGE + "ClassVisitor",
                ASM_PACKAGE + "FieldVisitor",
                ASM_PACKAGE + "MethodVisitor");
        for (final String visitor : visitors) {
            final byte[] patched = transformer.transform(classes.get(visitor));
            assertFalse(Arrays.equals(classes.get(visitor), patched), visitor + " must be patched");
            classes.put(visitor, patched);
        }

        // the bump must be silent: a visitor that still called Foundation.add would not link here
        final IsolatedLoader loader = new IsolatedLoader(classes,
                Set.of("top.outlands.foundation.boot.Foundation"));
        for (final String visitor : visitors) {
            final Class<?> visitorClass = loader.loadClass(visitor);
            final String simpleName = visitor.substring(visitor.lastIndexOf('.') + 1);
            final Field api = visitorClass.getDeclaredField("api");
            api.setAccessible(true);

            // an old (but valid) api gets bumped, so mods written for ASM 4-8 keep working
            final String probeName = "probe/" + simpleName + "Probe";
            final Class<?> probe = loader.define(probeName.replace('/', '.'),
                    probe(probeName, visitor.replace('.', '/'), false));
            final Object upgraded = probe.getConstructor(int.class).newInstance(Opcodes.ASM8);
            assertEquals(ASM9, api.getInt(upgraded), visitor + " should report ASM9 after construction");

            // an up to date api is left alone
            final Object current = probe.getConstructor(int.class).newInstance(Opcodes.ASM9);
            assertEquals(ASM9, api.getInt(current));

            // the delegating constructor is covered on its own, it does not go through the one above
            final String delegateProbeName = "probe/" + simpleName + "DelegateProbe";
            final Class<?> delegateProbe = loader.define(delegateProbeName.replace('/', '.'),
                    probe(delegateProbeName, visitor.replace('.', '/'), true));
            final Object delegated = delegateProbe.getConstructor(int.class).newInstance(Opcodes.ASM8);
            assertEquals(ASM9, api.getInt(delegated),
                    visitor + " should report ASM9 after the delegating constructor");
        }
    }

    @Test
    void classWriterResolvesTheCommonSuperClassThroughTheDelegate() throws Exception {
        final Map<String, byte[]> classes = asmClasses();
        final String classWriter = ASM_PACKAGE + "ClassWriter";
        final byte[] patched = new ASMClassWriterTransformer().transform(classes.get(classWriter));
        assertFalse(Arrays.equals(classes.get(classWriter), patched), "ClassWriter must be patched");
        classes.put(classWriter, patched);

        // the original implementation would look these up through the isolated loader and fail;
        // only the delegate, which uses the application loader, can resolve them
        final IsolatedLoader loader = new IsolatedLoader(classes,
                Set.of(Base.class.getName(), Child.class.getName()));
        final Class<?> writerClass = loader.loadClass(classWriter);
        final Object writer = writerClass.getConstructor(int.class).newInstance(0);
        final Method commonSuperClass =
                writerClass.getDeclaredMethod("getCommonSuperClass", String.class, String.class);
        commonSuperClass.setAccessible(true);

        final String base = internalName(Base.class);
        final String child = internalName(Child.class);
        assertEquals(base, commonSuperClass.invoke(writer, base, child));
        assertEquals(base, commonSuperClass.invoke(writer, child, base));
        assertEquals(child, commonSuperClass.invoke(writer, child, child));
        assertEquals("java/lang/Object", commonSuperClass.invoke(writer, base, internalName(String.class)));
    }

    @Test
    void classesWithoutThePatchedMembersAreLeftUntouched() throws Exception {
        final byte[] opcodes = resource("org/objectweb/asm/Opcodes.class");
        assertArrayEquals(opcodes, new ASMVisitorTransformer().transform(opcodes));
        assertArrayEquals(opcodes, new ASMClassWriterTransformer().transform(opcodes));
    }

    // ---------------------------------------------------------------- helpers

    /** Reads every ASM class from the dependency jar so an isolated loader can own the whole package. */
    private static Map<String, byte[]> asmClasses() throws IOException {
        final Path jar;
        try {
            jar = Path.of(ClassWriter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        final Map<String, byte[]> classes = new HashMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (final JarEntry entry : Collections.list(file.entries())) {
                final String name = entry.getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                try (InputStream in = file.getInputStream(entry)) {
                    classes.put(name.substring(0, name.length() - ".class".length()).replace('/', '.'),
                            in.readAllBytes());
                }
            }
        }
        return classes;
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = ASMTransformerTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }

    /** A concrete visitor so the patched abstract constructor can actually run. */
    private static byte[] probe(String name, String superName, boolean delegate) {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, superName, null);
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ILOAD, 1);
        if (delegate) {
            constructor.visitInsn(Opcodes.ACONST_NULL);
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "(IL" + superName + ";)V", false);
        } else {
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "(I)V", false);
        }
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(3, 2);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String internalName(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    /** Child first for the ASM package, so the patched classes are the ones that get linked. */
    private static final class IsolatedLoader extends ClassLoader {
        private final Map<String, byte[]> classes;
        private final Set<String> hidden;

        IsolatedLoader(Map<String, byte[]> classes) {
            this(classes, Set.of());
        }

        IsolatedLoader(Map<String, byte[]> classes, Set<String> hidden) {
            super(ASMTransformerTest.class.getClassLoader());
            this.classes = classes;
            this.hidden = hidden;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    final byte[] bytes = classes.get(name);
                    if (bytes != null) {
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } else if (hidden.contains(name)) {
                        throw new ClassNotFoundException(name);
                    } else {
                        loaded = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static class Base {
    }

    private static final class Child extends Base {
    }
}
