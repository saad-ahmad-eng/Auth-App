package com.authlock.client;

import com.authlock.client.ui.LoginFrame;
import com.authlock.common.crypto.SharedKeyProvider;
import com.authlock.common.tls.DevTlsSetup;

import javax.crypto.SecretKey;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Entry point for the AuthLock Swing client.
 *
 * <p>Implementation history: Phase 2 proved the RMI channel with a console
 * {@code ping()}; Phases 3–5 added a console demo of login/logout, upload/
 * list/download, and lock/unlock contention; Phase 6 made that demo
 * genuinely AES-256-GCM-encrypted and TLS-protected; Phase 7 made every
 * call audited server-side. <b>Phase 8 (this rewrite):</b> the console demo
 * is fully replaced by the real Swing UI (UIUX.md §1–§2) — the same
 * {@code VaultService} calls, the same encryption, the same RMI connection
 * logic, just triggered from real login/dashboard event handlers instead of
 * running unconditionally in {@code main()}.
 *
 * <p>This class only handles process-level bootstrap (TLS configuration,
 * loading the shared encryption key, resolving the target host) — anything
 * with a UI belongs in {@link LoginFrame}/{@code DashboardFrame} onward.
 *
 * <p>Host defaults to {@code localhost}; override with
 * {@code -Dauthlock.server.host=<host>} (e.g. a cloud VM's public IP in
 * Phase 11, per Architecture.md §3) or a single command-line argument. TLS
 * is on by default (matching {@code ServerMain}), disable with
 * {@code -Dauthlock.tls.enabled=false}. The shared encryption key defaults
 * to {@code authlock-shared.key} in the working directory, overridable via
 * {@code -Dauthlock.crypto.keyfile=<path>} — see {@code SharedKeyProvider}'s
 * Javadoc for why this is a coursework-scope simplification, not a
 * production key-distribution scheme.
 */
public final class ClientMain {

    private static final String DEFAULT_KEY_FILE = "authlock-shared.key";

    private ClientMain() {
    }

    public static void main(String[] args) {
        String host = resolveHost(args);
        boolean tlsEnabled = Boolean.parseBoolean(System.getProperty("authlock.tls.enabled", "true"));

        if (tlsEnabled) {
            try {
                DevTlsSetup.configure();
            } catch (Exception e) {
                showFatalErrorAndExit("Failed to configure TLS: " + e.getMessage());
                return;
            }
        }

        SecretKey sharedKey;
        try {
            Path keyFile = Path.of(System.getProperty("authlock.crypto.keyfile", DEFAULT_KEY_FILE));
            sharedKey = SharedKeyProvider.load(keyFile);
        } catch (NoSuchFileException e) {
            showFatalErrorAndExit("Shared encryption key not found at " + e.getFile()
                    + ".\nStart the server first (it generates the key), or copy its key file here.");
            return;
        } catch (IOException e) {
            showFatalErrorAndExit("Failed to load shared encryption key: " + e.getMessage());
            return;
        }

        SecretKey finalSharedKey = sharedKey;
        SwingUtilities.invokeLater(() -> new LoginFrame(host, finalSharedKey).setVisible(true));
    }

    /**
     * Startup failures (missing key file, broken TLS setup) happen before
     * any window exists — a modal dialog here is still the right call for a
     * GUI app (no guaranteed visible console), and {@link JOptionPane} can
     * be shown safely even before the EDT has anything else running on it.
     */
    private static void showFatalErrorAndExit(String message) {
        JOptionPane.showMessageDialog(null, message, "AuthLock — Startup Error", JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }

    private static String resolveHost(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return args[0];
        }
        return System.getProperty("authlock.server.host", "localhost");
    }
}
