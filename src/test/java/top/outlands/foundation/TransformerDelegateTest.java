package top.outlands.foundation;

import net.minecraft.launchwrapper.IClassTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.outlands.foundation.boot.TransformerHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.*;

class TransformerDelegateTest {
    private List<IClassTransformer> previous;
    private Map<String, PriorityQueue<IExplicitTransformer>> previousExplicit;
    private final TransformerHolder holder = new TransformerHolder();

    @BeforeEach void initialize() {
        previous = TransformerHolder.transformers;
        previousExplicit = TransformerHolder.explicitTransformers;
        TransformerDelegate.fillTransformerHolder(holder);
    }

    @AfterEach void restore() {
        TransformerHolder.transformers = previous;
        TransformerHolder.explicitTransformers = previousExplicit;
    }

    @Test void unregisterByNameRemovesEveryInstanceAndPreservesSortedSurvivors() {
        List<Integer> calls = new ArrayList<>();
        Recording high = new Recording(9, calls);
        Recording low = new Recording(1, calls);
        TransformerDelegate.registerTransformer(high);
        TransformerDelegate.registerTransformer(new Removed());
        TransformerDelegate.registerTransformer(low);
        TransformerDelegate.registerTransformer(new Removed());
        TransformerDelegate.registerTransformer(new Removed());
        assertSame(high, TransformerDelegate.getTransformers().getFirst(), "instance registration still appends");
        TransformerDelegate.unRegisterTransformer(Removed.class.getName());
        assertEquals(List.of(low, high), TransformerDelegate.getTransformers());
        byte[] bytes = {1};
        assertSame(bytes, holder.runTransformersFunction.apply("a", "a", bytes));
        assertEquals(List.of(1, 9), calls);
        TransformerDelegate.unRegisterTransformer(low);
        assertEquals(List.of(high), TransformerDelegate.getTransformers());
    }

    @Test void explicitTransformersConsumeInPriorityOrderOncePerTarget() {
        List<Integer> calls = new ArrayList<>();
        TransformerDelegate.registerExplicitTransformer(explicit(8, calls), "a", "b");
        TransformerDelegate.registerExplicitTransformer(explicit(-1, calls), "a");
        byte[] bytes = {1};
        assertSame(bytes, holder.runExplicitTransformersFunction.apply("missing", bytes));
        assertNull(holder.runExplicitTransformersFunction.apply("missing", null));
        assertSame(bytes, holder.runExplicitTransformersFunction.apply("a", bytes));
        assertEquals(List.of(-1, 8), calls);
        assertFalse(TransformerDelegate.getExplicitTransformers().containsKey("a"));
        holder.runExplicitTransformersFunction.apply("a", bytes);
        holder.runExplicitTransformersFunction.apply("b", bytes);
        assertEquals(List.of(-1, 8, 8), calls);
    }

    @Test void registrationDuringDispatchUsesTheSameLiveQueue() {
        List<Integer> calls = new ArrayList<>();
        TransformerDelegate.registerExplicitTransformer(bytes -> {
            calls.add(0);
            TransformerDelegate.registerExplicitTransformer(explicit(1, calls), "a");
            return bytes;
        }, "a");
        holder.runExplicitTransformersFunction.apply("a", new byte[0]);
        assertEquals(List.of(0, 1), calls);
        assertTrue(TransformerDelegate.getExplicitTransformers().isEmpty());
    }

    @Test void failureKeepsUnconsumedTransformersAvailable() {
        List<Integer> calls = new ArrayList<>();
        TransformerDelegate.registerExplicitTransformer(bytes -> { throw new IllegalStateException("test"); }, "a");
        TransformerDelegate.registerExplicitTransformer(explicit(1, calls), "a");
        assertThrows(IllegalStateException.class, () -> holder.runExplicitTransformersFunction.apply("a", null));
        assertEquals(1, TransformerDelegate.getExplicitTransformers().get("a").size());
        holder.runExplicitTransformersFunction.apply("a", null);
        assertEquals(List.of(1), calls);
    }

    private static IExplicitTransformer explicit(int priority, List<Integer> calls) {
        return new IExplicitTransformer() {
            @Override public byte[] transform(byte[] bytes) { calls.add(priority); return bytes; }
            @Override public int getPriority() { return priority; }
        };
    }

    private record Recording(int priority, List<Integer> calls) implements IClassTransformer {
        @Override public byte[] transform(String name, String transformedName, byte[] bytes) { calls.add(priority); return bytes; }
        @Override public int getPriority() { return priority; }
    }

    private static final class Removed implements IClassTransformer {
        @Override public byte[] transform(String name, String transformedName, byte[] bytes) { fail("removed transformer"); return bytes; }
    }
}
