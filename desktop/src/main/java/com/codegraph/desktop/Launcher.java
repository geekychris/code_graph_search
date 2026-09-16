package com.codegraph.desktop;

import javafx.application.Application;

/**
 * Trampoline main-class that JavaFX's runtime accepts when the app
 * is packaged as a fat JAR.
 *
 * <p>Rationale: JavaFX 11+ refuses to start when the main-class
 * itself extends {@link Application} unless it's launched with a
 * proper {@code --module-path} pointing at the JavaFX modules. That
 * mode is fine under jpackage, but breaks {@code java -jar
 * fat.jar} dev iteration. The standard workaround is a plain
 * (non-Application) main-class that calls {@link Application#launch}
 * — bypasses the "runtime components missing" check because JavaFX
 * only inspects the immediate main-class, not the launched one.
 */
public class Launcher {
    public static void main(String[] args) {
        DesktopMain.main(args);
    }
}
