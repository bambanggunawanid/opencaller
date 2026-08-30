---
name: add-tool
description: Add a new file tool to PrivZapp end-to-end (registry, engine crate, dispatch, UI options, tests, docs). Use whenever asked to add a tool, operation, or converter.
---

# Add a tool to PrivZapp

Every tool follows the same seam. Work through these steps in order; the
tool page UI is generic, so you almost never touch page code.

## 1. Check the constraint first

The operation must be pure bytes-in/bytes-out and wasm32-safe (no threads,
no I/O, no clocks, no C deps — ADR-0002). If it can't be, stop and discuss;
don't work around it.

## 2. Registry (`crates/pz-core/src/lib.rs`)

- Add a `ToolMeta` to `TOOLS`: slug (kebab-case), name, tagline, category
  (`Pdf`/`Image`/`Archive`/`Security`), `accept` filter, `multi`,
  `min_files`, `options`, emoji icon, `pipeline`.
- `pipeline` is `ToolPipeline::Engine` for anything that is a pure engine
  call — that's almost every tool. `BrowserRender` (PDF rasterization via
  PDF.js, ADR-0009, `app/src/render.rs`) and `BrowserFfmpeg` (video via
  ffmpeg.wasm, ADR-0010, `app/src/video.rs`) are only for work the engine
  physically can't do; both mean writing the app-side path yourself, and
  `pz_engine::run` refuses such slugs.
- If no existing `OptionKind` fits the tool's knobs, add a variant — then
  `app/src/pages/tool.rs` will fail to compile until you add its widget arm
  (that's the point; the match is exhaustive on purpose).
- New option value goes in `ToolOptions` (+ its `Default`).

## 3. Engine implementation (`crates/pz-{pdf,img,archive}` or new crate)

- Signature style: `pub fn op(name: &str, bytes: &[u8], …) -> Result<OutputFile, PzError>`.
- Error taxonomy: `Invalid` (user's fault), `Unsupported` (we don't do
  that), `Failed` (operation broke).
- Compressors must never return more bytes than the input (existing
  invariant — copy the pattern from `pz_img::compress`).
- Unit tests with in-memory fixtures (`sample_pdf`/`sample_png` helpers).
  Test the happy path, one failure path, and the output filename.

## 4. Dispatch (`crates/pz-engine/src/lib.rs::run`)

Add the match arm. Multi-file tools map over `files`; single-file tools use
`files[0]`. Add an engine-level test if the tool composes crates (like
images-to-pdf) or has password/option validation.

## 5. UI (usually nothing)

Only if you added an `OptionKind`: add the widget arm in
`app/src/pages/tool.rs` and thread any new `ToolOptions` field through the
`run` closure. Category additions also need the array in
`app/src/pages/home.rs`.

## 6. SEO copy (`crates/pz-core/src/seo.rs`)

Add a `ToolSeo` entry: title ≤ 65 chars with the primary search keyword
first, description 80–165 chars stating the job + the privacy angle, and
at least 2 FAQ pairs. Tests fail without it, and `tools/seo-gen` turns it
into the prerendered landing page at build time (ADR-0006).

## 7. Docs (same commit — see docs/CONTINUOUS_DOCUMENTATION.md)

- README tools table (a docs-sync test fails if you skip this).
- CHANGELOG `[Unreleased]` → Added.
- New invariants or gotchas → CLAUDE.md.

## 8. Verify

Run `./scripts/verify.sh` (fmt, clippy -D warnings, all tests, wasm32
check). All four must pass before committing.
