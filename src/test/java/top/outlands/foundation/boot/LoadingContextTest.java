package top.outlands.foundation.boot;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class LoadingContextTest {
    @Test
    void nestedScopesRestoreIdentityAndReleaseCompletedContexts() throws Exception {
        assertNull(LoadingContext.current());
        LoadingContext.pop();
        URL source = new URL("file:/outer.jar");
        Manifest manifest = new Manifest();
        for (int i = 0; i < 100; i++) {
            LoadingContext.push(getClass().getPackage(), manifest, source);
            LoadingContext outer = LoadingContext.current();
            try {
                assertSame(manifest, outer.manifest());
                assertSame(source, outer.codeSourceUrl());
                LoadingContext.push(null, null, null);
                try {
                    assertNotSame(outer, LoadingContext.current());
                } finally {
                    LoadingContext.pop();
                }
                assertSame(outer, LoadingContext.current());
                try (var worker = Executors.newSingleThreadExecutor()) {
                    worker.submit(() -> {
                        assertNull(LoadingContext.current());
                        LoadingContext.push(null, null, null);
                        LoadingContext.pop();
                        assertNull(LoadingContext.current());
                    }).get(5, TimeUnit.SECONDS);
                }
            } finally {
                LoadingContext.pop();
            }
            assertNull(LoadingContext.current());
        }
    }

    @Test
    void failedNestedClassLoadsRestoreTheOuterContext() throws Exception {
        TransformerHolder holder = ActualClassLoader.getTransformerHolder();
        var previous = holder.runTransformersFunction;
        try (ActualClassLoader loader = new ActualClassLoader(new URL[0], getClass().getClassLoader())) {
            holder.runTransformersFunction = (name, transformed, bytes) -> {
                assertNotNull(LoadingContext.current());
                if (name.equals("context.Outer")) {
                    LoadingContext outer = LoadingContext.current();
                    assertThrows(ClassNotFoundException.class, () -> loader.findClass("context.Inner"));
                    assertSame(outer, LoadingContext.current());
                }
                throw new IllegalStateException("test failure");
            };
            assertThrows(ClassNotFoundException.class, () -> loader.findClass("context.Outer"));
            assertNull(LoadingContext.current());
        } finally {
            holder.runTransformersFunction = previous;
        }
    }
}
