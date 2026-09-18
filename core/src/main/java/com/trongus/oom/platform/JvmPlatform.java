package com.trongus.oom.platform;

import java.lang.management.ManagementFactory;

/**
 * Runtime platform detection utility.
 *
 * <p>All detection is done lazily via class-loading and system-property
 * inspection so that the class compiles with {@code --release 8} yet still
 * uses newer APIs (e.g. {@code ProcessHandle}) when they are available at
 * runtime via reflection.
 *
 * <p>Detection results are cached as static finals evaluated at class-load
 * time to avoid repeated string/reflection overhead in the poll loop.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.8
 * @since 1.0.0
 */
public final class JvmPlatform {

    // ── runtime JDK version ──────────────────────────────────────────────────
    public static final int JDK_VERSION = detectJdkVersion();

    // ── vendor flags ────────────────────────────────────────────────────────
    /** True when running inside a GraalVM Native Image executable. */
    public static final boolean IS_GRAAL_NATIVE  = detectGraalNative();

    /** True when running on GraalVM JVM mode (not native). */
    public static final boolean IS_GRAAL_JVM     = detectGraalJvm();

    /** True when running on IBM J9 or Eclipse OpenJ9. */
    public static final boolean IS_J9            = detectJ9();

    /** True when running on any HotSpot-derived JVM (Oracle, OpenJDK, Azul Zulu/Zing, Amazon Corretto). */
    public static final boolean IS_HOTSPOT       = detectHotSpot();

    // ── current PID ──────────────────────────────────────────────────────────
    /** PID of this JVM process, or -1 if it could not be determined. */
    public static final long PID = detectPid();

    private JvmPlatform() {}

    // -------------------------------------------------------------------------
    // Detection helpers
    // -------------------------------------------------------------------------

    private static int detectJdkVersion() {
        // "java.specification.version" is "1.8" on JDK 8, "9" on JDK 9, "26" on JDK 26
        String spec = System.getProperty("java.specification.version", "1.8");
        try {
            if (spec.startsWith("1.")) {
                return Integer.parseInt(spec.substring(2));
            }
            return Integer.parseInt(spec);
        } catch (NumberFormatException e) {
            return 8; // safe default
        }
    }

    private static boolean detectGraalNative() {
        // org.graalvm.nativeimage.ImageInfo.inImageCode() is the canonical check.
        // We use a system property that GraalVM sets in every native image so we
        // don't need a compile-time dependency on the GraalVM SDK.
        String vmName = System.getProperty("java.vm.name", "");
        String runtime = System.getProperty("org.graalvm.nativeimage.imagecode", "");
        return !runtime.isEmpty()
            || vmName.toLowerCase().contains("substrate")
            || vmName.toLowerCase().contains("native image");
    }

    private static boolean detectGraalJvm() {
        if (IS_GRAAL_NATIVE) return false;
        String vmVendor = System.getProperty("java.vm.vendor", "");
        String vmName   = System.getProperty("java.vm.name", "");
        return vmVendor.toLowerCase().contains("graalvm")
            || vmName.toLowerCase().contains("graalvm");
    }

    private static boolean detectJ9() {
        String vmName = System.getProperty("java.vm.name", "");
        return vmName.contains("J9") || vmName.contains("OpenJ9");
    }

    private static boolean detectHotSpot() {
        if (IS_GRAAL_NATIVE || IS_J9) return false;
        String vmName = System.getProperty("java.vm.name", "");
        return vmName.contains("HotSpot") || vmName.contains("OpenJDK");
    }

    /**
     * Returns the current process PID.
     * <ul>
     *   <li>JDK 9+: uses {@code ProcessHandle.current().pid()} via reflection</li>
     *   <li>JDK 8: parses {@code RuntimeMXBean.getName()} (format: {@code pid@host})</li>
     *   <li>GraalVM Native Image: uses {@code ProcessHandle} (available in Substrate 21+)</li>
     * </ul>
     */
    private static long detectPid() {
        // JDK 9+: ProcessHandle.current().pid() – most reliable
        if (JDK_VERSION >= 9) {
            try {
                Class<?> ph   = Class.forName("java.lang.ProcessHandle");
                Object   curr = ph.getMethod("current").invoke(null);
                return (Long) curr.getClass().getMethod("pid").invoke(curr);
            } catch (Exception ignored) {
                // fall through
            }
        }
        // JDK 8 fallback: parse "pid@hostname" from RuntimeMXBean
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            String pidStr = at > 0 ? name.substring(0, at) : name;
            return Long.parseLong(pidStr);
        } catch (Exception ignored) {
            return -1L;
        }
    }

    /**
     * Returns a human-readable summary of the detected JVM platform for use in log output and alerts.
     *
     * @return a formatted string containing JDK version, vendor, VM name, PID, GraalVM native flag,
     *         and J9 flag
     */
    public static String summary() {
        return String.format(
            "JDK=%d vendor=%s vm=%s pid=%d graalNative=%b j9=%b",
            JDK_VERSION,
            System.getProperty("java.vm.vendor", "?"),
            System.getProperty("java.vm.name", "?"),
            PID,
            IS_GRAAL_NATIVE,
            IS_J9);
    }
}
