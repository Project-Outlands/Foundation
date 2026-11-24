package top.outlands.foundation.boot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
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
import java.util.zip.ZipFile;

/**
 * Maps a package directory to the classpath entries containing it, so finding a class file probes only
 * the jars that have the package instead of every classpath entry. Answers what
 * {@link java.net.URLClassLoader#findResource(String)} answers for a class file, or {@link #UNKNOWN}.
 */

@SuppressWarnings("deprecation")
public final class ClassPathIndex {

    /** Returned by {@link #lookup(String)} when the caller has to use the regular findResource lookup. */
    public static final Object UNKNOWN = new Object();

    private static final int[] NO_POSITIONS = new int[0];
    private static final URL[] NO_URLS = new URL[0];

    private sealed interface Source permits JarSource, DirSource {
        URL find(String path);

        /** @return the file contents, or null when this source has no such file */
        byte[] read(String path) throws IOException;
    }

    private final List<Source> sources = new ArrayList<>();
    /** Classpath positions of each package directory, ascending. */
    private final Map<String, int[]> packages = new HashMap<>();
    /** URLs already placed, so a Class-Path entry is used once. */
    private final Set<String> placed = new HashSet<>();
    /** First position whose contribution is unknown; MAX_VALUE while the whole order is known. */
    private int barrier = Integer.MAX_VALUE;

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
            addLocked(url);
        }
    }

    /**
     * @return the URL findResource would return, null if it would return null, or {@link #UNKNOWN}
     */
    public synchronized Object lookup(String path) {
        if (barrier == 0 || !isPlain(path)) {
            // the JDK percent-encodes the URL; not worth reproducing for class files
            return UNKNOWN;
        }
        final int slash = path.lastIndexOf('/');
        final int[] positions = packages.getOrDefault(slash < 0 ? "" : path.substring(0, slash + 1), NO_POSITIONS);
        for (int position : positions) {
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

    /**
     * Reads a class file from the entry {@link #lookup(String)} would have found, without building a
     * {@code jar:} URL and without a {@link java.net.JarURLConnection}. Same search order, same answers.
     *
     * @return the bytes, null when no entry has the file, or {@link #UNKNOWN} when the caller has to use
     *         {@code findResource(path)}
     */
    public synchronized Object read(String path) throws IOException {
        if (barrier == 0 || !isPlain(path)) {
            return UNKNOWN;
        }
        final int slash = path.lastIndexOf('/');
        final int[] positions = packages.getOrDefault(slash < 0 ? "" : path.substring(0, slash + 1), NO_POSITIONS);
        for (int position : positions) {
            if (position >= barrier) {
                return UNKNOWN;
            }
            final byte[] bytes = sources.get(position).read(path);
            if (bytes != null) {
                return bytes;
            }
        }
        return barrier == Integer.MAX_VALUE ? null : UNKNOWN;
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
        for (String packagePath : packagesOf(source)) {
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
            jarFile = new JarFile(file, true, ZipFile.OPEN_READ, JarFile.runtimeVersion());
        } catch (IOException | RuntimeException e) {
            return null;
        }
        try {
            return new JarSource(url, new URL("jar", "", -1, url.toExternalForm() + "!/"), jarFile,
                    new ArchiveIndex(jarFile));
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

    private static List<String> packagesOf(Source source) {
        if (source instanceof DirSource dirSource) {
            return dirSource.packages();
        }
        return ((JarSource) source).packages();
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

        @Override
        public byte[] read(String path) throws IOException {
            final File file = new File(dir, path);
            return file.isFile() ? Files.readAllBytes(file.toPath()) : null;
        }

        /** Directories that contain files, which is what a package lookup can ask for. */
        private List<String> packages() {
            final List<String> result = new ArrayList<>();
            collect(dir, "", result, 0);
            return result;
        }

        private static void collect(File current, String path, List<String> result, int depth) {
            if (depth > 64) {
                return; // symbolic link loop
            }
            final File[] children = current.listFiles();
            if (children == null) {
                return;
            }
            boolean hasFiles = false;
            for (File child : children) {
                if (child.isDirectory()) {
                    collect(child, path + child.getName() + '/', result, depth + 1);
                } else {
                    hasFiles = true;
                }
            }
            if (hasFiles) {
                result.add(path);
            }
        }
    }

    /**
     * @param source  Jar URL, which Class-Path entries are resolved against. 
     * @param base  {@code jar:...!/} URL, which resources are resolved against.  
     * @param index  the entries of {@code file}, which is what a lookup and a read consult  */
    private record JarSource(URL source, URL base, JarFile file, ArchiveIndex index) implements Source {

        @Override
        public URL find(String path) {
            final JarEntry entry = index.find(path);
            if (entry == null) {
                return null;
            }
            // a multi-release jar serves the copy the runtime version selects
            try {
                return new URL(base, ArchiveIndex.realName(entry));
            } catch (MalformedURLException e) {
                return null;
            }
        }

        @Override
        public byte[] read(String path) throws IOException {
            return index.read(index.find(path));
        }

        private List<String> packages() {
            return index.packages();
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
