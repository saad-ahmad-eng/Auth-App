# flow.md — System Flows

**Project:** AuthLock
**Related documents:** [API-spec.md](API-spec.md) · [Architecture.md](Architecture.md) · [Security.md](Security.md)

All flows below reference the `VaultService` remote interface and error codes defined in [API-spec.md](API-spec.md). Every flow must remain consistent with that spec — if a flow changes, update API-spec.md in the same change (per [Development-rules.md](Development-rules.md) §4).

---

## 1. Login

```mermaid
sequenceDiagram
    participant C as Client
    participant V as VaultService
    participant A as Auth Service
    participant S as Session Manager
    participant L as Audit Logger

    C->>V: login(username, password)
    V->>A: verifyCredentials(username, password)
    A-->>V: success + userId
    V->>S: createSession(userId)
    S-->>V: sessionToken
    V->>L: log(LOGIN, SUCCESS)
    V-->>C: sessionToken
```

## 2. Authentication Failure

```mermaid
sequenceDiagram
    participant C as Client
    participant V as VaultService
    participant A as Auth Service
    participant L as Audit Logger

    C->>V: login(username, password)
    V->>A: verifyCredentials(username, password)
    A-->>V: failure
    V->>L: log(LOGIN, FAILURE)
    V-->>C: AUTHENTICATION_FAILED
```

## 3. Session Creation

```mermaid
sequenceDiagram
    participant V as VaultService
    participant S as Session Manager

    V->>S: createSession(userId)
    S->>S: generate SecureRandom token
    S->>S: store {token, userId, createdAt, expiresAt}
    S-->>V: sessionToken
```

## 4. Session Validation

```mermaid
sequenceDiagram
    participant C as Client
    participant V as VaultService
    participant S as Session Manager

    C->>V: anyOperation(sessionToken, ...)
    V->>S: validate(sessionToken)
    alt token valid and not expired
        S-->>V: userId
        V-->>C: proceed with operation
    else token invalid/expired
        S-->>V: invalid
        V-->>C: INVALID_SESSION
    end
```

## 5. File Listing

```mermaid
sequenceDiagram
    participant C as Client
    participant V as VaultService
    participant S as Session Manager
    participant F as Vault Service
    participant Lk as Lock Manager

    C->>V: listFiles(sessionToken)
    V->>S: validate(sessionToken)
    S-->>V: userId (valid)
    V->>F: getAllMetadata()
    F->>Lk: getLockStates()
    Lk-->>F: lock state per file
    F-->>V: file metadata list (with lock state)
    V-->>C: file list
```

## 6. File Upload

```mermaid
sequenceDiagram
    participant C as Client
    participant V as VaultService
    participant S as Session Manager
    participant E as Encryption Service
    participant F as Vault Service
    participant L as Audit Logger

    C->>E: encrypt(fileBytes)
    E-->>C: ciphertext + IV
    C->>V: uploadFile(sessionToken, filename, ciphertext, IV)
    V->>S: validate(sessionToken)
    S-->>V: userId (valid)
    V->>F: store(fileId=new, filename, ciphertext, IV, owner=userId)
    F->>F: compute checksum, persist bytes + metadata
    F-->>V: fileId
    V->>L: log(UPLOAD, SUCCESS, fileId)
    V-->>C: fileId
```

## 7. File Download

```mermaid
sequenceDiagram
    participant C as Client
    participant V as VaultService
    participant S as Session Manager
    participant F as Vault Service
    participant E as Encryption Service
    participant L as Audit Logger

    C->>V: downloadFile(sessionToken, fileId)
    V->>S: validate(sessionToken)
    S-->>V: userId (valid)
    V->>F: retrieve(fileId)
    alt file exists
        F-->>V: ciphertext + IV
        V->>L: log(DOWNLOAD, SUCCESS, fileId)
        V-->>C: ciphertext + IV
        C->>E: decrypt(ciphertext, IV)
        E-->>C: plaintext bytes
    else file not found
        F-->>V: not found
        V->>L: log(DOWNLOAD, FAILURE, fileId)
        V-->>C: FILE_NOT_FOUND
    end
```

## 8. File Locking

```mermaid
sequenceDiagram
    participant C as Client
    participant V as VaultService
    participant S as Session Manager
    participant Lk as Lock Manager
    participant L as Audit Logger

    C->>V: lockFile(sessionToken, fileId)
    V->>S: validate(sessionToken)
    S-->>V: userId (valid)
    V->>Lk: acquire(fileId, sessionToken)  note right of Lk: atomic putIfAbsent
    alt lock acquired
        Lk-->>V: granted
        V->>L: log(LOCK, SUCCESS, fileId)
        V-->>C: lock granted
    else already locked
        Lk-->>V: denied (owner=otherSession)
        V->>L: log(LOCK, FAILURE, fileId)
        V-->>C: FILE_LOCKED
    end
```

## 9. File Unlock

```mermaid
sequenceDiagram
    participant C as Client
    participant V as VaultService
    participant S as Session Manager
    participant Lk as Lock Manager
    participant L as Audit Logger

    C->>V: unlockFile(sessionToken, fileId)
    V->>S: validate(sessionToken)
    S-->>V: userId (valid)
    V->>Lk: release(fileId, sessionToken)
    alt requester is owner
        Lk-->>V: released
        V->>L: log(UNLOCK, SUCCESS, fileId)
        V-->>C: unlocked
    else requester is not owner
        Lk-->>V: denied
        V->>L: log(UNLOCK, FAILURE, fileId)
        V-->>C: LOCK_NOT_OWNED
    end
```

## 10. Concurrent Lock Attempt

```mermaid
sequenceDiagram
    participant C1 as Client A
    participant C2 as Client B
    participant V as VaultService
    participant Lk as Lock Manager

    par simultaneous requests
        C1->>V: lockFile(tokenA, fileId=X)
    and
        C2->>V: lockFile(tokenB, fileId=X)
    end
    V->>Lk: acquire(X, tokenA)
    V->>Lk: acquire(X, tokenB)
    note over Lk: atomic compare-and-set on the map entry for X — only one caller wins
    Lk-->>V: A granted, B denied  (or B granted, A denied — exactly one)
    V-->>C1: result A
    V-->>C2: result B
```

**Invariant (validated by [Testing.md](Testing.md) Concurrency Test):** regardless of arrival order or thread interleaving, exactly one of the racing requests receives `granted`; all others receive `FILE_LOCKED`.

## 11. Lock Timeout / Stale Lock

```mermaid
sequenceDiagram
    participant Lk as Lock Manager
    participant S as Session Manager
    participant C2 as Client B

    note over Lk: Client A acquired lock on fileId=X, then crashed/disconnected
    S->>S: session-cleanup sweep detects Client A's session expired
    S->>Lk: notify session expired (A)
    Lk->>Lk: release lock owned by A's session
    C2->>Lk: lockFile(tokenB, X)
    Lk-->>C2: granted (lock was reclaimed as stale)
```

## 12. Logout

```mermaid
sequenceDiagram
    participant C as Client
    participant V as VaultService
    participant S as Session Manager
    participant L as Audit Logger

    C->>V: logout(sessionToken)
    V->>S: invalidate(sessionToken)
    S-->>V: removed
    V->>L: log(LOGOUT, SUCCESS)
    V-->>C: acknowledged
```

## 13. Invalid Session

```mermaid
sequenceDiagram
    participant C as Client
    participant V as VaultService
    participant S as Session Manager
    participant L as Audit Logger

    C->>V: anyOperation(staleOrUnknownToken, ...)
    V->>S: validate(token)
    S-->>V: not found / expired
    V->>L: log(AUTHZ_FAILURE, FAILURE)
    V-->>C: INVALID_SESSION
```

## 14. Unauthorized Access (Lock Not Owned)

```mermaid
sequenceDiagram
    participant C as Client B (not lock owner)
    participant V as VaultService
    participant Lk as Lock Manager
    participant L as Audit Logger

    C->>V: unlockFile(tokenB, fileId=X)
    V->>Lk: release(X, tokenB)
    Lk-->>V: denied — owner is Client A's session
    V->>L: log(UNLOCK, FAILURE, fileId=X)
    V-->>C: LOCK_NOT_OWNED
```

## 15. Upload Failure

```mermaid
sequenceDiagram
    participant C as Client
    participant V as VaultService
    participant F as Vault Service
    participant L as Audit Logger

    C->>V: uploadFile(sessionToken, filename, ciphertext, IV)
    V->>F: store(...)
    F-->>V: I/O error (e.g., disk full, invalid filename)
    V->>L: log(UPLOAD, FAILURE)
    V-->>C: UPLOAD_FAILED
```

## 16. Download Failure

```mermaid
sequenceDiagram
    participant C as Client
    participant V as VaultService
    participant F as Vault Service
    participant L as Audit Logger

    C->>V: downloadFile(sessionToken, fileId)
    V->>F: retrieve(fileId)
    F-->>V: checksum mismatch / read error
    V->>L: log(DOWNLOAD, FAILURE, fileId)
    V-->>C: DOWNLOAD_FAILED
```

## 17. Server Unavailable

```mermaid
sequenceDiagram
    participant C as Client
    participant R as RMI Registry (unreachable)

    C->>R: lookup("VaultService")
    R--xC: connection refused / timeout
    C->>C: display "Server unavailable" state (FR-013)
    note over C: no operation attempted; retry left to user
```

## 18. Audit Logging (Cross-Cutting)

```mermaid
sequenceDiagram
    participant V as VaultService
    participant Any as (any internal service)
    participant L as Audit Logger
    participant Disk as audit.log

    Any-->>V: operation result (success or failure)
    V->>L: log(eventType, result, metadata)
    L->>Disk: append structured record (synchronous)
    note over L,Disk: no password/token/key fields ever included — see Security.md §9
```

---

## Consistency Note

Every remote call named in these flows (`login`, `logout`, `listFiles`, `uploadFile`, `downloadFile`, `lockFile`, `unlockFile`) and every error code referenced (`AUTHENTICATION_FAILED`, `INVALID_SESSION`, `FILE_LOCKED`, `LOCK_NOT_OWNED`, `FILE_NOT_FOUND`, `UPLOAD_FAILED`, `DOWNLOAD_FAILED`) must exactly match [API-spec.md](API-spec.md). If either document changes, update both together (see [Development-rules.md](Development-rules.md) §4 Change Management).
