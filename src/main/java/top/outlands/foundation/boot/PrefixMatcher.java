package top.outlands.foundation.boot;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A small, thread safe set of bare string prefixes
 */
public final class PrefixMatcher {
    
    private static final Comparator<String> SHORTEST_FIRST = Comparator.comparingInt(String::length);
    private static final String[] EMPTY = new String[0];
    private final Object writeLock = new Object();
    /** Immutable snapshot; replaced as a whole on write. */
    private volatile Snapshot snapshot = new Snapshot(EMPTY, EMPTY, null);

    private record Snapshot(String[] prefixes, String[] matching, String first) {
        static Snapshot of(String[] prefixes) {
            // Keep every registered prefix for listing/removal; only the lookup index drops children.
            String[] sorted = prefixes.clone();
            Arrays.sort(sorted);
            int count = 0;
            for (String prefix : sorted) {
                if (count == 0 || !prefix.startsWith(sorted[count - 1])) {
                    sorted[count++] = prefix;
                }
            }
            return new Snapshot(prefixes, Arrays.copyOf(sorted, count), prefixes.length == 0 ? null : prefixes[0]);
        }
    }

    /**
     * @param prefix the prefix to store, must not be {@code null}
     * @return whether the prefix was added, {@code false} if it was already present
     */
    public boolean add(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        synchronized (writeLock) {
            String[] current = snapshot.prefixes;
            for (String existing : current) {
                if (existing.equals(prefix)) {
                    return false;
                }
            }
            String[] next = Arrays.copyOf(current, current.length + 1);
            next[current.length] = prefix;
            Arrays.sort(next, SHORTEST_FIRST);
            snapshot = Snapshot.of(next);
            return true;
        }
    }

    /**
     * Removes the prefix, so it stops matching and is no longer listed by {@link #prefixes()}.
     *
     * @param prefix the prefix to remove, must not be {@code null}
     * @return whether the prefix was present and has been removed
     */
    public boolean remove(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        synchronized (writeLock) {
            String[] current = snapshot.prefixes;
            int index = -1;
            for (int i = 0; i < current.length; i++) {
                if (current[i].equals(prefix)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return false;
            }
            String[] next = new String[current.length - 1];
            System.arraycopy(current, 0, next, 0, index);
            System.arraycopy(current, index + 1, next, index, current.length - index - 1);
            snapshot = Snapshot.of(next);
            return true;
        }
    }

    /**
     * @param name the name to look up
     * @return whether one of the stored prefixes is a prefix of {@code name}
     */
    public boolean matches(String name) {
        Snapshot state = snapshot;
        // Preserve the original cheap first probe for common short prefixes such as "java.".
        String first = state.first;
        return first != null && (name.startsWith(first) || matchesRemaining(state.matching, name));
    }

    private static boolean matchesRemaining(String[] matching, String name) {
        if (matching.length == 1) return false;
        // With overlapping prefixes removed, only the lexicographic predecessor can match.
        int index = Arrays.binarySearch(matching, name);
        if (index >= 0) return true;
        int predecessor = -index - 2;
        return predecessor >= 0 && name.startsWith(matching[predecessor]);
    }

    /**
     * @return number of stored prefixes
     */
    public int size() {
        return snapshot.prefixes.length;
    }

    /**
     * @return every stored prefix, shortest first
     */
    public List<String> prefixes() {
        return Collections.unmodifiableList(Arrays.asList(snapshot.prefixes));
    }
}
