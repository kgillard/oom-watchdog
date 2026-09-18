package com.trongus.oom.dump.strategy;

import com.trongus.oom.dump.DumpType;
import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.model.JvmSnapshot;

import javax.management.MBeanServer;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Heap dump via HotSpot {@code HotSpotDiagnosticMXBean#dumpHeap}.
 *
 * <p>Works on: HotSpot, OpenJDK, GraalVM JVM mode (JDK 8+).
 * Not available in: GraalVM Native Image, IBM J9/OpenJ9.
 * Reflection is used so the code compiles without a {@code com.sun.management}
 * import, keeping it compatible with all JDK versions including 26+.
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.6
 * @since 1.7.0
 */
public final class HotSpotHeapDumpStrategy implements DumpStrategy {

    private static final Logger LOG = WatchdogLogger.forClass(HotSpotHeapDumpStrategy.class);
    private static final String MXBEAN_NAME = "com.sun.management:type=HotSpotDiagnostic";

    @Override public DumpType type() { return DumpType.HEAP; }
    @Override public String  name() { return "HotSpot-HotSpotDiagnosticMXBean"; }

    @Override
    public String attempt(JvmSnapshot snapshot, String outputPath) {
        try {
            MBeanServer server  = ManagementFactory.getPlatformMBeanServer();
            Class<?>    cls     = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
            Object      bean    = ManagementFactory.newPlatformMXBeanProxy(server, MXBEAN_NAME, cls);
            Method      method  = cls.getMethod("dumpHeap", String.class, boolean.class);
            method.invoke(bean, outputPath, true); // true = live objects only
            return new File(outputPath).getAbsolutePath();
        } catch (ClassNotFoundException | UnsupportedOperationException e) {
            return null; // not available on this JVM
        } catch (Exception e) {
            WatchdogLogger.warning(LOG, e, "HotSpot heap dump failed: {0}", e.getMessage());
            return null;
        }
    }
}
