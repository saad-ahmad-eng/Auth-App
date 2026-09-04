# AuthLock Report

`AuthLock-Report.pdf` / `AuthLock-Report.docx` — the written report deliverable
(`auth` §10: "design, development, deployment, walkthrough and testing").

## Contents

Executive summary; objectives and requirements coverage; system architecture and
key design decisions; security design; the distributed-locking implementation and
its mandatory concurrency proof; a phase-by-phase development-process summary
(including real defects found and fixed along the way); a testing/QA summary; the
AWS deployment design and its honest current status; an application walkthrough
with real screenshots from a live server+client run; known limitations; and a
conclusion.

Every factual claim in the report is drawn from this project's own documentation
(`PRD.md`, `Architecture.md`, `Security.md`, `Testing.md`, `Context.md`) and real
artifacts captured from a live run (screenshots, an audit-log excerpt) — nothing
in it is asserted without a corresponding test, inspection, or interactive
verification recorded elsewhere in the repo.

## Regenerating it

The report has no separately-maintained source of truth to edit by hand — it's
generated from an HTML file (screenshots embedded as base64 data URIs) via
LibreOffice headless, the same pipeline used the first time:

```bash
# 1. Capture fresh screenshots against a live server+client if anything material
#    has changed since the last report (login, dashboard, lock state, session
#    expiry, etc. — see Context.md's Phase 8/9 Implementation Log entries for
#    the interactive-verification approach used to capture these).
# 2. Update the report content (currently authored directly as HTML — ask
#    whoever generated the current version for that source file if you don't
#    have it, or reconstruct from this README's "Contents" list above).
# 3. Convert: HTML -> ODT -> DOCX, and HTML -> PDF directly.
soffice --headless --convert-to odt report.html
soffice --headless --convert-to docx report.odt
soffice --headless --convert-to pdf report.html
```

`scripts/package-submission.sh` expects `AuthLock-Report.pdf` and
`AuthLock-Report.docx` to already exist in this directory before it assembles
the submission zip.
