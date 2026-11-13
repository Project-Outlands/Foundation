package top.outlands.foundation.boot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The executor follows RetroFuturaBootstrap's split: the game runs on its own thread while the
 * caller (the process's first thread) services marshalled tasks. The tests rely on the executor
 * being restarted after every game thread exits, which is also what keeps the class usable from
 * several launches in one test JVM.
 */
class MainThreadExecutorTest {

    @Test
    void theGameRunsOffTheFirstThreadAndSubmitRunsOnIt() {
        final AtomicBoolean gameIsMainThread = new AtomicBoolean(true);
        final AtomicReference<Thread> taskThread = new AtomicReference<>();
        final AtomicReference<Integer> result = new AtomicReference<>();

        MainThreadExecutor.start("Foundation-Test-Game", () -> {
            gameIsMainThread.set(MainThreadExecutor.isMainThread());
            result.set(MainThreadExecutor.submit(() -> {
                taskThread.set(Thread.currentThread());
                return 42;
            }));
        });

        assertFalse(gameIsMainThread.get(), "the game must not own the first thread");
        assertEquals(42, result.get());
        assertSame(Thread.currentThread(), taskThread.get(), "submit must execute on the first thread");
        assertFalse(MainThreadExecutor.isMainThread(), "the executor is down once the game thread exited");
    }

    @Test
    void aMarshalFromAWorkerDoesNotNeedTheGameLoopToPump() {
        final AtomicReference<String> result = new AtomicReference<>();

        MainThreadExecutor.start("Foundation-Test-Game", () -> {
            // The game thread blocks on a worker; the worker marshals back to the first thread. With the
            // old "drain in the game loop" design this is a deadlock, here the first thread is independent.
            final Thread worker = new Thread(() -> result.set(MainThreadExecutor.submit(() -> "ok")), "worker");
            worker.start();
            try {
                worker.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        assertEquals("ok", result.get());
    }

    @Test
    void postKeepsTheCallersContextClassLoader() throws IOException {
        final AtomicReference<Thread> taskThread = new AtomicReference<>();
        final AtomicReference<ClassLoader> taskLoader = new AtomicReference<>();

        try (URLClassLoader loader = new URLClassLoader(new URL[0], getClass().getClassLoader())) {
            MainThreadExecutor.start("Foundation-Test-Game", () -> {
                Thread.currentThread().setContextClassLoader(loader);
                MainThreadExecutor.post(() -> {
                    taskThread.set(Thread.currentThread());
                    taskLoader.set(Thread.currentThread().getContextClassLoader());
                });
                // Barrier: the posted task above has run before the game thread lets the loop stop.
                MainThreadExecutor.submit(() -> null);
            });

            assertSame(Thread.currentThread(), taskThread.get());
            assertSame(loader, taskLoader.get(), "a marshalled task must keep its caller's class loader");
        }
    }

    @Test
    void submitFromTheFirstThreadRunsInline() {
        final AtomicBoolean ranInline = new AtomicBoolean();

        MainThreadExecutor.start("Foundation-Test-Game", () -> MainThreadExecutor.submit(() -> {
            MainThreadExecutor.submit(() -> {
                ranInline.set(true);
                return null;
            });
            return null;
        }));

        assertTrue(ranInline.get(), "a nested submit must not queue behind the running loop");
    }

    @Test
    void postFromTheFirstThreadRunsOnTheNextLoopIteration() {
        final AtomicReference<Thread> posted = new AtomicReference<>();

        MainThreadExecutor.start("Foundation-Test-Game", () -> MainThreadExecutor.submit(() -> {
            MainThreadExecutor.post(() -> posted.set(Thread.currentThread()));
            return null;
        }));

        assertSame(Thread.currentThread(), posted.get());
    }

    @Test
    void aFailingTaskDoesNotKillTheLoop() {
        final AtomicBoolean after = new AtomicBoolean();

        MainThreadExecutor.start("Foundation-Test-Game", () -> {
            MainThreadExecutor.post(() -> {
                throw new IllegalStateException("boom");
            });
            MainThreadExecutor.post(() -> after.set(true));
            MainThreadExecutor.submit(() -> null);
        });

        assertTrue(after.get());
    }

    @Test
    void submitPropagatesTaskFailures() {
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        MainThreadExecutor.start("Foundation-Test-Game", () -> {
            try {
                MainThreadExecutor.submit(() -> {
                    throw new IllegalArgumentException("boom");
                });
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        assertInstanceOf(IllegalArgumentException.class, failure.get());
    }

    @Test
    void aStoppedExecutorRejectsNewWork() {
        MainThreadExecutor.start("Foundation-Test-Game", () -> {});

        assertThrows(IllegalStateException.class, () -> MainThreadExecutor.post(() -> {}));
        assertThrows(IllegalStateException.class, () -> MainThreadExecutor.submit(() -> null));
    }
}
