/**
 * Lock Manager: atomic per-file distributed locking, ownership, timeout, and
 * stale-lock recovery — the project's core feature.
 * See Architecture.md §2.7, Backend.md §3, Security.md §8 (SEC-009).
 * Populated starting Implementation.md Phase 5. Validated by TEST-CONC-001.
 */
package com.authlock.server.lock;
