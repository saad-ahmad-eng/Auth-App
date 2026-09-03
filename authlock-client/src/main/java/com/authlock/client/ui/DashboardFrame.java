package com.authlock.client.ui;

import com.authlock.client.rmi.ServerUnavailableException;
import com.authlock.common.ErrorCode;
import com.authlock.common.FileContent;
import com.authlock.common.FileMetadata;
import com.authlock.common.VaultService;
import com.authlock.common.VaultServiceException;
import com.authlock.common.crypto.AesGcmCipher;

import javax.crypto.SecretKey;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.file.Files;
import java.rmi.RemoteException;

/**
 * Main Dashboard (UIUX.md §2): file list, upload, download, lock, unlock,
 * refresh, logout, and a connection-state indicator. Every remote-call
 * button runs asynchronously ({@link SwingAsync}) and is disabled for the
 * call's duration (UIUX.md §5 "Loading state").
 */
public final class DashboardFrame extends JFrame {

    private static final AesGcmCipher CIPHER = new AesGcmCipher();

    private final VaultService service;
    private final SecretKey sharedKey;
    private final String host;
    private final String sessionToken;

    private final FileTableModel tableModel = new FileTableModel();
    private final JTable table = new JTable(tableModel);
    private final JButton uploadButton = new JButton("Upload");
    private final JButton downloadButton = new JButton("Download");
    private final JButton lockButton = new JButton("Lock");
    private final JButton unlockButton = new JButton("Unlock");
    private final JButton refreshButton = new JButton("Refresh");
    private final JButton logoutButton = new JButton("Logout");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel connectionStatusLabel = new JLabel("Connected");

    DashboardFrame(VaultService service, SecretKey sharedKey, String username, String sessionToken, String host) {
        super("AuthLock — " + username);
        this.service = service;
        this.sharedKey = sharedKey;
        this.sessionToken = sessionToken;
        this.host = host;
        buildUi();
        refresh();
    }

    private void buildUi() {
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSelectionDependentButtons();
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        toolbar.add(uploadButton);
        toolbar.add(downloadButton);
        toolbar.add(lockButton);
        toolbar.add(unlockButton);
        toolbar.add(refreshButton);
        toolbar.add(logoutButton);
        add(toolbar, BorderLayout.NORTH);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(connectionStatusLabel, BorderLayout.EAST);
        add(statusBar, BorderLayout.SOUTH);

        uploadButton.addActionListener(e -> doUpload());
        downloadButton.addActionListener(e -> doDownload());
        lockButton.addActionListener(e -> doLock());
        unlockButton.addActionListener(e -> doUnlock());
        refreshButton.addActionListener(e -> refresh());
        logoutButton.addActionListener(e -> doLogout());

        setSize(760, 420);
        setLocationRelativeTo(null);
        updateSelectionDependentButtons();
    }

    /** Lock enabled only when unlocked; unlock enabled only when locked by *this* session (UIUX.md §2). */
    private void updateSelectionDependentButtons() {
        int row = table.getSelectedRow();
        boolean hasSelection = row >= 0;
        downloadButton.setEnabled(hasSelection);
        if (hasSelection) {
            FileMetadata selected = tableModel.fileAt(row);
            lockButton.setEnabled(!"LOCKED".equals(selected.lockState()));
            unlockButton.setEnabled("LOCKED".equals(selected.lockState()) && "you".equals(selected.lockOwnerHint()));
        } else {
            lockButton.setEnabled(false);
            unlockButton.setEnabled(false);
        }
    }

    private void setBusy(boolean busy) {
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        uploadButton.setEnabled(!busy);
        refreshButton.setEnabled(!busy);
        logoutButton.setEnabled(!busy);
        if (busy) {
            downloadButton.setEnabled(false);
            lockButton.setEnabled(false);
            unlockButton.setEnabled(false);
        } else {
            updateSelectionDependentButtons();
        }
    }

    private void refresh() {
        setBusy(true);
        setStatus("Refreshing...");
        SwingAsync.run(
                () -> service.listFiles(sessionToken),
                files -> {
                    setBusy(false);
                    tableModel.setFiles(files);
                    connectionStatusLabel.setText("Connected");
                    setStatus("Vault contains " + files.size() + " file(s).");
                },
                ex -> {
                    setBusy(false);
                    handleFailure(ex, "Refresh failed");
                });
    }

    private void doUpload() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();

        setBusy(true);
        setStatus("Uploading " + file.getName() + "...");
        SwingAsync.run(
                () -> {
                    byte[] plaintext = Files.readAllBytes(file.toPath());
                    AesGcmCipher.Encrypted encrypted = CIPHER.encrypt(sharedKey, plaintext);
                    return service.uploadFile(sessionToken, file.getName(), encrypted.ciphertext(), encrypted.iv());
                },
                fileId -> {
                    setBusy(false);
                    setStatus("Upload succeeded: " + file.getName());
                    refresh();
                },
                ex -> {
                    setBusy(false);
                    handleFailure(ex, "Upload failed");
                });
    }

    private void doDownload() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        FileMetadata selected = tableModel.fileAt(row);

        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(selected.filename()));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();

        setBusy(true);
        setStatus("Downloading " + selected.filename() + "...");
        SwingAsync.run(
                () -> {
                    FileContent content = service.downloadFile(sessionToken, selected.fileId());
                    byte[] plaintext = CIPHER.decrypt(sharedKey, content.iv(), content.fileBytes());
                    Files.write(target.toPath(), plaintext);
                    return target;
                },
                savedTo -> {
                    setBusy(false);
                    setStatus("File saved to " + savedTo.getAbsolutePath());
                },
                ex -> {
                    setBusy(false);
                    handleFailure(ex, "Download failed");
                });
    }

    private void doLock() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        FileMetadata selected = tableModel.fileAt(row);

        setBusy(true);
        setStatus("Locking " + selected.filename() + "...");
        SwingAsync.run(
                () -> {
                    service.lockFile(sessionToken, selected.fileId());
                    return null;
                },
                ignored -> {
                    setBusy(false);
                    setStatus("Locked: " + selected.filename());
                    refresh();
                },
                ex -> {
                    setBusy(false);
                    handleFailure(ex, "Lock failed");
                });
    }

    private void doUnlock() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        FileMetadata selected = tableModel.fileAt(row);

        setBusy(true);
        setStatus("Unlocking " + selected.filename() + "...");
        SwingAsync.run(
                () -> {
                    service.unlockFile(sessionToken, selected.fileId());
                    return null;
                },
                ignored -> {
                    setBusy(false);
                    setStatus("Unlocked: " + selected.filename());
                    refresh();
                },
                ex -> {
                    setBusy(false);
                    handleFailure(ex, "Unlock failed");
                });
    }

    private void doLogout() {
        setBusy(true);
        setStatus("Logging out...");
        SwingAsync.run(
                () -> {
                    service.logout(sessionToken);
                    return null;
                },
                ignored -> returnToLogin(),
                // Even a failed logout (e.g. session already expired) should still return the user to the login screen.
                ex -> returnToLogin());
    }

    private void returnToLogin() {
        new LoginFrame(host, sharedKey).setVisible(true);
        dispose();
    }

    private void handleFailure(Exception ex, String actionLabel) {
        if (ex instanceof VaultServiceException vse) {
            if (vse.getErrorCode() == ErrorCode.INVALID_SESSION) {
                JOptionPane.showMessageDialog(this, "Your session has expired. Please log in again.",
                        "Session Expired", JOptionPane.WARNING_MESSAGE);
                returnToLogin();
                return;
            }
            setStatus(actionLabel + ": " + friendlyMessage(vse.getErrorCode()));
        } else if (ex instanceof ServerUnavailableException || ex instanceof RemoteException) {
            connectionStatusLabel.setText("Server Unavailable");
            setStatus(actionLabel + ": server unavailable.");
        } else {
            setStatus(actionLabel + ": " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
    }

    private static String friendlyMessage(ErrorCode code) {
        return switch (code) {
            case FILE_LOCKED -> "this file is locked by another user.";
            case LOCK_NOT_OWNED -> "you do not hold the lock on this file.";
            case FILE_NOT_FOUND -> "file not found — it may have been removed.";
            case UPLOAD_FAILED -> "upload failed.";
            case DOWNLOAD_FAILED -> "download failed.";
            case UNAUTHORIZED -> "you are not authorized to perform this action.";
            case SERVER_ERROR -> "an unexpected server error occurred.";
            default -> code.name();
        };
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }
}
