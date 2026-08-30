# Security

## Reporting a vulnerability

Open a GitHub issue for non-sensitive problems. For anything exploitable,
use GitHub's private vulnerability reporting on this repository (Security →
Report a vulnerability). Expect a response within a week.

## Security model (summary)

- **No user data exists server-side.** The project's infrastructure is a CI
  cron and static release files. There is nothing to breach, subpoena, or
  sell. Rules, history, and settings live only in app-private storage.
- **The download channel is untrusted by design.** Every DB shard is
  Ed25519-signed; the app verifies against a pinned public key before
  opening anything, refuses malformed payloads, and refuses rollbacks
  (build date is inside the signed file). The signing key lives offline,
  never in the repo or CI logs (CI holds it only as an Actions secret).
- **Permission tiers.** The base app uses only the call-screening role.
  Notification access (WhatsApp/SMS features), overlay, and full-screen
  alerts are independent opt-ins, each disclosed in-app next to its toggle.
  The notification listener drops every package except WhatsApp and the
  default SMS app on its first line and reads only sender information,
  never message bodies.
- **The screening hot path cannot reach the network** — lookups are mmap
  reads of a local file; the only network code is the user-controlled
  shard update.

## Audit log

- **2026-08-30 (v0.2.x self-audit)** — reviewed update path, DB parser,
  notification listener, PendingIntents, secrets handling. Fixed three
  findings: unbounded update download when Content-Length is absent
  (memory-exhaustion risk; now a hard-capped streaming read), potential
  out-of-bounds panic in the DB reader on a malformed-but-signed shard
  (screening now degrades to a miss instead), and history-log delimiter
  injection via attacker-controlled SMS sender names (sanitized).
  Standing items: independent review of the update-verification code is
  welcome — it lives in `crates/opencaller-core/src/update.rs` and is
  deliberately small.
