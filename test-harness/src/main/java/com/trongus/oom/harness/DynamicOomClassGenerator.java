package com.trongus.oom.harness;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Generates, compiles, and loads dynamic Java bytecode at runtime to induce heap exhaustion.
 *
 * <h2>Design Rationale</h2>
 * <p>Testing out-of-memory error recovery mechanisms requires realistic memory leak generation
 * that cannot be eliminated by standard static compiler optimisations. {@code DynamicOomClassGenerator}
 * synthesizes a source file for a class ({@code HeapExhauster}) at runtime, invokes the platform
 * Java compiler ({@link JavaCompiler} via {@link ToolProvider}), loads the resulting bytecode through
 * an isolated {@link URLClassLoader}, and triggers its execution via reflection.
 *
 * <h3>Dynamic Heap Exhauster Lifecycle</h3>
 * <ol>
 *   <li><b>Phase 1 (Slow Leak):</b> Allocates 2&nbsp;MB byte arrays into a static collection across multiple
 *       iterations, periodically invoking {@link System#gc()} so the watchdog's trend assessor observes
 *       a monotonically increasing post-GC memory baseline.</li>
 *   <li><b>Phase 2 (Burst):</b> Rapidly appends memory chunks until available free heap drops below
 *       twice the burst increment.</li>
 *   <li><b>Phase 3 (Terminal OOM):</b> Allocates a single oversized array ({@link Integer#MAX_VALUE} bytes)
 *       to guarantee an immediate {@link OutOfMemoryError}.</li>
 * </ol>
 *
 * <h3>Fallback Handling</h3>
 * <p>In execution environments where the Java compiler is absent (e.g., JRE-only distributions or
 * GraalVM native image execution), {@link ToolProvider#getSystemJavaCompiler()} returns {@code null}.
 * In such cases, this generator gracefully falls back to the pre-compiled {@link BuiltInHeapExhauster}.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.0
 * @since 1.0.0
 * @see com.trongus.oom.harness.BuiltInHeapExhauster
 * @see com.trongus.oom.harness.TestHarnessMain
 * @see javax.tools.JavaCompiler
 */
public final class DynamicOomClassGenerator {

    /** Size in megabytes of each memory chunk allocated by the generated class during the leak phase. */
    private static final int CHUNK_MB = 2;

    /** Size in bytes of each memory chunk allocated during the leak phase (2 MB). */
    private static final long CHUNK_BYTES = CHUNK_MB * 1024L * 1024L;

    /** Fully qualified class name of the dynamically generated class. */
    private final String className;

    /** Directory path where generated {@code .java} source files are written. */
    private final File sourceDir;

    /** Directory path where compiled {@code .class} bytecode files are emitted. */
    private final File classDir;

    /** Flag tracking whether runtime compilation completed successfully. */
    private boolean compiledSuccessfully = false;

    /**
     * Constructs a new {@code DynamicOomClassGenerator}.
     *
     * @param className fully qualified class name for the generated exhauster class
     * @param sourceDir directory in which generated source files will be stored
     * @param classDir  directory in which compiled class bytecode will be stored
     */
    public DynamicOomClassGenerator(String className, File sourceDir, File classDir) {
        this.className = className;
        this.sourceDir = sourceDir;
        this.classDir  = classDir;
    }

    /**
     * Generates the Java source code and attempts compilation using the system Java compiler.
     * <p>If {@link ToolProvider#getSystemJavaCompiler()} returns {@code null} or if compilation fails,
     * the generator logs a warning and marks itself to fallback to {@link BuiltInHeapExhauster}.
     *
     * @throws Exception if an I/O error occurs while writing source files or managing file managers
     */
    public void generateAndCompile() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        // Check if runtime Java compiler is available on the host JVM
        if (compiler == null) {
            System.out.println("[DynamicGen] javax.tools.JavaCompiler not available "
                    + "(JRE-only or Native Image). Will use built-in exhauster.");
            return;
        }

        // Write Java source code to disk
        File sourceFile = writeSource();
        System.out.println("[DynamicGen] Source written: " + sourceFile.getAbsolutePath());

        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(
                diags, Locale.US, StandardCharsets.UTF_8);

        Iterable<? extends JavaFileObject> units =
                fm.getJavaFileObjects(sourceFile);

        // Configure compilation task targeting Java 8 bytecode compatibility
        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, diags,
                Arrays.asList("-d", classDir.getAbsolutePath(),
                              "-source", "8", "-target", "8"),
                null, units);

        // Execute compilation task
        if (!task.call()) {
            System.err.println("[DynamicGen] Compilation FAILED:");
            for (javax.tools.Diagnostic<?> d : diags.getDiagnostics()) {
                System.err.println("  " + d);
            }
            System.err.println("[DynamicGen] Falling back to built-in exhauster.");
            fm.close();
            return;
        }
        fm.close();
        compiledSuccessfully = true;
        System.out.println("[DynamicGen] Compiled successfully → " + classDir.getAbsolutePath());
    }

    /**
     * Executes the heap exhaustion routine using either dynamically compiled bytecode
     * or the built-in fallback exhauster.
     * <p>This method intentionally drives memory to exhaustion and throws {@link OutOfMemoryError}.
     *
     * @throws Exception if reflection invocation fails or class loading encounters an error
     */
    public void execute() throws Exception {
        if (compiledSuccessfully) {
            // Execute via dynamically loaded class loader
            executeCompiled();
        } else {
            // Fall back to pre-compiled static exhauster
            System.out.println("[DynamicGen] Running built-in HeapExhauster (fallback).");
            BuiltInHeapExhauster.exhaust();
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Loads the compiled bytecode using a dedicated {@link URLClassLoader} and invokes
     * the static {@code exhaust()} method via reflection.
     *
     * @throws Exception if class loading or reflective invocation fails
     */
    private void executeCompiled() throws Exception {
        URLClassLoader loader = new URLClassLoader(
                new URL[]{ classDir.toURI().toURL() },
                Thread.currentThread().getContextClassLoader());
        try {
            Class<?> cls    = loader.loadClass(className);
            Method   method = cls.getMethod("exhaust");
            System.out.println("[DynamicGen] Invoking " + className + ".exhaust() ...");
            method.invoke(null);
        } finally {
            try {
                loader.close();
            } catch (Exception ignored) {
                // Suppress class loader close errors
            }
        }
    }

    /**
     * Synthesizes and writes the Java source file for the dynamic heap exhauster.
     *
     * @return the {@link File} handle pointing to the generated source file
     * @throws Exception if directory creation or file writing fails
     */
    private File writeSource() throws Exception {
        // Convert "com.trongus.oom.harness.generated.HeapExhauster"
        // → "com/trongus/oom/harness/generated/"
        int lastDot = className.lastIndexOf('.');
        String pkg  = className.substring(0, lastDot);
        String simpleName = className.substring(lastDot + 1);

        File pkgDir = new File(sourceDir, pkg.replace('.', File.separatorChar));
        pkgDir.mkdirs();
        File sourceFile = new File(pkgDir, simpleName + ".java");

        // Emit source code lines
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(sourceFile), StandardCharsets.UTF_8))) {
            pw.println("package " + pkg + ";");
            pw.println();
            pw.println("import java.util.ArrayList;");
            pw.println("import java.util.List;");
            pw.println();
            pw.println("/**");
            pw.println(" * Dynamically generated class that exhausts the JVM heap.");
            pw.println(" * Generated at runtime by DynamicOomClassGenerator.");
            pw.println(" */");
            pw.println("public final class " + simpleName + " {");
            pw.println();
            pw.println("    // Static reference keeps all chunks alive across GC cycles");
            pw.println("    private static final List<byte[]> SINK = new ArrayList<>();");
            pw.println();
            pw.println("    private " + simpleName + "() {}");
            pw.println();
            pw.println("    /**");
            pw.println("     * Phase 1: slow leak – accumulate " + CHUNK_MB + " MB chunks,");
            pw.println("     *          calling GC periodically so the watchdog sees a trend.");
            pw.println("     * Phase 2: burst – allocate until near-full.");
            pw.println("     * Phase 3: OOM   – one final oversized allocation.");
            pw.println("     */");
            pw.println("    public static void exhaust() {");
            pw.println("        System.out.println(\"[HeapExhauster] Phase 1: slow leak\");");
            pw.println("        for (int i = 0; i < 12; i++) {");
            pw.println("            SINK.add(new byte[" + CHUNK_BYTES + "L > Integer.MAX_VALUE");
            pw.println("                    ? Integer.MAX_VALUE : (int)" + CHUNK_BYTES + "L]);");
            pw.println("            if (i % 3 == 0) System.gc();");
            pw.println("            sleep(300);");
            pw.println("        }");
            pw.println("        System.out.println(\"[HeapExhauster] Phase 2: burst\");");
            pw.println("        Runtime rt = Runtime.getRuntime();");
            pw.println("        while (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory()) > " + (CHUNK_BYTES * 2) + "L) {");
            pw.println("            SINK.add(new byte[(int)Math.min(" + CHUNK_BYTES + "L * 3, Integer.MAX_VALUE)]);");
            pw.println("            sleep(100);");
            pw.println("        }");
            pw.println("        System.out.println(\"[HeapExhauster] Phase 3: OOM\");");
            pw.println("        byte[] finalBlow = new byte[Integer.MAX_VALUE];");
            pw.println("        SINK.add(finalBlow);");
            pw.println("    }");
            pw.println();
            pw.println("    private static void sleep(long ms) {");
            pw.println("        try { Thread.sleep(ms); }");
            pw.println("        catch (InterruptedException e) { Thread.currentThread().interrupt(); }");
            pw.println("    }");
            pw.println("}");
        }
        return sourceFile;
    }
}
