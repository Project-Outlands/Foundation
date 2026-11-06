package top.outlands.foundation.boot;

import java.io.File;
import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Maps a package directory to the classpath entries containing it, so finding a class file probes only
 * the jars that have the package instead of every classpath entry. Answers what
 * {@link java.net.URLClassLoader#findResource(String)} answers for a class file, or {@link #UNKNOWN}.
 */

@SuppressWarnings("deprecation")
public final class ClassPathIndex implements Closeable {

    /** Returned by {@link #lookup(String)} when the caller has to use the regular findResource lookup. */
    public static final Object UNKNOWN = new Object();

    private static final int[] NO_POSITIONS = new int[0];
    private static final URL[] NO_URLS = new URL[0];

    private sealed interface Source permits JarSource, DirSource {
        URL find(String path);
    }

    private final List<Source> sources = new ArrayList<>();
    /** Classpath positions of each package directory, ascending. */
    private final Map<String, int[]> packages = new HashMap<>();
    /** Directories are mutable: probe them at their original positions without scanning their trees. */
    private int[] directories = NO_POSITIONS;
    /** URLs already placed, so a Class-Path entry is used once. */
    private final Set<String> placed = new HashSet<>();
    /** First position whose contribution is unknown; MAX_VALUE while the whole order is known. */
    private int barrier = Integer.MAX_VALUE;
    private boolean closed;

    public ClassPathIndex(URL[] urls) {
        synchronized (this) {
            for (URL url : urls) {
                addLocked(url);
            }
        }
    }

    /** Appends a classpath entry; duplicates are ignored. */
    public void add(URL url) {
        if (url == null) {
            return;
        }
        synchronized (this) {
            if (closed) return;
            addLocked(url);
        }
    }

    /**
     * @return the URL findResource would return, null if it would return null, or {@link #UNKNOWN}
     */
    public synchronized Object lookup(String path) {
        if (closed) return UNKNOWN;
        if (barrier == 0 || !isPlain(path)) {
            // the JDK percent-encodes the URL; not worth reproducing for class files
            return UNKNOWN;
        }
        final int slash = path.lastIndexOf('/');
        final int[] positions = packages.getOrDefault(slash < 0 ? "" : path.substring(0, slash + 1), NO_POSITIONS);
        int packageIndex = 0;
        int directoryIndex = 0;
        while (packageIndex < positions.length || directoryIndex < directories.length) {
            final int position;
            if (directoryIndex < directories.length
                    && (packageIndex == positions.length || directories[directoryIndex] < positions[packageIndex])) {
                position = directories[directoryIndex++];
            } else {
                position = positions[packageIndex++];
            }
            if (position >= barrier) {
                return UNKNOWN;
            }
            final URL url = sources.get(position).find(path);
            if (url != null) {
                return url;
            }
        }
        return barrier == Integer.MAX_VALUE ? null : UNKNOWN;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (Source source : sources) {
            if (source instanceof JarSource jar) {
                try {
                    jar.file.close();
                } catch (IOException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
        }
        sources.clear();
        packages.clear();
        directories = NO_POSITIONS;
        placed.clear();
        if (failure != null) throw failure;
    }

    private void addLocked(URL url) {
        if (barrier != Integer.MAX_VALUE) {
            return;
        }
        // the JDK searches a jar's Class-Path entries right after that jar, depth first
        final Deque<URL> pending = new ArrayDeque<>();
        pending.push(url);
        while (!pending.isEmpty()) {
            final URL current = pending.pop();
            if (!placed.add(noFragment(current))) {
                continue; // already on the search path
            }
            final Source source = create(current);
            if (source == null) {
                if (isUnknown(current)) {
                    barrier = sources.size();
                    return;
                }
                continue;
            }
            final int position = sources.size();
            sources.add(source);
            indexPackages(source, position);
            final URL[] classPath = classPathOf(source);
            for (int i = classPath.length - 1; i >= 0; i--) {
                pending.push(classPath[i]);
            }
        }
    }

    private void indexPackages(Source source, int position) {
        if (source instanceof DirSource) {
            directories = Arrays.copyOf(directories, directories.length + 1);
            directories[directories.length - 1] = position;
            return;
        }
        for (String packagePath : ((JarSource) source).packages()) {
            final int[] old = packages.getOrDefault(packagePath, NO_POSITIONS);
            final int[] updated = Arrays.copyOf(old, old.length + 1);
            updated[old.length] = position;
            packages.put(packagePath, updated);
        }
    }

    /** Whether the entry can serve resources the index cannot predict, invalidating later positions. */
    private static boolean isUnknown(URL url) {
        return !"file".equalsIgnoreCase(url.getProtocol()) || url.getFile().endsWith("!/");
    }

    private Source create(URL url) {
        if (!"file".equalsIgnoreCase(url.getProtocol())) {
            return null;
        }
        final File file = toFile(url);
        if (file == null) {
            return null;
        }
        if (url.getFile().endsWith("/")) {
            return new DirSource(url, file);
        }
        if (!file.isFile()) {
            return null; // the JDK skips classpath entries it cannot open
        }
        final JarFile jarFile;
        try {
            jarFile = new JarFile(file, false, ZipFile.OPEN_READ, JarFile.runtimeVersion());
        } catch (IOException | RuntimeException e) {
            return null;
        }
        try {
            return new JarSource(url, new URL("jar", "", -1, url.toExternalForm() + "!/"), jarFile);
        } catch (MalformedURLException e) {
            closeSilently(jarFile);
            return null;
        }
    }

    private URL[] classPathOf(Source source) {
        if (!(source instanceof JarSource jarSource)) {
            return NO_URLS;
        }
        final Manifest manifest;
        try {
            manifest = jarSource.file.getManifest();
        } catch (IOException | RuntimeException e) {
            return NO_URLS;
        }
        if (manifest == null) {
            return NO_URLS;
        }
        final String value = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        if (value == null) {
            return NO_URLS;
        }
        final List<URL> urls = new ArrayList<>();
        final StringTokenizer tokenizer = new StringTokenizer(value);
        while (tokenizer.hasMoreTokens()) {
            final String entry = tokenizer.nextToken();
            try {
                final URL resolved = new URL(jarSource.source, entry);
                // a ':' is either a scheme or a Windows drive letter
                if (entry.indexOf(':') >= 0 && !"file".equalsIgnoreCase(resolved.getProtocol())) {
                    continue;
                }
                urls.add(resolved);
            } catch (MalformedURLException ignored) {
                // the JDK ignores entries it cannot resolve as well
            }
        }
        return urls.toArray(NO_URLS);
    }

    private record DirSource(URL base, File dir) implements Source {

        @Override
        public URL find(String path) {
            if (!new File(dir, path).exists()) {
                return null;
            }
            try {
                return new URL(base, path);
            } catch (MalformedURLException e) {
                return null;
            }
        }
    }

    /**
     * @param source  Jar URL, which Class-Path entries are resolved against. 
     * @param base  {@code jar:...!/} URL, which resources are resolved against.  */
    private record JarSource(URL source, URL base, JarFile file) implements Source {

        @Override
        public URL find(String path) {
            final ZipEntry entry = file.getEntry(path);
            if (entry == null) {
                return null;
            }
            // a multi-release jar serves the copy the runtime version selects
            final String realName = entry instanceof JarEntry jarEntry ? jarEntry.getRealName() : null;
            try {
                return new URL(base, realName == null ? path : realName);
            } catch (MalformedURLException e) {
                return null;
            }
        }

        private List<String> packages() {
            final Set<String> result = new HashSet<>();
            final Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                final String name = entries.nextElement().getName();
                if (name.endsWith("/")) {
                    continue;
                }
                // versioned copies are asked for under their unversioned name
                result.add(packageOf(name));
                result.add(packageOf(unversion(name)));
            }
            return new ArrayList<>(result);
        }

        private static final String VERSIONS = "META-INF/versions/";

        private static String packageOf(String name) {
            final int slash = name.lastIndexOf('/');
            return slash < 0 ? "" : name.substring(0, slash + 1);
        }

        private static String unversion(String name) {
            if (!name.startsWith(VERSIONS)) {
                return name;
            }
            final int slash = name.indexOf('/', VERSIONS.length());
            return slash < 0 ? name : name.substring(slash + 1);
        }
    }

    private static void closeSilently(JarFile file) {
        try {
            file.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    private static File toFile(URL url) {
        try {
            return new File(url.toURI());
        } catch (Exception e) {
            try {
                return new File(url.getPath());
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    private static String noFragment(URL url) {
        final String form = url.toExternalForm();
        final int hash = form.indexOf('#');
        return hash < 0 ? form : form.substring(0, hash);
    }

    /** Whether the JDK builds the resource URL from the path unchanged, so the indexed URL is identical. */
    private static boolean isPlain(String path) {
        // Let URLClassLoader perform its canonical-path checks for directory traversal/absolute paths.
        if (path.startsWith("/") || path.equals(".") || path.equals("..")
                || path.startsWith("./") || path.startsWith("../")
                || path.contains("/./") || path.contains("/../")
                || path.endsWith("/.") || path.endsWith("/..")) return false;
        for (int i = 0; i < path.length(); i++) {
            final char c = path.charAt(i);
            final boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '/' || c == '.' || c == '_' || c == '-' || c == '$';
            if (!plain) {
                return false;
            }
        }
        return true;
    }
}
