package top.outlands.foundation.boot;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs tasks on the process's first thread, which is the only thread allowed to talk to SDL and Cocoa
 * on macOS.
 *
 * <p>Following RetroFuturaBootstrap's {@code MainStartOnFirstThread}, {@link Foundation#main} does not
 * run the game on the first thread. It starts the game on a separate thread and runs the event loop
 * below on the first thread instead. A task marshalled by the game or a mod thread therefore runs
 * independently of whether the game loop is currently pumping frames, which removes the
 * "worker waits for the game loop while the game loop waits for the worker" deadlock.</p>
 */
public final class MainThreadExecutor {

    /** Wakes the loop once the game thread has finished. */
    private static final Runnable WAKEUP = () -> {};

    private static final LinkedBlockingQueue<Runnable> TASKS = new LinkedBlockingQueue<>();
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private static volatile Thread mainThread;
    private static volatile boolean running;

    private MainThreadExecutor() {
    }

    /**
     * Starts {@code game} on a new thread and runs the main-thread event loop on the calling thread
     * until the game thread exits. The calling thread becomes {@link #isMainThread()}.
     *
     * @param gameThreadName name of the thread the game itself runs on
     * @param game           the game entry point, usually the rest of {@link Foundation#main}
     */
    public static void start(String gameThreadName, Runnable game) {
        if (!STARTED.compareAndSet(false, true)) {
            throw new IllegalStateException("The main thread executor has already been started");
        }
        mainThread = Thread.currentThread();
        running = true;

        final Thread gameThread = new Thread(() -> {
            try {
                game.run();
            } catch (Throwable t) {
                Foundation.LOGGER.error("The game thread terminated with an exception", t);
            } finally {
                running = false;
                TASKS.offer(WAKEUP);
            }
        }, gameThreadName);
        gameThread.start();

        final Thread loopThread = Thread.currentThread();
        final String previousName = loopThread.getName();
        loopThread.setName("Foundation-EventLoop");
        try {
            eventLoop();
            join(gameThread);
        } finally {
            running = false;
            loopThread.setName(previousName);
            TASKS.clear();
            mainThread = null;
            STARTED.set(false);
        }
    }

    /**
     * @return whether the current thread is the process's first thread, the one that owns SDL/Cocoa
     */
    public static boolean isMainThread() {
        return Thread.currentThread() == mainThread;
    }

    /**
     * Queues a task for the first thread without blocking. The task runs on the loop's next
     * iteration, also when the caller already is the first thread.
     */
    public static void post(Runnable task) {
        enqueue(task);
    }

    /**
     * Runs a task on the first thread and waits for its result. If the caller already is the first
     * thread the task runs inline.
     */
    public static <T> T submit(Callable<T> task) {
        if (isMainThread()) {
            return call(task);
        }
        final CompletableFuture<T> future = new CompletableFuture<>();
        enqueue(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the main thread", e);
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    /**
     * Queues a task for the first thread. Tasks keep the context class loader of the thread that
     * submitted them, because most marshalled work is mod code that loads classes of its own.
     */
    private static void enqueue(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (!STARTED.get() || !running) {
            throw new IllegalStateException("The main thread executor is not running");
        }
        TASKS.add(bindContextClassLoader(task));
    }

    private static void eventLoop() {
        while (true) {
            final Runnable task;
            try {
                task = TASKS.take();
            } catch (InterruptedException e) {
                // The first thread is not ours to interrupt; only the game thread ending stops the loop.
                continue;
            }
            if (task == WAKEUP) {
                if (!running) {
                    return;
                }
                continue;
            }
            runSafely(task);
        }
    }

    private static void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            Foundation.LOGGER.error("Task on the main thread executor failed", t);
        }
    }

    private static void join(Thread thread) {
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static Runnable bindContextClassLoader(Runnable task) {
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return () -> {
            final Thread thread = Thread.currentThread();
            final ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(contextClassLoader);
            try {
                task.run();
            } finally {
                thread.setContextClassLoader(previous);
            }
        };
    }

    private static <T> T call(Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
