package cloud.projectargus.buildconfigfixture;

/**
 * Hand-rolled stand-in for a host-app-generated BuildConfig class. In a real
 * Android app, AGP synthesises this at compile time inside the host app's
 * package. Unit tests need a real class on the classpath so the reflection
 * lookup in {@link com.telus.argus.ArgusConfiguration.Companion#autoDetectedEnvironment}
 * actually resolves something.
 *
 * Plain Java so AGP and Kotlin/JVM agree on the field layout
 * (`public static final String ARGUS_TRACK = "..."`).
 */
public final class BuildConfig {
    public static final String ARGUS_TRACK = "staging";

    private BuildConfig() { }
}
