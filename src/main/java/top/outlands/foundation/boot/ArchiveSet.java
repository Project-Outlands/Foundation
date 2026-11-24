package top.outlands.foundation.boot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/**
 * The archives a process has opened, so that the class loader, discovery and a cache key all talk about the same
 * {@link JarFile} instead of opening the same file three times.
 *
 * <p>An open {@code JarFile} is not free: the JDK keeps the central directory and an entry table alive for as long as
 * the handle is, which measured at 131 bytes per entry — 38 MB for 300 mod jars. The class loader already keeps one
 * open per class path entry for the life of the process, so the point of this set is not to open more of them but to
 * stop anyone else from opening a second copy.
 *
 * <p>Nothing here is closed: the handles live as long as the process does, and an archive whose file changed under it
 * cannot be seen through them, so a mod update needs a restart either way.
 */
public final class ArchiveSet {

    /** The set the class loader opens its class path entries into. */
    private static final ArchiveSet CLASS_PATH = new ArchiveSet();

    /** @return the set the class loader uses, so a caller can ask it for the archives already there */
    public static ArchiveSet classPath() {
        return CLASS_PATH;
    }

    private final Map<Path, ArchiveIndex> archives = new ConcurrentHashMap<>();

    public ArchiveSet() {
    }

    /**
     * @return the index of {@code jar}, opening it the first time and handing back the same instance afterwards
     */
    public ArchiveIndex open(Path jar) throws IOException {
        final Path key = normalise(jar);
        final ArchiveIndex known = archives.get(key);
        if (known != null) {
            return known;
        }
        synchronized (this) {
            final ArchiveIndex again = archives.get(key);
            if (again != null) {
                return again;
            }
            // same construction the class path index used before this set existed, so verification stays on
            final JarFile file = new JarFile(key.toFile(), true, ZipFile.OPEN_READ, JarFile.runtimeVersion());
            final ArchiveIndex created = new ArchiveIndex(key, file);
            archives.put(key, created);
            return created;
        }
    }

    /** @return the index of {@code jar} when it is already open, null otherwise */
    public ArchiveIndex of(Path jar) {
        return archives.get(normalise(jar));
    }

    /** @return every archive in the set, in no particular order */
    public List<ArchiveIndex> all() {
        return new ArrayList<>(archives.values());
    }

    public int size() {
        return archives.size();
    }

    /** Closes nothing; drops the bookkeeping, which only a test that wants a clean set needs. */
    public void clear() {
        archives.clear();
    }

    private static Path normalise(Path jar) {
        return jar.toAbsolutePath().normalize();
    }
}
