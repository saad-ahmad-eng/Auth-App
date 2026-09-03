# UIUX.md — Swing Application UI/UX Design

**Project:** AuthLock
**Related documents:** [API-spec.md](API-spec.md) · [flow.md](flow.md) · [Architecture.md](Architecture.md) (ADR-002)

Scope note: this is a **Java Swing desktop application**. No web or mobile UI patterns (responsive breakpoints, hamburger menus, touch gestures) are introduced, per [p1.md](p1.md) §18.

---

## 1. Login Screen

| Element | Behavior |
|---|---|
| Username field | `JTextField`, focused by default on screen open. |
| Password field | `JPasswordField` (masked input). |
| Login button | Invokes `login()`; disabled while a login call is in flight (loading state) and re-enabled on completion (success or failure). |
| Status/error area | A `JLabel` (or small panel) below the form showing `AUTHENTICATION_FAILED` as a plain "Invalid username or password" message, or a connection error ("Cannot reach server") if the RMI lookup itself fails (FR-013). |
| Enter-key submit | Pressing Enter in either field triggers the same action as clicking Login (keyboard accessibility). |

On success: transitions to the Main Dashboard and stores the returned session token in client memory only (never written to disk).

---

## 2. Main Dashboard

| Element | Behavior |
|---|---|
| File list | A `JTable` (or `JList` with a custom renderer) bound to `listFiles()` results — columns: filename, size, owner, modified time, lock state (see §3). |
| Upload button | Opens a `JFileChooser`; on selection, encrypts (if client-side AES) and calls `uploadFile()`; disabled while no dashboard session is active or while an upload is already in progress. |
| Download button | Enabled only when a file row is selected; calls `downloadFile()`, decrypts, and prompts a save location via `JFileChooser`. |
| Lock button | Enabled only when a file row is selected **and** it is currently unlocked (or locked by the current user, for the unlock case — see next row); calls `lockFile()`. |
| Unlock button | Enabled only when the selected file is locked **by the current session**; calls `unlockFile()`. |
| Refresh button | Re-invokes `listFiles()` and repopulates the table. |
| Logout button | Calls `logout()`, clears the in-memory token, returns to the Login Screen. |
| Status indicator | A persistent small panel/icon showing connection state (Connected / Server Unavailable) — see §6. |

---

## 3. File Information

Each row/detail view for a file shows:

| Field | Source |
|---|---|
| Filename | `FileMetadata.filename` |
| Size | `FileMetadata.size` (human-readable, e.g., KB/MB) |
| Owner | `FileMetadata.owner` |
| Modified time | `FileMetadata.modifiedAt` (localized display format) |
| Lock state | `FileMetadata.lockState` — rendered as a plain-text tag ("Unlocked" / "Locked" / "Locked by you"), optionally with a color or icon accent that still reads correctly without color (accessibility — see §7). |

---

## 4. Notifications

| Event | Message (example) |
|---|---|
| Login success | (Implicit — transition to dashboard; optional transient "Welcome, `<username>`" status text.) |
| Login failure | "Invalid username or password." |
| Upload success | "File uploaded: `<filename>`." |
| Upload failure | "Upload failed: `<reason>`." (reason mapped from `UPLOAD_FAILED`, not a raw stack trace) |
| Download success | "File saved to `<path>`." |
| Download failure | "Download failed: `<reason>`." |
| File locked | "This file is locked by another user." (on a blocked lock attempt) |
| File unavailable | "File not found — it may have been removed." |
| Session expired | "Your session has expired. Please log in again." — triggers return to Login Screen. |

All notifications use non-blocking status text or `JOptionPane` dialogs as appropriate (transient status for routine confirmations, modal dialog for errors requiring acknowledgement) — final choice per element left to implementation, but must be **consistent** across the app (per [Development-rules.md](Development-rules.md) §2 maintainability).

---

## 5. UX Rules

| Rule | Application |
|---|---|
| **Clear errors** | Every error code in [API-spec.md](API-spec.md) §3 Error Model maps to a specific, human-readable message — never a raw exception or error code shown to the user. |
| **Loading state** | Buttons that trigger a remote call are disabled and (optionally) show a busy cursor/progress indicator for the call's duration, so the user cannot double-submit. |
| **Disabled buttons when inappropriate** | Download/Lock/Unlock disabled with no row selected; Unlock disabled unless the current session owns the lock; Login disabled while a login call is in flight. |
| **Confirmation dialogs** | Not required by `auth` for destructive actions (there is no delete operation in scope) — none introduced beyond what's functionally necessary, per [p1.md](p1.md) §21 (avoid unnecessary UI complexity). |
| **Safe file selection** | `JFileChooser` is used for both upload source and download destination — no free-text path entry, avoiding user-side path errors. |
| **Lock-state visibility** | Lock state is always visible in the file list (§3), not hidden behind a details click. |
| **Connection-state visibility** | See §6 — always visible, not just surfaced as a one-time error dialog. |

---

## 6. Connection-State Indicator

A persistent status element on the Main Dashboard (and implicitly assumed reachable on the Login Screen too) shows:

- **Connected** — normal state, calls proceeding.
- **Server Unavailable** — the RMI registry lookup or a call failed with a transport-level error (FR-013); dashboard actions are disabled except Refresh (which can be used to retry), and a clear message explains the state rather than leaving stale data silently displayed.

---

## 7. Accessibility

| Aspect | Approach |
|---|---|
| **Keyboard navigation** | All interactive controls (fields, buttons, table rows) reachable via Tab order; Enter submits the focused form/default button; no mouse-only interactions. |
| **Readable labels** | Every field and button has a visible, descriptive `JLabel`/button text — no icon-only controls without a tooltip or text label. |
| **Logical focus order** | Tab order follows visual/reading order (username → password → login button; file list → action buttons). |
| **Meaningful error messages** | As in §4/§5 — messages describe what happened and, where relevant, what the user can do next (e.g., "Please log in again" rather than just "Session expired"). |
| **Lock/connection state without color alone** | Text labels ("Locked", "Server Unavailable") are always present alongside any color/icon accent, so the UI remains usable for colorblind users or in monochrome screenshots (relevant for the coursework report). |

---

## 8. Scope Boundary

No additional screens (settings, admin panel, user management, file preview/edit-in-app) are introduced beyond Login and Main Dashboard, since `auth` describes only login and a file browser with upload/download/lock/unlock controls (`auth` §6). Any future screen (e.g., an audit-log viewer) is a Future Enhancement — see [PRD.md](PRD.md) §6.
