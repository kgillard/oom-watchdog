package com.trongus.oom.examples;

import com.trongus.oom.alert.ConsoleAlertChannel;
import com.trongus.oom.alert.FileLogAlertChannel;
import com.trongus.oom.collector.MxBeanDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.diagnosis.OomCause;
import com.trongus.oom.diagnosis.OomCauseAnalyser;
import com.trongus.oom.diagnosis.OomCauseCategory;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.i18n.Messages;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.ThresholdRiskAssessor;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;

/**
 * Example 11 — OOM Cause Analysis and Internationalisation (i18n).
 *
 * <h2>What this example shows</h2>
 * <p>Demonstrates the two major features introduced in v1.3.0 and polished in v1.4.0:
 *
 * <ol>
 *   <li><strong>Root-cause analysis:</strong> {@link OomCauseAnalyser} inspects three JVM
 *       signals (heap utilisation ratio, GC overhead fraction, post-GC heap growth rate) and
 *       produces an {@link OomCause} with a {@link OomCauseCategory} enum value and a
 *       plain-language explanation.  The explanation is automatically embedded in every alert
 *       message and LEEF event — no extra wiring required when the watchdog is used normally.
 *       This example shows how to invoke the analyser standalone for testing or custom logic.</li>
 *
 *   <li><strong>Internationalisation:</strong> {@link Messages} wraps a UTF-8 {@link java.util.ResourceBundle}
 *       and serves locale-aware strings for every alert section heading, label, and cause
 *       explanation.  Nine locales are shipped: {@code en} (default), {@code de}, {@code es},
 *       {@code fr}, {@code ja}, {@code ko}, {@code pt_BR}, {@code zh_CN}, {@code zh_TW}.
 *       The locale is set once on {@link WatchdogConfig} and propagates automatically to
 *       all alert text.</li>
 * </ol>
 *
 * <h2>Running with a non-default locale</h2>
 * <pre>{@code
 *   java -cp oom-watchdog.jar com.trongus.oom.examples.Example11CauseAnalysisAndI18n ja
 * }</pre>
 *
 * <p>Pass any supported locale tag as the first argument: {@code de}, {@code es},
 * {@code fr}, {@code ja}, {@code ko}, {@code pt_BR}, {@code zh_CN}, {@code zh_TW}.
 * Omit the argument to default to {@code en}.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.5.0
 * @since 1.3.0
 * @see OomCauseAnalyser
 * @see OomCause
 * @see OomCauseCategory
 * @see Messages
 * @see WatchdogConfig
 */
public final class Example11CauseAnalysisAndI18n {

    /** Utility class — construction is not permitted. */
    private Example11CauseAnalysisAndI18n() {}

    /**
     * Entry point for the OOM cause analysis and i18n example.
     *
     * @param args optional first argument: locale tag (e.g. {@code ja}, {@code de});
     *             defaults to {@code en} if absent
     * @throws InterruptedException if the main thread is interrupted while sleeping
     */
    public static void main(String[] args) throws InterruptedException {

        // ── Step 1: Resolve locale from CLI arg ────────────────────────────────
        //
        // Accept a BCP 47 language tag or simple language code as the first argument.
        // Supported: en, de, es, fr, ja, ko, pt_BR (or pt-BR), zh_CN (or zh-CN), zh_TW.
        Locale locale = parseLocale(args.length > 0 ? args[0] : "en");
        System.out.println("[Example11] Using locale: " + locale);

        // ── Step 2: Demo — OomCauseAnalyser standalone ─────────────────────────
        //
        // OomCauseAnalyser is stateless and thread-safe.  Create one instance per
        // (threshold, locale) combination and reuse it freely.
        //
        // In normal watchdog operation, OomCauseAnalyser is wired internally by
        // ThresholdRiskAssessor — you never need to call it directly.  This section
        // shows how to use it standalone for testing or custom assessors.
        System.out.println("\n=== OomCauseAnalyser standalone demo ===");

        Messages messages = new Messages(locale);
        OomCauseAnalyser analyser = new OomCauseAnalyser(0.90, 0.50, messages);

        demonstrateCause(analyser, "Healthy JVM",
                0.50, 0.10, 0.0);                    // all fine
        demonstrateCause(analyser, "Memory leak",
                0.72, 0.22, 50.0 / (3_600_000.0));   // +50 MB/hour slope
        demonstrateCause(analyser, "GC overhead",
                0.65, 0.60, 0.0);                    // GC time > 50 %
        demonstrateCause(analyser, "Heap exhaustion",
                0.93, 0.30, 0.0);                    // heap > 90 % critical
        demonstrateCause(analyser, "Runaway GC + high heap",
                0.94, 0.72, 20.0 / (3_600_000.0));  // worst-case: both signals

        // ── Step 3: Build a WatchdogConfig with the chosen locale ──────────────
        //
        // .locale(Locale) stores the locale on the config.  ThresholdRiskAssessor
        // reads it to construct a Messages instance for all alert text.
        System.out.println("\n=== Starting watchdog with locale " + locale + " ===");

        WatchdogConfig config = WatchdogConfig.defaults()
                .warningHeapThreshold(0.80)
                .criticalHeapThreshold(0.90)
                .gcOverheadThreshold(0.50)
                .pollIntervalMs(3_000L)
                .leakDetectionWindowSize(5)
                .heapDumpDirectory("./dumps")
                .dumpTypes(EnumSet.of(DumpType.THREAD))  // light: thread dump only
                .locale(locale)
                .build();

        // ── Step 4: Wire and start the watchdog ────────────────────────────────
        OomWatchdog watchdog = new OomWatchdog(
                config,
                new MxBeanDiagnosticsCollector(config),
                new ThresholdRiskAssessor(config),
                Arrays.asList(
                        new ConsoleAlertChannel(),
                        new FileLogAlertChannel("./oom-example11.log")
                ),
                new CompositeDumpService(config)
        );

        Runtime.getRuntime().addShutdownHook(
                new Thread(watchdog::stop, "oom-watchdog-shutdown"));

        watchdog.start();

        System.out.println("[Example11] Watchdog running with locale '" + locale + "'.");
        System.out.println("[Example11] Alert section headings and cause explanations");
        System.out.println("[Example11] will appear in that language when thresholds are crossed.");
        System.out.println("[Example11] Sleeping 12 s (3 poll cycles), then stopping.");
        System.out.println("[Example11] Run with -Xmx32m to trigger alerts quickly.");

        Thread.sleep(12_000L);
        watchdog.stop();
        System.out.println("[Example11] Done.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Calls {@link OomCauseAnalyser#analyse} with the supplied signals and prints
     * a formatted result row for the standalone demo section.
     *
     * @param analyser           the configured analyser instance
     * @param scenario           human-readable scenario label for the printout
     * @param heapRatio          heap used / max (0–1)
     * @param gcOverheadRatio    GC time / uptime (0–1)
     * @param growthRatePerMs    post-GC growth bytes/ms (use 0 for no leak signal)
     */
    private static void demonstrateCause(OomCauseAnalyser analyser,
                                         String scenario,
                                         double heapRatio,
                                         double gcOverheadRatio,
                                         double growthRatePerMs) {
        OomCause cause = analyser.analyse(heapRatio, gcOverheadRatio, growthRatePerMs);
        System.out.printf("%-35s  category=%-35s%n", scenario + ":", cause.getCategory());
        System.out.printf("%-35s  explanation=%s%n%n", "", cause.getExplanation());
    }

    /**
     * Resolves a {@link Locale} from a string argument, supporting both underscore
     * ({@code zh_CN}) and hyphen ({@code zh-CN}) forms as well as simple language
     * codes ({@code ja}, {@code de}).
     *
     * <p>Unrecognised tags fall back to {@link Locale#ENGLISH}.
     *
     * @param tag the locale tag to parse; must not be {@code null}
     * @return the corresponding {@link Locale}; never {@code null}
     */
    private static Locale parseLocale(String tag) {
        // Normalise: replace hyphens with underscores for ResourceBundle compatibility
        String normalised = tag.replace('-', '_');
        switch (normalised) {
            case "de":    return Locale.GERMAN;
            case "es":    return new Locale("es");
            case "fr":    return Locale.FRENCH;
            case "ja":    return Locale.JAPANESE;
            case "ko":    return Locale.KOREAN;
            case "pt_BR": return new Locale("pt", "BR");
            case "zh_CN": return Locale.SIMPLIFIED_CHINESE;
            case "zh_TW": return Locale.TRADITIONAL_CHINESE;
            default:      return Locale.ENGLISH;
        }
    }
}
