package com.authlock.client.ui;

import com.authlock.client.rmi.RmiConnection;
import com.authlock.client.rmi.ServerUnavailableException;
import com.authlock.common.ErrorCode;
import com.authlock.common.VaultService;
import com.authlock.common.VaultServiceException;

import javax.crypto.SecretKey;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Arrays;

/**
 * Login Screen (UIUX.md §1): username, password, login button, status/error
 * area. Attempts the RMI connection in the background as soon as the window
 * opens, so connection state (UIUX.md §6) is visible before the user even
 * starts typing, rather than surfacing only as a one-time error dialog on
 * first submit.
 */
public final class LoginFrame extends JFrame {

    private final String host;
    private final SecretKey sharedKey;

    private final JTextField usernameField = new JTextField(18);
    private final JPasswordField passwordField = new JPasswordField(18);
    private final JButton loginButton = new JButton("Login");
    private final JLabel statusLabel = new JLabel(" ");

    private volatile VaultService service;

    public LoginFrame(String host, SecretKey sharedKey) {
        super("AuthLock — Login");
        this.host = host;
        this.sharedKey = sharedKey;
        buildUi();
        connect();
    }

    private void buildUi() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        form.add(new JLabel("Username:"), gbc);
        gbc.gridx = 1;
        form.add(usernameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        form.add(new JLabel("Password:"), gbc);
        gbc.gridx = 1;
        form.add(passwordField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        form.add(loginButton, gbc);

        gbc.gridy = 3;
        statusLabel.setForeground(Color.DARK_GRAY);
        form.add(statusLabel, gbc);

        getContentPane().add(form);
        pack();
        setLocationRelativeTo(null);

        // Enter in either field submits — keyboard accessibility (UIUX.md §7).
        loginButton.addActionListener(e -> attemptLogin());
        usernameField.addActionListener(e -> attemptLogin());
        passwordField.addActionListener(e -> attemptLogin());

        loginButton.setEnabled(false);
        setStatus("Connecting to server...");
        usernameField.requestFocusInWindow();
    }

    private void connect() {
        SwingAsync.run(
                () -> RmiConnection.connect(host),
                connectedService -> {
                    this.service = connectedService;
                    loginButton.setEnabled(true);
                    setStatus("Connected. Enter your credentials.");
                },
                ex -> {
                    loginButton.setEnabled(false);
                    setStatus("Server unavailable — " + rootMessage(ex));
                });
    }

    private void attemptLogin() {
        VaultService currentService = this.service;
        if (currentService == null) {
            setStatus("Not connected to the server yet.");
            return;
        }
        String username = usernameField.getText().trim();
        char[] password = passwordField.getPassword();
        if (username.isEmpty() || password.length == 0) {
            setStatus("Enter both username and password.");
            return;
        }

        loginButton.setEnabled(false);
        setBusyCursor(true);
        setStatus("Logging in...");

        SwingAsync.run(
                () -> currentService.login(username, new String(password)),
                token -> {
                    Arrays.fill(password, '\0');
                    setBusyCursor(false);
                    openDashboard(currentService, username, token);
                },
                ex -> {
                    Arrays.fill(password, '\0');
                    passwordField.setText("");
                    setBusyCursor(false);
                    loginButton.setEnabled(true);
                    setStatus(errorMessage(ex));
                    usernameField.requestFocusInWindow();
                });
    }

    private void openDashboard(VaultService connectedService, String username, String sessionToken) {
        DashboardFrame dashboard = new DashboardFrame(connectedService, sharedKey, username, sessionToken, host);
        dashboard.setVisible(true);
        dispose();
    }

    private void setBusyCursor(boolean busy) {
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private static String errorMessage(Exception ex) {
        if (ex instanceof VaultServiceException vse) {
            if (vse.getErrorCode() == ErrorCode.AUTHENTICATION_FAILED) {
                return "Invalid username or password.";
            }
            return "Login failed: " + vse.getErrorCode();
        }
        if (ex instanceof ServerUnavailableException) {
            return "Server unavailable.";
        }
        return "Unexpected error: " + rootMessage(ex);
    }

    private static String rootMessage(Exception ex) {
        Throwable t = ex;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
