package com.ibm.oomwatchdog.dump;

import com.ibm.oomwatchdog.model.JvmSnapshot;

import java.util.List;

/**
 * ISP: sole responsibility is triggering one or more diagnostic dumps for the
 * current JVM process and returning the paths that were written.
 *
 * <p>Implementations select the appropriate mechanism for each
 * {@link DumpType} and the current JVM vendor.
 */
public interface HeapDumpService {

    /**
     * Triggers every dump type in {@code types} for the current JVM process.
     *
     * @param snapshot the assessed snapshot that caused the dump request
     *                 (used to derive file names and include process context)
     * @param types    one or more dump types to produce
     * @return list of absolute paths to the files written (never {@code null};
     *         may be empty if no dump could be taken)
     */
    List<String> dump(JvmSnapshot snapshot, List<DumpType> types);
}
