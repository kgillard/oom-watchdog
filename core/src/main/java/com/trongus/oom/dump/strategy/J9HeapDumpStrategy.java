package com.trongus.oom.dump.strategy;

import com.trongus.oom.dump.DumpType;
import com.trongus.oom.model.JvmSnapshot;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Heap dump via IBM J9 / Eclipse OpenJ9 {@code com.ibm.jvm.Dump#HeapDump}.
 *
 * <p>Produces a Portable Heap Dump (PHD) file.  Works on: IBM J9 JDK 8+,
 * Eclipse OpenJ9 JDK 8+.  Silently returns {@code null} on any other JVM.
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.4
 * @since 1.7.0
 */
public final class J9HeapDumpStrategy implements DumpStrategy {

    @Override public DumpType type() { return DumpType.HEAP; }
    @Override public String  name() { return "J9-com.ibm.jvm.Dump#HeapDump"; }

    @Override
    public String attempt(JvmSnapshot snapshot, String outputPath) {
        try {
            Class<?>  cls    = Class.forName("com.ibm.jvm.Dump");
            Method    method = cls.getMethod("HeapDump");
            method.invoke(null);
            // J9 controls the exact output path; we return the expected path as a hint
            return new File(outputPath + ".phd").getAbsolutePath();
        } catch (ClassNotFoundException e) {
            return null; // not J9
        } catch (Exception e) {
            System.err.println("[OomWatchdog][Dump] J9Heap error: " + e.getMessage());
            return null;
        }
    }
}
