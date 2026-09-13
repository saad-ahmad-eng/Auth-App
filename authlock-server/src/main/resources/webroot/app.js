// AuthLock web UI — plain JS, no build step, no framework. Talks to the
// JSON endpoints exposed by AuthLockHttpServer (same-origin, so the session
// cookie set by POST /api/login is sent automatically by fetch()/navigation
// without any extra credentials option).

const loginView = document.getElementById("loginView");
const dashboardView = document.getElementById("dashboardView");
const who = document.getElementById("who");
const whoami = document.getElementById("whoami");
const whoamiId = document.getElementById("whoamiId");

const loginForm = document.getElementById("loginForm");
const loginError = document.getElementById("loginError");

const uploadForm = document.getElementById("uploadForm");
const uploadInput = document.getElementById("uploadInput");
const uploadError = document.getElementById("uploadError");
const dropZone = document.getElementById("dropZone");
const uploadProgressRow = document.getElementById("uploadProgressRow");
const uploadProgressBar = document.getElementById("uploadProgressBar");
const uploadProgressLabel = document.getElementById("uploadProgressLabel");

const adminBtn = document.getElementById("adminBtn");
const adminDialog = document.getElementById("adminDialog");
const adminUsersBody = document.getElementById("adminUsersBody");
const adminUsersError = document.getElementById("adminUsersError");
const createUserForm = document.getElementById("createUserForm");
const createUserError = document.getElementById("createUserError");

const historyDialog = document.getElementById("historyDialog");
const historyFilename = document.getElementById("historyFilename");
const historyBody = document.getElementById("historyBody");
const historyEmpty = document.getElementById("historyEmpty");

const searchInput = document.getElementById("searchInput");
const filesBody = document.getElementById("filesBody");
const filesEmpty = document.getElementById("filesEmpty");
const filesNoMatch = document.getElementById("filesNoMatch");
const filesError = document.getElementById("filesError");
const liveIndicator = document.getElementById("liveIndicator");

let currentUsername = null;
// CSRF synchronizer token (AuthLockHttpServer#requireValidCsrf) — issued in
// the JSON body of /api/login and /api/me, never in a cookie (a cross-site
// page can make the browser attach cookies automatically, but can't read a
// JSON response from a different origin to learn this value). Kept only in
// memory, re-fetched via /api/me on every page load.
let csrfToken = null;

// Client-side view state over the last fetched file list — search/sort never
// re-fetch, they just re-render lastFiles differently.
let lastFiles = [];
let searchQuery = "";
let sortKey = null; // "filename" | "size" | null (server order)
let sortDir = 1; // 1 = ascending, -1 = descending

function showError(el, message) {
  el.textContent = message;
  el.hidden = false;
}

function hideError(el) {
  el.hidden = true;
  el.textContent = "";
}

/** Calls a JSON endpoint; throws an Error carrying .status/.code on a non-2xx response. Attaches the CSRF header automatically on every non-GET call. */
async function api(path, options = {}) {
  const method = (options.method || "GET").toUpperCase();
  const headers = new Headers(options.headers || {});
  if (method !== "GET" && csrfToken) {
    headers.set("X-CSRF-Token", csrfToken);
  }
  const res = await fetch(path, { ...options, headers });
  let data = null;
  const contentType = res.headers.get("Content-Type") || "";
  if (contentType.includes("application/json")) {
    data = await res.json();
  }
  if (!res.ok) {
    const message = (data && data.message) || res.statusText || "Request failed.";
    const err = new Error(message);
    err.status = res.status;
    err.code = data && data.error;
    throw err;
  }
  return data;
}

/**
 * Same contract as api() (resolves with the parsed JSON body, throws an
 * Error with .status/.code on failure) but over XMLHttpRequest instead of
 * fetch — fetch has no upload-progress event, and the upload/replace
 * progress indicator needs one. Used only for the two multipart endpoints;
 * everything else uses the simpler fetch-based api().
 */
function xhrUpload(method, path, formData, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open(method, path);
    if (csrfToken) {
      xhr.setRequestHeader("X-CSRF-Token", csrfToken);
    }
    xhr.upload.addEventListener("progress", (event) => {
      if (onProgress && event.lengthComputable) {
        onProgress(Math.round((event.loaded / event.total) * 100));
      }
    });
    xhr.addEventListener("load", () => {
      let data = null;
      try {
        data = JSON.parse(xhr.responseText);
      } catch (parseError) {
        // non-JSON response body — data stays null, message falls back below
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(data);
      } else {
        const err = new Error((data && data.message) || xhr.statusText || "Request failed.");
        err.status = xhr.status;
        err.code = data && data.error;
        reject(err);
      }
    });
    xhr.addEventListener("error", () => reject(new Error("Network error.")));
    xhr.send(formData);
  });
}

function showUploadProgress(percent) {
  uploadProgressRow.hidden = false;
  uploadProgressBar.style.width = percent + "%";
  uploadProgressLabel.textContent = percent + "%";
}

function hideUploadProgress() {
  uploadProgressRow.hidden = true;
  uploadProgressBar.style.width = "0%";
}

/** Shows the dashboard for whoever the session cookie currently belongs to — calls GET /api/me itself rather than trusting a value passed in, so this is also the single correct way to resume a session on page reload. */
async function enterDashboard() {
  const me = await api("/api/me"); // throws (401) if there's no valid session
  currentUsername = me.username;
  csrfToken = me.csrfToken;
  whoami.textContent = me.username;
  whoamiId.textContent = "· " + shortId(me.userId);
  whoamiId.title = "Your full user ID: " + me.userId;
  adminBtn.hidden = me.role !== "ADMIN";
  who.hidden = false;
  loginView.hidden = true;
  dashboardView.hidden = false;
  await loadFiles();
  startPolling();
}

// ---- live updates: short-interval polling, not a WebSocket ----
// Java's built-in HttpServer has no WebSocket support without either a
// hand-rolled frame implementation or a new library; this project carries
// zero runtime dependencies beyond authlock-common, and at this scale a
// file list every 2.5s is simpler and entirely sufficient. GET /api/files
// is a normal authenticated request (session cookie), not a login attempt,
// so it's untouched by the login rate limiter.
const POLL_INTERVAL_MS = 2500;
let pollTimer = null;

function startPolling() {
  stopPolling();
  pollTimer = setInterval(async () => {
    try {
      lastFiles = await api("/api/files");
      applyAndRender();
      liveIndicator.textContent = "LIVE";
    } catch (err) {
      if (err.status === 401) {
        stopPolling();
        showLogin("SESSION EXPIRED — please log in again.");
        return;
      }
      // A transient failure shouldn't spam the error banner every 2.5s —
      // the indicator itself is the signal, loadFiles()'s own error
      // handling already covers a real, persistent failure.
      liveIndicator.textContent = "CONNECTION LOST — RETRYING…";
    }
  }, POLL_INTERVAL_MS);
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
  rowsByFileId.clear();
}

/** First 8 chars of an id, for display — never used for anything but a label; API calls always use the full id. */
function shortId(id) {
  return id.length > 8 ? id.slice(0, 8) : id;
}

function showLogin(message) {
  stopPolling();
  currentUsername = null;
  csrfToken = null;
  who.hidden = true;
  dashboardView.hidden = true;
  loginView.hidden = false;
  loginForm.reset();
  if (message) {
    showError(loginError, message);
  }
}

document.getElementById("togglePassword").addEventListener("click", () => {
  const passwordInput = document.getElementById("password");
  const btn = document.getElementById("togglePassword");
  const showing = passwordInput.type === "text";
  passwordInput.type = showing ? "password" : "text";
  btn.textContent = showing ? "SHOW" : "HIDE";
});

loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  hideError(loginError);
  // Username is trimmed (a mobile/OS keyboard can add a trailing space on
  // autocomplete-word-commit) — password is sent exactly as typed, since
  // silently altering password input would mask what the user actually
  // meant to enter, which is exactly what the SHOW toggle above is for.
  const username = document.getElementById("username").value.trim();
  const password = document.getElementById("password").value;
  try {
    await api("/api/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
    await enterDashboard();
  } catch (err) {
    showError(loginError, "LOGIN FAILED: " + err.message);
  }
});

document.getElementById("logoutBtn").addEventListener("click", async () => {
  try {
    await api("/api/logout", { method: "POST" });
  } catch (err) {
    // Session was already invalid server-side — fine, we're logging out anyway.
  }
  showLogin();
});

async function loadFiles() {
  hideError(filesError);
  try {
    lastFiles = await api("/api/files");
    applyAndRender();
  } catch (err) {
    if (err.status === 401) {
      showLogin("SESSION EXPIRED — please log in again.");
      return;
    }
    showError(filesError, "COULD NOT LOAD FILES: " + err.message);
  }
}

/** Applies the current search filter + sort to lastFiles, then renders — never re-fetches. */
function applyAndRender() {
  let view = lastFiles;
  if (searchQuery) {
    const needle = searchQuery.toLowerCase();
    view = view.filter((f) => f.filename.toLowerCase().includes(needle));
  }
  if (sortKey) {
    view = view.slice().sort((a, b) => {
      const va = a[sortKey];
      const vb = b[sortKey];
      const cmp = typeof va === "number" ? va - vb : String(va).localeCompare(String(vb));
      return cmp * sortDir;
    });
  }

  filesEmpty.hidden = lastFiles.length !== 0;
  filesNoMatch.hidden = !(lastFiles.length !== 0 && view.length === 0);
  renderRows(view);
}

searchInput.addEventListener("input", () => {
  searchQuery = searchInput.value.trim();
  applyAndRender();
});

function setSort(key) {
  if (sortKey === key) {
    sortDir = -sortDir;
  } else {
    sortKey = key;
    sortDir = 1;
  }
  updateSortHeaders();
  applyAndRender();
}

function updateSortHeaders() {
  const headers = { filename: document.getElementById("sortByFilename"), size: document.getElementById("sortBySize") };
  const labels = { filename: "FILENAME", size: "SIZE" };
  for (const key of Object.keys(headers)) {
    const arrow = sortKey === key ? (sortDir === 1 ? " ▲" : " ▼") : "";
    headers[key].textContent = labels[key] + arrow;
  }
}

document.getElementById("sortByFilename").addEventListener("click", () => setSort("filename"));
document.getElementById("sortBySize").addEventListener("click", () => setSort("size"));

// fileId -> { tr, signature } — lets renderRows() update/reorder existing
// <tr> nodes in place on a poll tick (Step: live updates) instead of wiping
// and rebuilding the whole table every 2-3s, which is what caused the
// "full reload/flicker" this was specifically asked to avoid.
const rowsByFileId = new Map();

function rowSignature(file) {
  return [file.filename, file.size, file.owner, file.lockState, file.lockOwnerHint].join("|");
}

function renderRows(files) {
  const seen = new Set();
  let previousNode = null;
  for (const file of files) {
    seen.add(file.fileId);
    const signature = rowSignature(file);
    let entry = rowsByFileId.get(file.fileId);
    if (!entry) {
      const tr = document.createElement("tr");
      fillRow(tr, file);
      entry = { tr, signature };
      rowsByFileId.set(file.fileId, entry);
    } else if (entry.signature !== signature) {
      fillRow(entry.tr, file);
      entry.signature = signature;
    }
    const expectedNextSibling = previousNode ? previousNode.nextSibling : filesBody.firstChild;
    if (expectedNextSibling !== entry.tr) {
      filesBody.insertBefore(entry.tr, expectedNextSibling);
    }
    previousNode = entry.tr;
  }
  for (const [fileId, entry] of rowsByFileId) {
    if (!seen.has(fileId)) {
      entry.tr.remove();
      rowsByFileId.delete(fileId);
    }
  }
}

function fillRow(tr, file) {
  tr.textContent = "";
  tr.appendChild(fileIdCell(file));
  tr.appendChild(cell(file.filename));
  tr.appendChild(cell(formatSize(file.size)));
  tr.appendChild(cell(file.owner));
  tr.appendChild(lockCell(file));
  tr.appendChild(actionsCell(file));
}

function cell(text) {
  const td = document.createElement("td");
  td.textContent = text;
  return td;
}

/** Short, unambiguous-enough prefix by default (title = full id on hover; click toggles the full id, for touch devices with no hover). */
function fileIdCell(file) {
  const td = document.createElement("td");
  const span = document.createElement("span");
  span.className = "file-id";
  span.textContent = shortId(file.fileId);
  span.title = file.fileId;
  span.tabIndex = 0;
  let expanded = false;
  span.addEventListener("click", () => {
    expanded = !expanded;
    span.textContent = expanded ? file.fileId : shortId(file.fileId);
  });
  td.appendChild(span);
  return td;
}

function lockCell(file) {
  const td = document.createElement("td");
  const span = document.createElement("span");
  span.className = "lock-state" + (file.lockState === "LOCKED" ? " locked" : "");
  span.textContent = file.lockState === "LOCKED"
    ? "LOCKED (" + file.lockOwnerHint.toUpperCase() + ")"
    : "UNLOCKED";
  td.appendChild(span);
  return td;
}

function actionsCell(file) {
  const td = document.createElement("td");
  td.className = "actions";

  const downloadLink = document.createElement("a");
  downloadLink.className = "action-link";
  downloadLink.textContent = "DOWNLOAD";
  downloadLink.href = "/api/files/" + encodeURIComponent(file.fileId)
    + "/download?filename=" + encodeURIComponent(file.filename);
  td.appendChild(downloadLink);

  const youHoldTheLock = file.lockState === "LOCKED" && file.lockOwnerHint === "you";

  if (file.lockState === "LOCKED") {
    if (youHoldTheLock) {
      td.appendChild(actionButton("UNLOCK", () => confirmedUnlock(file.fileId)));
    }
    // locked by another user: no unlock button offered here — matches the
    // Swing client, which only lets the lock's owner release it.
  } else {
    td.appendChild(actionButton("LOCK", () => lockAction(file.fileId, "lock")));
  }

  // REPLACE is enabled only for the current lock holder — same rule the
  // server enforces (LOCK_NOT_OWNED otherwise), this is just not offering a
  // button that would fail, not a security boundary of its own.
  if (youHoldTheLock) {
    td.appendChild(replaceButton(file));
  }

  td.appendChild(actionButton("HISTORY", () => openHistory(file)));

  return td;
}

async function openHistory(file) {
  historyFilename.textContent = file.filename + " (" + file.fileId + ")";
  historyBody.textContent = "";
  historyEmpty.hidden = true;
  try {
    const versions = await api("/api/files/" + encodeURIComponent(file.fileId) + "/versions");
    historyEmpty.hidden = versions.length !== 0;
    for (const v of versions) {
      const row = document.createElement("tr");
      row.appendChild(cell("v" + v.versionNumber));
      row.appendChild(cell(v.replacedBy));
      row.appendChild(cell(new Date(v.timestamp).toLocaleString()));
      row.appendChild(cell(formatSize(v.size)));

      const actions = document.createElement("td");
      const link = document.createElement("a");
      link.className = "action-link";
      link.textContent = "DOWNLOAD THIS VERSION";
      link.href = "/api/files/" + encodeURIComponent(file.fileId) + "/versions/" + v.versionNumber
        + "/download?filename=" + encodeURIComponent(file.filename);
      actions.appendChild(link);
      row.appendChild(actions);

      historyBody.appendChild(row);
    }
  } catch (err) {
    historyEmpty.hidden = false;
    historyEmpty.textContent = "COULD NOT LOAD HISTORY: " + err.message;
  }
  historyDialog.showModal();
}

document.getElementById("historyCloseBtn").addEventListener("click", () => historyDialog.close());

// ---- admin panel (only ever opened when /api/me said role=ADMIN) ----

adminBtn.addEventListener("click", openAdminPanel);
document.getElementById("adminCloseBtn").addEventListener("click", () => adminDialog.close());

async function openAdminPanel() {
  adminDialog.showModal();
  await loadAdminUsers();
}

async function loadAdminUsers() {
  hideError(adminUsersError);
  adminUsersBody.textContent = "";
  try {
    const users = await api("/api/admin/users");
    for (const u of users) {
      const row = document.createElement("tr");
      row.appendChild(cell(u.username));
      row.appendChild(cell(u.role));
      row.appendChild(cell(u.status));

      const actions = document.createElement("td");
      if (u.status === "ACTIVE") {
        actions.appendChild(actionButton("DISABLE", () => setUserStatus(u.userId, "disable")));
      } else {
        actions.appendChild(actionButton("ENABLE", () => setUserStatus(u.userId, "enable")));
      }
      row.appendChild(actions);

      adminUsersBody.appendChild(row);
    }
  } catch (err) {
    showError(adminUsersError, "COULD NOT LOAD USERS: " + err.message);
  }
}

async function setUserStatus(userId, action) {
  hideError(adminUsersError);
  try {
    await api("/api/admin/users/" + encodeURIComponent(userId) + "/" + action, { method: "POST" });
    await loadAdminUsers();
  } catch (err) {
    showError(adminUsersError, action.toUpperCase() + " FAILED: " + err.message);
  }
}

createUserForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  hideError(createUserError);
  const username = document.getElementById("newUsername").value.trim();
  const password = document.getElementById("newPassword").value;
  const role = document.getElementById("newRole").value;
  try {
    await api("/api/admin/users", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password, role }),
    });
    createUserForm.reset();
    await loadAdminUsers();
  } catch (err) {
    showError(createUserError, "CREATE FAILED: " + err.message);
  }
});

function actionButton(label, onClick) {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.textContent = label;
  btn.addEventListener("click", onClick);
  return btn;
}

function replaceButton(file) {
  const input = document.createElement("input");
  input.type = "file";
  input.hidden = true;
  input.addEventListener("change", () => {
    if (input.files[0]) {
      replaceAction(file.fileId, input.files[0]);
    }
  });

  const btn = actionButton("REPLACE", () => input.click());
  const wrapper = document.createElement("span");
  wrapper.appendChild(btn);
  wrapper.appendChild(input);
  return wrapper;
}

/** One careless click shouldn't drop a lock someone's mid-edit on. */
function confirmedUnlock(fileId) {
  if (!confirm("Unlock this file? Anyone else will then be able to lock and replace it.")) {
    return;
  }
  lockAction(fileId, "unlock");
}

async function lockAction(fileId, action) {
  hideError(filesError);
  try {
    await api("/api/files/" + encodeURIComponent(fileId) + "/" + action, { method: "POST" });
    await loadFiles();
  } catch (err) {
    if (err.status === 401) {
      showLogin("SESSION EXPIRED — please log in again.");
      return;
    }
    showError(filesError, action.toUpperCase() + " FAILED: " + err.message);
  }
}

async function replaceAction(fileId, file) {
  hideError(filesError);
  const formData = new FormData();
  formData.append("file", file, file.name);
  showUploadProgress(0);
  try {
    await xhrUpload("POST", "/api/files/" + encodeURIComponent(fileId) + "/replace", formData, showUploadProgress);
    hideUploadProgress();
    await loadFiles();
  } catch (err) {
    hideUploadProgress();
    if (err.status === 401) {
      showLogin("SESSION EXPIRED — please log in again.");
      return;
    }
    showError(filesError, "REPLACE FAILED: " + err.message);
  }
}

// ---- drag-and-drop onto the upload zone (in addition to the file picker) ----
["dragenter", "dragover"].forEach((evt) =>
  dropZone.addEventListener(evt, (event) => {
    event.preventDefault();
    dropZone.classList.add("drag-over");
  })
);
["dragleave", "drop"].forEach((evt) =>
  dropZone.addEventListener(evt, (event) => {
    event.preventDefault();
    dropZone.classList.remove("drag-over");
  })
);
dropZone.addEventListener("drop", (event) => {
  if (event.dataTransfer.files && event.dataTransfer.files.length > 0) {
    uploadInput.files = event.dataTransfer.files;
  }
});

uploadForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  hideError(uploadError);
  const file = uploadInput.files[0];
  if (!file) {
    return;
  }
  const formData = new FormData();
  formData.append("file", file, file.name);
  showUploadProgress(0);
  try {
    await xhrUpload("POST", "/api/files/upload", formData, showUploadProgress);
    uploadForm.reset();
    hideUploadProgress();
    await loadFiles();
  } catch (err) {
    hideUploadProgress();
    if (err.status === 401) {
      showLogin("SESSION EXPIRED — please log in again.");
      return;
    }
    showError(uploadError, "UPLOAD FAILED: " + err.message);
  }
});

function formatSize(bytes) {
  if (bytes < 1024) {
    return bytes + " B";
  }
  if (bytes < 1024 * 1024) {
    return (bytes / 1024).toFixed(1) + " KB";
  }
  return (bytes / (1024 * 1024)).toFixed(1) + " MB";
}

// On load, find out whether we already have a valid session (e.g. page
// refresh) via GET /api/me — the real username/userId, not a guess.
(async function init() {
  try {
    await enterDashboard();
  } catch (err) {
    showLogin();
  }
})();
