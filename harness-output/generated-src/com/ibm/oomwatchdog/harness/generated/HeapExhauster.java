package com.ibm.oomwatchdog.harness.generated;

import java.util.ArrayList;
import java.util.List;

/**
 * Dynamically generated class that exhausts the JVM heap.
 * Generated at runtime by DynamicOomClassGenerator.
 */
public final class HeapExhauster {

    // Static reference keeps all chunks alive across GC cycles
    private static final List<byte[]> SINK = new ArrayList<>();

    private HeapExhauster() {}

    /**
     * Phase 1: slow leak – accumulate 2 MB chunks,
     *          calling GC periodically so the watchdog sees a trend.
     * Phase 2: burst – allocate until near-full.
     * Phase 3: OOM   – one final oversized allocation.
     */
    public static void exhaust() {
        System.out.println("[HeapExhauster] Phase 1: slow leak");
        for (int i = 0; i < 12; i++) {
            SINK.add(new byte[2097152L > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : (int)2097152L]);
            if (i % 3 == 0) System.gc();
            sleep(300);
        }
        System.out.println("[HeapExhauster] Phase 2: burst");
        Runtime rt = Runtime.getRuntime();
        while (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory()) > 4194304L) {
            SINK.add(new byte[(int)Math.min(2097152L * 3, Integer.MAX_VALUE)]);
            sleep(100);
        }
        System.out.println("[HeapExhauster] Phase 3: OOM");
        byte[] finalBlow = new byte[Integer.MAX_VALUE];
        SINK.add(finalBlow);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
