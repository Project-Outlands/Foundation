package top.outlands.foundation.boot;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The entries of one archive: a name lookup, a prefix scan, and the file contents read straight from the
 * archive instead of through a {@code jar:} URL and {@link java.net.JarURLConnection}.
 *
 * <p>The archive already has a good name lookup — a hash map behind {@link JarFile#getEntry(String)} —
 * so this class does not try to beat it: it keeps the package table the caller used to build by walking
 * the entries a second time, and adds the one thing {@code JarFile} does not offer, reading a class file
 * without a URL connection.
 *
 * <p>A sorted name blob was measured here and dropped: a binary search over bytes costs 0.24 µs against
 * the 0.06 µs of the hash lookup, and building it costs 0.35 ms per archive, which is 50% of the whole
 * class path index. Prefix scans enumerate the entries instead, which is what a scan does anyway.
 *
 * <p>The archive is the caller's to close.
 */
public final class ArchiveIndex {

    /** The archive the entries are read from. */
    private final JarFile archive;
    /** Package directories of every entry, which is what a package lookup can ask for. */
    private final List<String> packages;

    public ArchiveIndex(JarFile archive) {
        this.archive = archive;
        final Set<String> packageNames = new HashSet<>();
        for (final Enumeration<JarEntry> all = archive.entries(); all.hasMoreElements(); ) {
            final JarEntry entry = all.nextElement();
            final String name = entry.getName();
            if (name.endsWith("/")) {
                continue; // a directory is not something a lookup can find
            }
            packageNames.add(packageOf(name));
            // a versioned copy is asked for under its unversioned name
            final String unversioned = unversion(name);
            if (unversioned != name) {
                packageNames.add(packageOf(unversioned));
            }
        }
        this.packages = List.copyOf(packageNames);
    }

    /** @return the package directory of every entry */
    public List<String> packages() {
        return packages;
    }

    /**
     * @return the entry this archive serves for {@code name}, which is {@link JarFile#getEntry(String)} so
     *         a multi-release jar answers with the versioned copy, or null when it has no such file
     */
    public JarEntry find(String name) {
        return archive.getJarEntry(name);
    }

    /** @return the name the archive serves for {@code entry}: the versioned copy, or the name itself */
    public static String realName(JarEntry entry) {
        final String real = entry.getRealName();
        return real == null ? entry.getName() : real;
    }

    /**
     * @return the file contents, read from the archive directly rather than through a {@code jar:} URL
     */
    public byte[] read(JarEntry entry) throws IOException {
        if (entry == null) {
            return null;
        }
        try (InputStream stream = archive.getInputStream(entry)) {
            // the central directory knows the uncompressed size, so the array can be sized once instead
            // of grown (and copied) by readAllBytes
            final long size = entry.getSize();
            if (size < 0 || size > Integer.MAX_VALUE - 8) {
                return stream.readAllBytes();
            }
            final byte[] bytes = new byte[(int) size];
            int filled = 0;
            while (filled < bytes.length) {
                final int read = stream.read(bytes, filled, bytes.length - filled);
                if (read < 0) {
                    break;
                }
                filled += read;
            }
            return filled == bytes.length ? bytes : Arrays.copyOf(bytes, filled);
        }
    }

    /**
     * Calls {@code sink} with every entry whose name starts with {@code prefix}.
     *
     * @return how many entries matched
     */
    public int forEachWithPrefix(String prefix, Consumer<JarEntry> sink) {
        // enumerating yields the stored names; a multi-release archive serves the versioned copy under
        // the plain name, and only getEntry applies that mapping
        final boolean multiRelease = archive.isMultiRelease();
        int found = 0;
        for (final Enumeration<JarEntry> all = archive.entries(); all.hasMoreElements(); ) {
            final JarEntry entry = all.nextElement();
            if (entry.getName().startsWith(prefix)) {
                sink.accept(multiRelease ? archive.getJarEntry(entry.getName()) : entry);
                found++;
            }
        }
        return found;
    }

    /** @return the names the archive serves, in no particular order */
    public List<String> names() {
        final List<String> names = new ArrayList<>();
        for (final Enumeration<JarEntry> all = archive.entries(); all.hasMoreElements(); ) {
            final String name = all.nextElement().getName();
            if (!name.endsWith("/")) {
                names.add(name);
            }
        }
        return names;
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
