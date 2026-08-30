---
name: continuous-docs
description: Per-change documentation checklist for PrivZapp — which docs a change must update and how drift is enforced. Use at the end of any feature/fix, before committing, or when asked to "update the docs".
---

# Continuous documentation checklist

The contract lives in docs/CONTINUOUS_DOCUMENTATION.md: **code, tests and
docs land in the same commit.** Before committing, walk this list against
your diff:

- [ ] Registry changed (`pz-core::TOOLS`)? → README tools table. A test
      (`crates/pz-core/tests/docs_sync.rs`) enforces this; if it fails,
      fix the README, never the test.
- [ ] User-visible change? → one line in CHANGELOG `[Unreleased]` under
      Added/Changed/Fixed/Removed.
- [ ] Crate graph, data flow, or platform split changed? →
      docs/ARCHITECTURE.md.
- [ ] Made a hard-to-reverse decision (format, dependency class, security
      posture)? → new ADR in docs/adr/ (next number; Status: Accepted).
      Superseding an old one? Mark it Superseded, don't rewrite it.
- [ ] Roadmap item finished/blocked/cut? → README checkbox +
      docs/ROADMAP.md status.
- [ ] New gotcha discovered (build quirk, dep pin, wasm trap)? →
      CLAUDE.md "Gotchas".
- [ ] New public engine API? → rustdoc comment stating the contract
      (inputs, outputs, failure modes, invariants).

Then run `./scripts/verify.sh` — the docs-sync tests are part of the
battery, so missed items surface as test failures, not review comments.

When a change genuinely touches no doc, you're done — but say so
explicitly in the commit message ("no doc changes needed: internal
refactor") so reviewers know it was considered, not forgotten.
