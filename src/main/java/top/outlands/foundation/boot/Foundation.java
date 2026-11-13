package top.outlands.foundation.boot;

import net.lenni0451.reflect.JavaBypass;
import net.lenni0451.reflect.Modules;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;

public class Foundation {
    public static Logger LOGGER = LogManager.getLogger("Foundation");

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> Foundation.LOGGER.error(thread, throwable));
        try {
            prepareFirstThread();
        } catch (Throwable t) {
            LOGGER.fatal("Unable to prepare the first thread", t);
            System.exit(1);
        }
        // RetroFuturaBootstrap style: the game runs on a new thread while the process's first thread
        // services SDL/Cocoa work. SDL requires the first thread for its video and event subsystems.
        MainThreadExecutor.start("Foundation-Main", () -> launch(args));
    }

    /**
     * On macOS the process's first thread is the only one allowed to talk to SDL/Cocoa, and it is only
     * the JVM's AppKit main thread when {@code -XstartOnFirstThread} was passed. Headless AWT is
     * initialized here so that later AWT access from other threads cannot wait on a missing Cocoa run
     * loop (the same pattern RetroFuturaBootstrap uses).
     */
    private static void prepareFirstThread() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return;
        }
        if (!Boolean.getBoolean("foundation.skip_first_thread_check") && !startedOnFirstThread()) {
            throw new IllegalStateException(
                    "Foundation must be started with -XstartOnFirstThread on macOS: SDL may only create the "
                            + "window and pump events on the process first thread. Add the flag to the JVM arguments, "
                            + "or set -Dfoundation.skip_first_thread_check=true if the launcher already guarantees it.");
        }
        System.setProperty("java.awt.headless", "true");
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment();
            Toolkit.getDefaultToolkit().getDesktopProperty("awt.mouse.numButtons");
        } catch (Throwable t) {
            LOGGER.warn("Failed to initialize AWT on the first thread", t);
        }
    }

    /**
     * The JDK launcher sets this environment variable when {@code -XstartOnFirstThread} is used (see the
     * macOS {@code java_md_macosx.m}); libGDX uses the same probe.
     */
    private static boolean startedOnFirstThread() {
        return "1".equals(System.getenv("JAVA_STARTED_ON_FIRST_THREAD_" + ProcessHandle.current().pid()));
    }

    private static void launch(String[] args) {
        try {
            breakModuleAndReflection();
            if (Launch.classLoader == null) {
                boolean loadsall = Boolean.parseBoolean(System.getProperty("foundation.loadsall", "false"));
                // The install step, before any game class can be looked up: it makes sure a patched Minecraft
                // exists on disk and, when it does, the overlay jar goes first on the classpath. A launcher never
                // installs anything for us - in the versions that do this, the component's mainClass is what
                // materialises the artifacts, and this is that mainClass.
                final java.util.Optional<java.nio.file.Path> patched = top.outlands.foundation.install.PatchInstaller.prepare();
                final java.net.URL[] sources = top.outlands.foundation.install.Classpath.launchClasspath(patched.orElse(null));
                Launch.classLoader = loadsall
                    ? new LoadsAllClassLoader(sources)
                    : new LaunchClassLoader(sources);
                patched.ifPresent(path -> System.setProperty(top.outlands.foundation.install.PatchInstaller.PATCHED_JAR_PROPERTY, path.toString()));
            }
            Object handler = Class.forName("top.outlands.foundation.LaunchHandler", true, Launch.classLoader).getConstructor().newInstance();
            MethodHandles.lookup()
                .findVirtual(handler.getClass(), "launch", MethodType.methodType(void.class, String[].class))
                .invoke(handler, (Object) args);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void breakModuleAndReflection() throws ClassNotFoundException {
        JavaBypass.clearReflectionFilter();
        Modules.openBootModule();
        Modules.enableNativeAccessToAllUnnamed();
    }
}
