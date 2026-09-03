package com.authlock.client.ui;

import com.authlock.common.FileMetadata;

import javax.swing.table.AbstractTableModel;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Backs the Main Dashboard's file table (UIUX.md §2/§3): filename, size,
 * owner, modified time, and lock state — the lock state always as a plain
 * text label ("Unlocked" / "Locked (you)" / "Locked (another user)"), never
 * color-only, per UIUX.md §7 Accessibility.
 */
final class FileTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {"Filename", "Size", "Owner", "Modified", "Lock State"};
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private List<FileMetadata> files = List.of();

    void setFiles(List<FileMetadata> files) {
        this.files = List.copyOf(files);
        fireTableDataChanged();
    }

    FileMetadata fileAt(int row) {
        return files.get(row);
    }

    @Override
    public int getRowCount() {
        return files.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int row, int column) {
        FileMetadata file = files.get(row);
        return switch (column) {
            case 0 -> file.filename();
            case 1 -> humanReadableSize(file.size());
            case 2 -> file.owner();
            case 3 -> TIMESTAMP_FORMAT.format(file.modifiedAt());
            case 4 -> lockStateLabel(file);
            default -> "";
        };
    }

    private static String lockStateLabel(FileMetadata file) {
        if (!"LOCKED".equals(file.lockState())) {
            return "Unlocked";
        }
        return "you".equals(file.lockOwnerHint()) ? "Locked (you)" : "Locked (another user)";
    }

    private static String humanReadableSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
