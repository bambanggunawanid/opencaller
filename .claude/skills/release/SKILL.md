---
name: release
description: Build and sanity-check the production web bundle with PWA files, and prepare a version release (changelog, tag). Use for "build for prod", "release", "deploy bundle".
---

# Release PrivZapp (web)

## Build

```bash
./scripts/verify.sh        # must be green first
./scripts/build-web.sh     # dx release build + PWA files at site root
```

Output: `target/dx/privzapp/release/web/public/` — a fully static site.
Confirm it contains `index.html`, `sw.js`, `manifest.webmanifest`, the
icon PNGs, and hashed assets under `assets/` (including the `.wasm`).

## Sanity checks

- `sw.js` and `manifest.webmanifest` must be at the bundle **root** (SW
  scope rule). If missing, `scripts/build-web.sh`'s copy step broke —
  check whether the dx output path changed.
- Any host must serve `.wasm` as `application/wasm` and fall back to
  `index.html` for unknown paths (SPA routing).
- Bump `CACHE` in `app/pwa/sw.js` when shell behavior changes (asset URLs
  are hashed, but the cached `/` document is not).

## Versioned release

1. Move CHANGELOG `[Unreleased]` under a new `## [x.y.z] — YYYY-MM-DD`
   heading (keep an empty `[Unreleased]` above; the docs-sync test requires
   one of the two to exist).
2. Bump `version` in the workspace `Cargo.toml` `[workspace.package]`.
3. `./scripts/verify.sh`, commit, `git tag vx.y.z`.
4. Publishing/hosting is owner-driven; do not push or deploy without being
   asked.
