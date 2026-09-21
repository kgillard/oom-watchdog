package com.trongus.oom.monitor;

import java.io.File;

/**
 * Immutable TLS configuration for the {@link MetricsHttpServer}.
 *
 * <p>Three modes are supported:
 * <ol>
 *   <li><strong>Disabled</strong> — plain HTTP only; use only in isolated/trusted networks.
 *       Obtain via {@link #disabled()}.</li>
 *   <li><strong>Self-signed</strong> — a 2048-bit RSA certificate is generated in memory
 *       on first start and regenerated automatically when it expires (default validity:
 *       {@value #DEFAULT_SELF_SIGNED_DAYS} days).  No filesystem artefact is required.
 *       Obtain via {@link #selfSigned()} or {@link #selfSigned(int)}.</li>
 *   <li><strong>User-supplied</strong> — a PKCS#12 ({@code .p12} / {@code .pfx}) or
 *       JKS keystore provided by the operator.  Obtain via
 *       {@link #fromKeystore(String, char[])}.</li>
 * </ol>
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.11.6
 * @since 1.7.4
 * @see MetricsHttpServer
 */
public final class TlsConfig {

    /** Default self-signed certificate validity period in days ({@value}). */
    public static final int DEFAULT_SELF_SIGNED_DAYS = 90;

    /** Enumeration of the three supported TLS modes. */
    public enum Mode {
        /** Plain HTTP — no TLS. */
        DISABLED,
        /** Automatically generated, in-memory self-signed certificate. */
        SELF_SIGNED,
        /** Operator-supplied PKCS#12 or JKS keystore file. */
        KEYSTORE
    }

    private final Mode   mode;
    private final int    selfSignedValidDays;
    private final String keystorePath;
    private final char[] keystorePassword;

    private TlsConfig(Mode mode, int selfSignedValidDays, String keystorePath, char[] keystorePassword) {
        this.mode                = mode;
        this.selfSignedValidDays = selfSignedValidDays;
        this.keystorePath        = keystorePath;
        this.keystorePassword    = keystorePassword == null ? null : keystorePassword.clone();
    }

    // ── factory methods ───────────────────────────────────────────────────────

    /**
     * Returns a configuration that disables TLS (plain HTTP).
     * <p><strong>Warning:</strong> this exposes JVM memory metrics in cleartext.
     * Use only on loopback or isolated private networks.
     *
     * @return disabled-TLS configuration
     */
    public static TlsConfig disabled() {
        return new TlsConfig(Mode.DISABLED, 0, null, null);
    }

    /**
     * Returns a configuration that uses an auto-generated self-signed certificate
     * valid for {@value #DEFAULT_SELF_SIGNED_DAYS} days.  The certificate is
     * regenerated in memory whenever it expires.
     *
     * @return self-signed TLS configuration with default validity
     */
    public static TlsConfig selfSigned() {
        return new TlsConfig(Mode.SELF_SIGNED, DEFAULT_SELF_SIGNED_DAYS, null, null);
    }

    /**
     * Returns a configuration that uses an auto-generated self-signed certificate
     * with a custom validity period.
     *
     * @param validDays number of days the certificate is valid before it is regenerated;
     *                  must be between 1 and 3650
     * @return self-signed TLS configuration
     * @throws IllegalArgumentException if {@code validDays} is out of range
     */
    public static TlsConfig selfSigned(int validDays) {
        if (validDays < 1 || validDays > 3650) {
            throw new IllegalArgumentException("validDays must be 1–3650, got: " + validDays);
        }
        return new TlsConfig(Mode.SELF_SIGNED, validDays, null, null);
    }

    /**
     * Returns a configuration backed by an operator-supplied PKCS#12 or JKS keystore file.
     *
     * @param keystorePath     absolute or relative path to the {@code .p12}, {@code .pfx},
     *                         or {@code .jks} file; must not be {@code null} or blank
     * @param keystorePassword password for the keystore and the private-key entry;
     *                         a defensive copy is made — the caller may clear the original
     *                         after this call
     * @return keystore-backed TLS configuration
     * @throws IllegalArgumentException if {@code keystorePath} is null or blank
     */
    public static TlsConfig fromKeystore(String keystorePath, char[] keystorePassword) {
        if (keystorePath == null || keystorePath.trim().isEmpty()) {
            throw new IllegalArgumentException("keystorePath must not be null or blank");
        }
        return new TlsConfig(Mode.KEYSTORE, 0, keystorePath.trim(), keystorePassword);
    }

    // ── accessors ─────────────────────────────────────────────────────────────

    /**
     * Returns the configured TLS mode.
     *
     * @return non-null {@link Mode}
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * Returns the self-signed certificate validity in days.
     * Only meaningful when {@link #getMode()} is {@link Mode#SELF_SIGNED}.
     *
     * @return validity days
     */
    public int getSelfSignedValidDays() {
        return selfSignedValidDays;
    }

    /**
     * Returns the path to the operator-supplied keystore.
     * Only meaningful when {@link #getMode()} is {@link Mode#KEYSTORE}.
     *
     * @return keystore path, or {@code null} when not in keystore mode
     */
    public String getKeystorePath() {
        return keystorePath;
    }

    /**
     * Returns a defensive copy of the keystore password.
     * Only meaningful when {@link #getMode()} is {@link Mode#KEYSTORE}.
     * <p>Callers should overwrite the returned array after use.
     *
     * @return password character array, or {@code null} when not in keystore mode
     */
    public char[] getKeystorePassword() {
        return keystorePassword == null ? null : keystorePassword.clone();
    }

    /**
     * Returns whether the keystore file exists and is readable.
     * Only relevant when {@link #getMode()} is {@link Mode#KEYSTORE}.
     *
     * @return {@code true} if the keystore file can be read
     */
    public boolean isKeystoreReadable() {
        if (mode != Mode.KEYSTORE || keystorePath == null) return false;
        File f = new File(keystorePath);
        return f.isFile() && f.canRead();
    }

    @Override
    public String toString() {
        switch (mode) {
            case DISABLED:    return "TlsConfig{DISABLED}";
            case SELF_SIGNED: return "TlsConfig{SELF_SIGNED, validDays=" + selfSignedValidDays + "}";
            case KEYSTORE:    return "TlsConfig{KEYSTORE, path='" + keystorePath + "'}";
            default:          return "TlsConfig{" + mode + "}";
        }
    }
}
