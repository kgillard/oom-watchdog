package com.trongus.oom.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * Locale-aware message source backed by {@link ResourceBundle} property files
 * located at {@code com/trongus/oom/i18n/Messages[_locale].properties} on the
 * classpath.
 *
 * <h2>UTF-8 encoding</h2>
 * <p>Java's default {@link ResourceBundle} loader reads {@code .properties}
 * files as ISO-8859-1 which corrupts double-byte characters (CJK, Korean).
 * This class overrides {@link ResourceBundle.Control} to read every bundle
 * file as UTF-8, ensuring that Japanese, Korean, and Chinese translations are
 * stored and loaded correctly.
 *
 * <h2>Fallback chain</h2>
 * <p>The standard {@link ResourceBundle} fallback chain applies:
 * {@code Messages_zh_CN.properties} → {@code Messages_zh.properties}
 * → {@code Messages.properties} (English base / ultimate fallback).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Messages msg = new Messages(Locale.JAPANESE);
 * String heading = msg.get("section.heap");           // simple lookup
 * String diag    = msg.format("cause.heap_exhaustion", 92.5, 90.0);  // parameterised
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * <p>Instances are effectively immutable after construction — the
 * {@link ResourceBundle} cache is managed by the JDK and is thread-safe.
 * {@code Messages} instances may be freely shared across threads.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.11.6
 * @since 1.3.0
 */
public final class Messages {

    /** Base name of the resource bundle on the classpath. */
    private static final String BASE_NAME =
            "com.trongus.oom.i18n.Messages";

    private final ResourceBundle bundle;
    private final Locale         locale;

    /**
     * Constructs a {@code Messages} instance for the given locale.
     *
     * <p>If no bundle exists for the requested locale the English base bundle
     * ({@code Messages.properties}) is used as the ultimate fallback.
     *
     * @param locale the desired locale; must not be {@code null}
     * @throws NullPointerException if {@code locale} is {@code null}
     */
    public Messages(Locale locale) {
        if (locale == null) throw new NullPointerException("locale");
        this.locale = locale;
        this.bundle = ResourceBundle.getBundle(BASE_NAME, locale, Utf8Control.INSTANCE);
    }

    /**
     * Returns the localised string for the given message key.
     *
     * <p>If the key is missing from all bundles in the fallback chain a
     * placeholder of the form {@code "?<key>?"} is returned rather than
     * throwing, so that a missing translation never propagates as an
     * unchecked exception into monitoring code.
     *
     * @param key the message key; must not be {@code null}
     * @return the localised string, or {@code "?<key>?"} if not found
     */
    public String get(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return "?" + key + "?";
        }
    }

    /**
     * Returns a localised string with {@link MessageFormat} parameter
     * substitution applied.
     *
     * <p>The pattern is looked up via {@link #get(String)} and then
     * formatted using {@link MessageFormat#format(String, Object...)} with
     * the supplied arguments.  If {@link MessageFormat} throws
     * {@link IllegalArgumentException} (e.g. malformed pattern in a custom
     * bundle) the raw pattern string is returned unchanged so that monitoring
     * is never interrupted by a translation error.
     *
     * @param key  the message key whose value is a {@link MessageFormat} pattern
     * @param args positional arguments substituted into the pattern
     * @return the formatted localised string, or the raw pattern on format error
     */
    public String format(String key, Object... args) {
        String pattern = get(key);
        try {
            return MessageFormat.format(pattern, args);
        } catch (IllegalArgumentException e) {
            return pattern;
        }
    }

    /**
     * Returns the locale used to resolve this bundle.
     *
     * @return the locale; never {@code null}
     */
    public Locale getLocale() {
        return locale;
    }

    // ── UTF-8 ResourceBundle.Control ─────────────────────────────────────────

    /**
     * A {@link ResourceBundle.Control} that forces UTF-8 decoding of
     * {@code .properties} files, overriding the JDK default of ISO-8859-1.
     *
     * <p>Without this override, any CJK or other multi-byte characters stored
     * in property files would be silently corrupted when loaded.
     */
    private static final class Utf8Control extends ResourceBundle.Control {

        static final Utf8Control INSTANCE = new Utf8Control();

        private Utf8Control() {}

        @Override
        public ResourceBundle newBundle(String baseName, Locale locale,
                                        String format,
                                        ClassLoader loader,
                                        boolean reload)
                throws IOException, IllegalAccessException, InstantiationException {

            if (!"java.properties".equals(format)) {
                // Delegate non-.properties formats (e.g. .class bundles) to parent
                return super.newBundle(baseName, locale, format, loader, reload);
            }

            String bundleName     = toBundleName(baseName, locale);
            String resourceName   = toResourceName(bundleName, "properties");
            InputStream stream    = null;

            if (reload) {
                URL url = loader.getResource(resourceName);
                if (url != null) {
                    URLConnection conn = url.openConnection();
                    conn.setUseCaches(false);
                    stream = conn.getInputStream();
                }
            } else {
                stream = loader.getResourceAsStream(resourceName);
            }

            if (stream == null) {
                return null;
            }

            try {
                return new PropertyResourceBundle(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
            } finally {
                stream.close();
            }
        }
    }
}
