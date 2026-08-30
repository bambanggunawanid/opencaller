---
name: verify
description: Run PrivZapp's full verification battery (fmt, clippy, tests, wasm check) and interpret failures. Use before every commit, after dependency changes, or when asked "does it still build/pass?".
---

# Verify PrivZapp

Single command:

```bash
./scripts/verify.sh
```

Runs, in order: `cargo fmt --check` → `cargo clippy --workspace
--all-targets -- -D warnings` → `cargo test --workspace` → `cargo check -p
privzapp --target wasm32-unknown-unknown`. CI runs the identical script.

## Interpreting failures

- **fmt**: run `cargo fmt --all`, don't hand-wrap.
- **clippy**: fix the code; only `#[allow]` with a comment when the lint is
  genuinely wrong for the case.
- **docs_sync tests** (in pz-core): the README table or CHANGELOG is out of
  date — update the doc, not the test (the test is the contract; see
  docs/CONTINUOUS_DOCUMENTATION.md).
- **wasm check**: usually a dependency pulled a non-wasm feature. Check the
  gotchas in CLAUDE.md (getrandom backends, `default-features = false` on
  image/lopdf/zip) before adding cfg workarounds.
- **crypto tests slow (~14s)**: expected — PBKDF2 at 600k rounds is
  deliberately slow. Don't reduce rounds to speed tests up.

## What this can't verify (needs a real browser)

Service-worker/PWA behavior, drag-and-drop events, downloads. If a change
touches those, say so in the commit/PR message and list the manual
smoke-test steps.
