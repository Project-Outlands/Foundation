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
        // The process main thread becomes the client thread; anchor it for the SDL marshaling executor.
        MainThreadExecutor.init();
        // macOS: initialize AWT on the main thread so the JVM attaches a Cocoa run loop to it,
        // which SDL requires for the video/event subsystem (same pattern as RetroFuturaBootstrap).
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            System.setProperty("java.awt.headless", "true");
            try {
                GraphicsEnvironment.getLocalGraphicsEnvironment();
                Toolkit.getDefaultToolkit().getDesktopProperty("awt.mouse.numButtons");
            } catch (Throwable t) {
                LOGGER.warn("Failed to initialize AWT on the main thread", t);
            }
        }
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
