package top.outlands.foundation.boot;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;


import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.CodeSigner;
import java.security.CodeSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static top.outlands.foundation.boot.Foundation.LOGGER;
import static top.outlands.foundation.boot.TransformerHolder.transformers;

@SuppressWarnings({"deprecation", "unused"})
public class ActualClassLoader extends URLClassLoader {

    public static final int BUFFER_SIZE = 1 << 12;
    /**
     * how many times in a row a bulk read may report "no progress" before the read is aborted
     */
    private static final int MAX_EMPTY_READS = 16;
    private final List<URL> sources;
    private final Set<String> sourceSet = new HashSet<>();
    // Why some plugins want to do this
    private final Object addURLLock = new Object();
    private ClassLoader parent = getClass().getClassLoader();
    public static final PrefixMatcher classLoaderInclusions = new PrefixMatcher();
    public static final PrefixMatcher classLoaderExceptions = new PrefixMatcher();
    public static final PrefixMatcher transformerExceptions = new PrefixMatcher();
    private final Map<String, Class<?>> cachedClasses = new ConcurrentHashMap<>();
    private final Map<String, Throwable> invalidClassesMap = new ConcurrentHashMap<>(32);
    private final Set<String> invalidClasses = ConcurrentHashMap.newKeySet();

    private final Map<String, byte[]> resourceCache = new ConcurrentHashMap<>(1024);
    private final Set<String> negativeResourceCache = ConcurrentHashMap.newKeySet();

    /** Answers class file lookups; other resources keep using {@link #findResource(String)}. */
    private final ClassPathIndex classPathIndex;

    private final ThreadLocal<byte[]> loadBuffer = new ThreadLocal<>();
    /** A scoped lookup, not a resource cache: keep virtual getClassBytes calls and nested loads intact. */
    private final ThreadLocal<ClassFileLookup> classFileLookup = new ThreadLocal<>();

    private record ClassFileLookup(String name, URL resource) {
    }

    private static final String[] RESERVED_NAMES = {"CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"};
    private static final boolean DUMP = Boolean.parseBoolean(System.getProperty("foundation.dump", "false"));
    private static final boolean VERBOSE = Boolean.parseBoolean(System.getProperty("foundation.verbose", "false"));
    private static final String TARGET = System.getProperty("foundation.target", "");
    private static File dumpSubDir;
    static TransformerHolder transformerHolder = new TransformerHolder();


    public ActualClassLoader(URL[] sources) {
        this(sources, null);
    }

    public ActualClassLoader(URL[] sources, ClassLoader loader) {
        super(sources, loader);
        if (parent != loader) {
            parent = loader;
        }
        this.sources = new CopyOnWriteArrayList<>(Arrays.asList(sources));
        this.sourceSet.addAll(this.sources.stream().map(URL::toExternalForm).toList());
        this.classPathIndex = new ClassPathIndex(sources);
        addClassLoaderInclusion("org.objectweb.asm.");
        addClassLoaderInclusion("org.spongepowered.asm.");
        addClassLoaderInclusion("org.lwjgl.");
        addClassLoaderInclusion("com.llamalad7.mixinextras.");
        addClassLoaderInclusion("net.minecraft");
        addClassLoaderInclusion("top.outlands.foundation.");
        addClassLoaderInclusion("com.cleanroommc.");
        addClassLoaderInclusion("ibxm.");
        addClassLoaderInclusion("paulscode.sound.codecs.");
        addClassLoaderInclusion("zone.rong.mixinbooter.");
        addClassLoaderInclusion("paulscode.sound.");
        addClassLoaderInclusion("com.mojang.");

        addClassLoaderExclusion0("java.");
        addClassLoaderExclusion0("org.slf4j.");
        addClassLoaderExclusion0("gnu.trove.");
        addClassLoaderExclusion0("com.google.common.");
        addClassLoaderExclusion0("com.google.gson.");
        addClassLoaderExclusion0("com.google.thirdparty.publicsuffix.");
        addClassLoaderExclusion0("com.ibm.icu.");
        addClassLoaderExclusion0("io.netty.");
        addClassLoaderExclusion0("jakarta.");
        addClassLoaderExclusion0("net.java.");
        addClassLoaderExclusion0("org.openjdk.");
        addClassLoaderExclusion0("oshi.");
        addClassLoaderExclusion0("it.unimi.dsi.");

        addClassLoaderExclusion0("org.apache.commons.compress.");
        addClassLoaderExclusion0("org.apache.commons.logging.");
        addClassLoaderExclusion0("org.apache.commons.codec.");
        addClassLoaderExclusion0("org.apache.commons.lang3.");
        addClassLoaderExclusion0("org.apache.commons.text.");
        addClassLoaderExclusion0("org.apache.commons.io.");
        addClassLoaderExclusion0("org.apache.logging.");
        addClassLoaderExclusion0("org.apache.hc.");
        addClassLoaderExclusion0("org.apache.maven.");

        addClassLoaderExclusion0("javax.accessibility.");
        addClassLoaderExclusion0("javax.annotation");
        addClassLoaderExclusion0("javax.crypto.");
        addClassLoaderExclusion0("javax.imageio.");
        addClassLoaderExclusion0("javax.lang");
        addClassLoaderExclusion0("javax.management.");
        addClassLoaderExclusion0("javax.naming.");
        addClassLoaderExclusion0("javax.net.");
        addClassLoaderExclusion0("javax.print.");
        addClassLoaderExclusion0("javax.script.");
        addClassLoaderExclusion0("javax.smartcardio.");
        addClassLoaderExclusion0("javax.sound.");
        addClassLoaderExclusion0("javax.sql.");
        addClassLoaderExclusion0("javax.swing.");
        addClassLoaderExclusion0("javax.tools.");
        addClassLoaderExclusion0("javax.transaction.");
        addClassLoaderExclusion0("javax.xml.");

        addClassLoaderExclusion0("net.minecraft.launchwrapper.LaunchClassLoader");
        addClassLoaderExclusion0("net.minecraft.launchwrapper.Launch");
        addClassLoaderExclusion0("top.outlands.foundation.boot.");
        addClassLoaderExclusion0("top.outlands.foundation.function.");
        addClassLoaderExclusion0("net.minecraftforge.server.terminalconsole.");

        addTransformerExclusion("org.lwjgl.");
        addTransformerExclusion("org.spongepowered.asm.bridge.");
        addTransformerExclusion("org.spongepowered.asm.lib.");
        addTransformerExclusion("org.spongepowered.asm.launch.");
        addTransformerExclusion("org.spongepowered.asm.logging.");
        addTransformerExclusion("org.spongepowered.asm.mixin.");
        addTransformerExclusion("org.spongepowered.asm.obfuscation.");
        addTransformerExclusion("org.spongepowered.asm.service.");
        addTransformerExclusion("org.spongepowered.asm.transformers.");
        addTransformerExclusion("org.spongepowered.asm.util.");
        addTransformerExclusion("org.spongepowered.include.com.google.");
        addTransformerExclusion("org.spongepowered.tools.");
        addTransformerExclusion("com.llamalad7.mixinextras.");
        if (DUMP) {
            File dumpDir = new File(Launch.minecraftHome, "CLASS_DUMP");

            if (!dumpDir.exists()) {
                dumpDir.mkdirs();
            }
            int i = 0;
            do {
                i++;
                dumpSubDir = new File(dumpDir, String.valueOf(i));
            } while (dumpSubDir.exists());
            dumpSubDir.mkdirs();
        }
        if (VERBOSE && !TARGET.isEmpty()) {
            LOGGER.info("Target class found, will print stacktrace when this class is loading: {}", TARGET);
        }
    }

    public static TransformerHolder getTransformerHolder() {
        return transformerHolder;
    }

    public void registerTransformer(String transformerClassName) {
        transformerHolder.registerTransformerFunction.accept(transformerClassName);
    }

    @Override
    public Class<?> findClass(final String name) throws ClassNotFoundException {
        if (invalidClasses.contains(name)) {
            Throwable invalidCause = invalidClassesMap.get(name);
            throw invalidCause == null
                ? new ClassNotFoundException(name)
                : new ClassNotFoundException(
                "Found " + name + " in invalid classes. Original failure:", invalidCause);
        }

        if (classLoaderExceptions.matches(name)) {
            return parent.loadClass(name);
        }

        final Class<?> cached = cachedClasses.get(name);
        if (cached != null) return cached;

        byte[] transformedClass;
        boolean contextPushed = false;
        final ClassFileLookup previousLookup = classFileLookup.get();
        boolean lookupPushed = false;

        try {
            final String transformedName = transformName(name);
            if (VERBOSE) {
                LOGGER.debug("Loading target class: {}", transformedName);
                if (!TARGET.isEmpty() && transformedName.equals(TARGET)) {
                    Arrays.stream(Thread.currentThread().getStackTrace()).forEach(LOGGER::debug);
                    transformerHolder.debugPrinter.run();
                }
            }
            final Class<?> transformedCached = cachedClasses.get(transformedName);
            if (transformedCached != null) return transformedCached;

            final String untransformedName = untransformName(name);

            final int lastDot = untransformedName.lastIndexOf('.');
            final String packageName = lastDot == -1 ? "" : untransformedName.substring(0, lastDot);
            final String fileName = untransformedName.replace('.', '/').concat(".class");
            final URL classFile = findClassFile(fileName);
            classFileLookup.set(new ClassFileLookup(cacheKey(untransformedName), classFile));
            lookupPushed = true;
            final JarInfo jarInfo = jarInfoFor(classFile);

            CodeSigner[] signers = null;
            Manifest manifest = null;
            Package pkg = null;
            if (lastDot > -1 && !untransformedName.startsWith("net.minecraft.")) {
                if (jarInfo != null) {
                    manifest = jarInfo.manifest;
                    if (manifest != null) {
                        final JarFile jarFile = jarInfo.jarFile;
                        final JarEntry entry = jarFile.getJarEntry(fileName);

                        pkg = getDefinedPackage(packageName);
                        getClassBytes(untransformedName);
                        signers = entry == null ? null : entry.getCodeSigners();
                        if (pkg == null) {
                            try {
                                definePackage(packageName, manifest, jarInfo.jarUrl);
                            } catch (IllegalArgumentException iae) {
                                if (getDefinedPackage(packageName) == null) {
                                    throw iae;
                                }
                            }
                        } else {
                            if (pkg.isSealed() && !pkg.isSealed(jarInfo.jarUrl)) {
                                LOGGER.warn("The jar file {} is trying to seal already secured path {}", jarFile.getName(), packageName);
                            } else if (isSealed(packageName, manifest)) {
                                LOGGER.warn("The jar file {} has a security seal for path {}, but that path is defined and not secure", jarFile.getName(), packageName);
                            }
                        }
                    }
                } else {
                    pkg = getPackage(packageName);
                    if (pkg == null) {
                        try {
                            definePackage(packageName, null, null, null, null, null, null, null);
                        } catch (IllegalArgumentException iae) {
                            if (getDefinedPackage(packageName) == null) {
                                throw iae;
                            }
                        }
                    } else if (pkg.isSealed() && classFile != null) {
                        LOGGER.warn("The URL {} is defining elements for sealed path {}", classFile, packageName);
                    }
                }
            }
            // expose the loading context to transformers without changing their signatures
            if (pkg == null && lastDot > -1) {
                pkg = getDefinedPackage(packageName);
            }
            final URL codeSourceUrl = jarInfo != null ? jarInfo.jarUrl : classFile;
            LoadingContext.push(pkg, manifest, codeSourceUrl);
            contextPushed = true;

            if (transformerExceptions.matches(name)) {
                try {
                    transformedClass = getClassBytes(name);
                    transformedClass = runExplicitTransformers(transformedName, transformedClass);
                    final CodeSource codeSource = classFile == null ? null : new CodeSource(classFile, signers);
                    if (transformedClass == null) throw new ClassNotFoundException(transformedName);
                    final Class<?> clazz = super.defineClass(name, transformedClass, 0, transformedClass.length, codeSource);
                    cachedClasses.put(name, clazz);
                    if (DUMP) {
                        saveClassBytes(transformedClass, transformedName);
                    }
                    return clazz;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            }

            transformedClass = runExplicitTransformers(transformedName, this.runTransformers(untransformedName, transformedName, getClassBytes(untransformedName)));
            if (DUMP) {
                saveClassBytes(transformedClass, transformedName);
            }

            final CodeSource codeSource = classFile == null ? null : new CodeSource(classFile, signers);
            if (transformedClass == null) throw new ClassNotFoundException();
            final Class<?> clazz = defineClass(transformedName, transformedClass, 0, transformedClass.length, codeSource);
            cachedClasses.put(transformedName, clazz);
            if (VERBOSE) {
                LOGGER.debug("Target class with name {} was successfully loaded.", transformedName);
                if (!TARGET.isEmpty() && transformedName.equals(TARGET)) {
                    LOGGER.debug("Class: {}", clazz);
                    LOGGER.debug("Class source: {}", clazz.getProtectionDomain().getCodeSource().getLocation());
                }
            }
            return clazz;
        } catch (Throwable e) {
            invalidClassesMap.put(name, e);
            invalidClasses.add(name);
            if (VERBOSE) {
                LOGGER.debug("Failed to load class {}, caused by {}", name, e);
                Arrays.stream(e.getStackTrace()).forEach(LOGGER::debug);
            }
            throw new ClassNotFoundException(name, e);
        } finally {
            if (lookupPushed) {
                if (previousLookup == null) classFileLookup.remove();
                else classFileLookup.set(previousLookup);
            }
            if (contextPushed) {
                LoadingContext.pop();
            }
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (classLoaderInclusions.matches(name)) {
            return findClass(name);
        }
        return super.loadClass(name, resolve);
    }

    public void saveClassBytes(final byte[] data, final String transformedName) {
        if (data == null) return;
        final File outFile = new File(dumpSubDir, transformedName.replace('.', File.separatorChar) + ".class");
        final File outDir = outFile.getParentFile();

        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        if (outFile.exists()) {
            outFile.delete();
        }

        try {
            final OutputStream output = new FileOutputStream(outFile);
            output.write(data);
            output.close();
        } catch (IOException ex) {
            LOGGER.warn("Could not save transformed class \"%s\"", transformedName, ex);
        }
    }

    public String untransformName(String name) {
        name = transformerHolder.unTransformNameFunction.apply(name);
        return name;
    }

    public String transformName(String name) {
        name = transformerHolder.transformNameFunction.apply(name);
        return name;
    }

    protected boolean isSealed(final String path, final Manifest manifest) {
        Attributes attributes = manifest.getAttributes(path);
        String sealed = null;
        if (attributes != null) {
            sealed = attributes.getValue(Attributes.Name.SEALED);
        }

        if (sealed == null) {
            attributes = manifest.getMainAttributes();
            if (attributes != null) {
                sealed = attributes.getValue(Attributes.Name.SEALED);
            }
        }
        return "true".equalsIgnoreCase(sealed);
    }

    protected URLConnection findCodeSourceConnectionFor(final String name) {
        final URL resource = findClassFile(name);
        if (resource != null) {
            try {
                return resource.openConnection();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return null;
    }

    protected byte[] runTransformers(final String name, final String transformedName, byte[] basicClass) {
        basicClass = transformerHolder.runTransformersFunction.apply(name, transformedName, basicClass);
        return basicClass;
    }

    protected byte[] runExplicitTransformers(final String transformedName, byte[] basicClass) {
        basicClass = transformerHolder.runExplicitTransformersFunction.apply(transformedName, basicClass);
        return basicClass;
    }

    public List<IClassTransformer> getTransformers() {
        return transformers;
    }

    @Override
    public void addURL(final URL url) {
        if (url == null) {
            return;
        }

        synchronized (addURLLock) {
            final String source = url.toExternalForm();
            if (sourceSet.contains(source)) {
                return;
            }
            super.addURL(url);
            sources.add(url);
            sourceSet.add(source);
            classPathIndex.add(url);
        }
    }

    public List<URL> getSources() {
        return sources;
    }

    @Override
    public void close() throws IOException {
        try (classPathIndex; Closeable cachedJars = this::closeJarInfos) {
            super.close();
        }
    }

    private void closeJarInfos() throws IOException {
        IOException failure = null;
        for (JarInfo jar : jarInfos.values()) {
            try {
                jar.jarFile.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        jarInfos.clear();
        if (failure != null) throw failure;
    }

    /** A jar of the classpath, with its manifest kept strongly referenced. */
    private record JarInfo(JarFile jarFile, Manifest manifest, URL jarUrl) {
    }

    /**
     * {@link JarURLConnection#getJarFile()} and {@link JarFile#getManifest()} are constant per jar but
     * expensive per class: the first parses the URL and looks the jar up again, the second re-parses the
     * manifest whenever the JDK's SoftReference to it has been collected.
     */
    private final Map<String, JarInfo> jarInfos = new ConcurrentHashMap<>();

    /**
     * @param classFile class file URL from {@link #findClassFile(String)}
     * @return its jar, or null for a directory on the classpath or no file at all
     */
    private JarInfo jarInfoFor(URL classFile) {
        if (classFile == null || !"jar".equals(classFile.getProtocol())) {
            return null;
        }
        final String file = classFile.getFile();
        final int separator = file.indexOf("!/");
        if (separator < 0) {
            return null;
        }
        final String key = "jar:" + file.substring(0, separator);
        final JarInfo cached = jarInfos.get(key);
        if (cached != null) {
            return cached;
        }
        final JarInfo loaded;
        try {
            final URLConnection connection = classFile.openConnection();
            if (!(connection instanceof JarURLConnection jarConnection)) {
                return null;
            }
            // These handles belong to this loader, not the JDK's process-wide JarFile cache.
            jarConnection.setUseCaches(false);
            final JarFile jarFile = jarConnection.getJarFile();
            try {
                loaded = new JarInfo(jarFile, jarFile.getManifest(), jarConnection.getJarFileURL());
            } catch (IOException | RuntimeException error) {
                closeSilently(jarFile);
                throw error;
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Could not read the jar of {}", classFile, e);
            return null;
        }
        final JarInfo previous = jarInfos.putIfAbsent(key, loaded);
        if (previous != null) closeSilently(loaded.jarFile);
        return previous == null ? loaded : previous;
    }

    private InputStream openClassStream(URL resource) throws IOException {
        final JarInfo jar = jarInfoFor(resource);
        final URLConnection connection = resource.openConnection();
        connection.setUseCaches(false);
        if (jar != null && connection instanceof JarURLConnection jarConnection) {
            final JarEntry entry = jar.jarFile.getJarEntry(jarConnection.getEntryName());
            if (entry == null) throw new java.io.FileNotFoundException(resource.toExternalForm());
            return jar.jarFile.getInputStream(entry);
        }
        return connection.getInputStream();
    }

    /**
     * @param path class file path such as {@code java/lang/Object.class}
     * @return the URL of the file, or null if no classpath entry has it
     */
    protected URL findClassFile(String path) {
        final Object indexed = classPathIndex.lookup(path);
        return indexed == ClassPathIndex.UNKNOWN ? findResource(path) : (URL) indexed;
    }

    protected byte[] readFully(InputStream stream) throws IOException {
        byte[] buffer = getOrCreateBuffer();
        int totalLength = 0;
        int emptyReads = 0;
        try {
            while (true) {
                if (totalLength == buffer.length) {
                    buffer = Arrays.copyOf(buffer, buffer.length << 1);
                }
                final int read = stream.read(buffer, totalLength, buffer.length - totalLength);
                if (read < 0) {
                    break;
                }
                if (read > 0) {
                    totalLength += read;
                    emptyReads = 0;
                    continue;
                }
                // read == 0: the bulk read made no progress, read a single byte instead
                if (++emptyReads > MAX_EMPTY_READS) {
                    throw new IOException("stream returned no data " + emptyReads + " times in a row");
                }
                final int singleByte = stream.read();
                if (singleByte < 0) {
                    break;
                }
                buffer[totalLength++] = (byte) singleByte;
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read class bytes from stream", e);
            throw e;
        }
        return Arrays.copyOf(buffer, totalLength);
    }

    protected byte[] getOrCreateBuffer() {
        byte[] buffer = loadBuffer.get();
        if (buffer == null) {
            buffer = new byte[BUFFER_SIZE];
            loadBuffer.set(buffer);
        }
        return buffer;
    }

    private void addClassLoaderExclusion0(String toExclude) {
        LOGGER.debug("Adding classloader exclusion {}", toExclude);
        classLoaderExceptions.add(toExclude);
    }

    private void addClassLoaderInclusion(String toInclude) {
        LOGGER.debug("Adding classloader inclusion {}", toInclude);
        classLoaderInclusions.add(toInclude);
    }

    @Deprecated
    public void addClassLoaderExclusion(String toExclude) {
        LOGGER.debug("A mod is trying to add CL exclusion {}, adding transformer exclude instead.", toExclude);
        addTransformerExclusion(toExclude);
    }

    public void addTransformerExclusion(String toExclude) {
        LOGGER.debug("Adding transformer exclusion {}", toExclude);
        transformerExceptions.add(toExclude);
    }

    public void removeTransformerExclusion(String toExclude) {
        LOGGER.debug("Removing transformer exclusion {}", toExclude);
        // the prefix is really removed: it stops matching and getTransformerExclusions() no longer lists it
        transformerExceptions.remove(toExclude);
    }

    /**
     * Callers pass class names both in internal ({@code a/b/C}) and binary ({@code a.b.C}) form, but
     * {@link #getClassBytes(String)} resolves either to the same file. Normalising to the binary form
     * keeps both spellings on one cache entry instead of reading the class file twice into two arrays.
     *
     * @param name a class name, with either {@code .} or {@code /} as package separator
     * @return the name in binary form, usable as a cache key
     */
    private static String cacheKey(String name) {
        return name.indexOf('/') < 0 ? name : name.replace('/', '.');
    }

    private URL classResource(String key) {
        final ClassFileLookup lookup = classFileLookup.get();
        if (lookup != null && lookup.name.equals(key)) return lookup.resource;
        return findClassFile(key.replace('.', '/').concat(".class"));
    }

    public byte[] getClassBytes(String name) throws IOException {
        final String key = cacheKey(name);
        if (negativeResourceCache.contains(key)) {
            return null;
        }
        final byte[] cached = resourceCache.get(key);
        if (cached != null) return cached;
        if (key.indexOf('.') == -1) {
            final String upperName = key.toUpperCase(Locale.ENGLISH);
            for (final String reservedName : RESERVED_NAMES) {
                if (upperName.startsWith(reservedName)) {
                    final byte[] data = getClassBytes("_" + key);
                    if (data != null) {
                        resourceCache.put(key, data);
                        return data;
                    }
                }
            }
        }

        InputStream classStream = null;
        try {
            final URL classResource = classResource(key);

            if (classResource == null) {
                negativeResourceCache.add(key);
                return null;
            }
            classStream = openClassStream(classResource);

            final byte[] data = readFully(classStream);
            resourceCache.put(key, data);
            return data;
        } finally {
            closeSilently(classStream);
        }
    }

    public byte[] testGetClassBytes(String name) throws IOException {
        final String key = cacheKey(name);
        final byte[] cached = resourceCache.get(key);
        if (cached != null) return cached;
        if (key.indexOf('.') == -1) {
            final String upperName = key.toUpperCase(Locale.ENGLISH);
            for (final String reservedName : RESERVED_NAMES) {
                if (upperName.startsWith(reservedName)) {
                    final byte[] data = getClassBytes("_" + key);
                    if (data != null) {
                        resourceCache.put(key, data);
                        return data;
                    }
                }
            }
        }

        InputStream classStream = null;
        try {
            final URL classResource = classResource(key);

            if (classResource == null) {
                return null;
            }
            classStream = openClassStream(classResource);

            return readFully(classStream);
        } finally {
            closeSilently(classStream);
        }
    }

    public void printDebugMessage() {
        transformerHolder.debugPrinter.run();
    }

    public Map<String, Class<?>> getCachedClasses() {
        return cachedClasses;
    }

    public Set<String> getInvalidClasses() {
        return Collections.unmodifiableSet(invalidClasses);
    }

    public void registerInvalidClass(String name) {
        LOGGER.debug("Registering invalid class {}", name);
        invalidClasses.add(name);
    }

    /**
     * Wrapper of defineClass()
     *
     * @param name   class name
     * @param buffer class byte array
     * @return the defined class
     */
    public Class<?> defineClass(String name, byte[] buffer) {
        if (DUMP) {
            saveClassBytes(buffer, name);
        }
        Class<?> clazz = defineClass(name, buffer, 0, buffer.length);
        cachedClasses.put(name, clazz);
        return clazz;
    }

    public Class<?> defineClass(String name, byte[] buffer, CodeSource codeSource) {
        if (DUMP) {
            saveClassBytes(buffer, name);
        }
        Class<?> clazz = defineClass(name, buffer, 0, buffer.length, codeSource);
        cachedClasses.put(name, clazz);
        return clazz;
    }

    /**
     * Check is a class loaded in this class loader without actually loading it
     *
     * @param name class name
     * @return if the class loaded
     */
    public boolean isClassLoaded(String name) {
        return findLoadedClass(name) != null;
    }

    /**
     * Check is a class exists without actually loading/defining it
     *
     * @param name class name
     * @return if the class exists
     */
    public boolean isClassExist(String name) {
        try {
            return testGetClassBytes(name) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    public Package definePackage(String name) {
        return definePackage(name, null, null);
    }

    protected static void closeSilently(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void clearNegativeEntries(Set<String> entriesToClear) {
        // negative entries are keyed in binary form, so a slash separated name must be normalised too
        for (final String entry : entriesToClear) {
            negativeResourceCache.remove(cacheKey(entry));
        }
    }

    public List<String> getTransformerExclusions() {
        return transformerExceptions.prefixes();
    }
}
