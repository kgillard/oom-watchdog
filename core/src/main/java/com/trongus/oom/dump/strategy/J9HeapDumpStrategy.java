package com.trongus.oom.dump.strategy;

import com.trongus.oom.dump.DumpType;
import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.model.JvmSnapshot;

import java.io.File;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Heap dump via IBM J9 / Eclipse OpenJ9 {@code com.ibm.jvm.Dump#HeapDump}.
 *
 * <p>Produces a Portable Heap Dump (PHD) file written to the watchdog's
 * configured dump directory.  The {@code HeapDump(String agentOptions)} overload
 * is used when available so J9 writes to the path chosen by the watchdog
 * ({@code "file=<path>"} option).  Falls back to the no-arg {@code HeapDump()}
 * on older J9 builds that do not expose the String overload.
 *
 * <p>Works on: IBM J9 JDK 8+, Eclipse OpenJ9 JDK 8+.
 * Silently returns {@code null} on any other JVM.
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.4
 * @since 1.7.0
 */
public final class J9HeapDumpStrategy implements DumpStrategy {

    private static final Logger LOG = WatchdogLogger.forClass(J9HeapDumpStrategy.class);

    @Override public DumpType type() { return DumpType.HEAP; }
    @Override public String  name() { return "J9-com.ibm.jvm.Dump#HeapDump"; }

    @Override
    public String attempt(JvmSnapshot snapshot, String outputPath) {
        // Replace the .hprof extension added by CompositeDumpService with .phd,
        // which is the native format produced by J9/OpenJ9 HeapDump.
        String phdPath = outputPath.endsWith(".hprof")
                ? outputPath.substring(0, outputPath.length() - 6) + ".phd"
                : outputPath + ".phd";
        try {
            Class<?> cls = Class.forName("com.ibm.jvm.Dump");
            // Prefer the HeapDump(String agentOptions) overload so J9 writes to
            // our chosen path.  The agent option string "file=<path>" is the
            // documented way to control the output location.
            try {
                Method withOpts = cls.getMethod("HeapDump", String.class);
                withOpts.invoke(null, "file=" + phdPath);
            } catch (NoSuchMethodException e) {
                // Older J9 builds only have the no-arg variant — fall back to it.
                cls.getMethod("HeapDump").invoke(null);
            }
            return new File(phdPath).getAbsolutePath();
        } catch (ClassNotFoundException e) {
            return null; // not J9
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            WatchdogLogger.warning(LOG, e, "J9 heap dump failed: {0}", cause.toString());
            return null;
        } catch (Exception e) {
            WatchdogLogger.warning(LOG, e, "J9 heap dump failed: {0}", e.getMessage());
            return null;
        }
    }
}
