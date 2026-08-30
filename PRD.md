# PRD — OpenCaller (working title)

**Version:** 0.1 (draft)
**Date:** 2026-08-29
**Status:** For review

---

## 1. Vision

A completely free, open-source mobile app that identifies and blocks spam, scam, and
telemarketing calls — **entirely on-device**. No accounts, no ads, no analytics, no
contact harvesting, no data center holding user data. The phone number database lives
on the user's phone and updates from cheap static file hosting.

**One-liner:** *"TrueCaller's spam shield, without TrueCaller's surveillance."*

## 2. Feasibility & honest constraints

This section is the foundation — the product only works if we scope it correctly.

### What IS possible offline
- **Spam/scam/robocall identification** ("Scam likely", "Telemarketer", "Insurance spam")
  from a locally stored database of reported numbers.
- **Call blocking / silencing** based on that database plus user-defined rules.
- **Business caller ID** for public businesses (from open data), optionally.
- **Database updates** via signed static files on a CDN / GitHub Releases / F-Droid —
  no per-user server compute, no user data collected. Cost: effectively zero.

**Prior art proving feasibility:** *Should I Answer?* and *Yet Another Call Blocker*
(offline spam DBs on Android). On iOS, Apple's **Call Directory Extension is
offline-only by design** — it cannot hit the network during a lookup — so the offline
model isn't just possible there, it's mandatory.

### What is NOT possible under our constraints
- **Identifying an arbitrary private person's name** (the GetContact feature). That
  requires centrally harvesting users' contact books — the exact practice this app
  exists to reject (and which is illegal or restricted in many jurisdictions under
  GDPR and similar laws). We will never do it. This is a stated product principle,
  not a missing feature.

### The one genuinely hard problem: where does the data come from?
A caller-ID app is only as good as its database. Without harvesting contacts, our
sources are:

1. **Public/regulator datasets** — e.g., FTC Do-Not-Call complaint data (US, public,
   updated daily), FCC robocall data, and equivalents in other countries where
   available. Free, legal, no privacy issues.
2. **Existing open/community spam lists** — curated, license-permitting.
3. **Open business registries** for business caller ID (e.g., national business
   registers, OpenCorporates-style data) — optional, later phase.
4. **Community reporting (Phase 2, opt-in):** users can report a spam number. A report
   contains ONLY `{number, category, country}` — never the reporter's identity,
   contacts, or call log. This needs a *minimal* ingestion point (a serverless
   endpoint or even a moderated GitHub flow), which is the only piece of
   infrastructure in the whole product. It stores reported numbers, not user data.
   The app is fully functional without it; it exists to keep the DB fresh.

> **Decided 2026-08-29:** layered collection strategy approved — M0 regulator
> piggyback (always-on) → M1 public-ledger bootstrap → M3 STAR threshold-encrypted
> reporting as the research flagship. Full mechanism design and threat analysis:
> [docs/collection-mechanisms.md](docs/collection-mechanisms.md).

## 3. Goals & non-goals

### Goals
- G1: Warn the user before/while an unknown number rings, using only on-device lookup.
- G2: Block or silence calls matching the spam DB or user rules.
- G3: Zero collection of user data. Provably: open source, no network permission at
  lookup time paths, reproducible builds.
- G4: Run on low-end devices; DB lookup adds < 50 ms to call handling.
- G5: Operating cost low enough to be sustained by one person indefinitely
  (target: < $10/month, i.e., static hosting only).

### Non-goals
- Identifying private individuals by name.
- SMS spam filtering (possible later; different APIs, keep MVP tight).
- Messaging, social features, "who viewed your profile", or any account system.
- Monetization. The app is free; sustainability via donations/sponsorship only.

## 4. Target users

| Persona | Need |
|---|---|
| Privacy-conscious user | Spam protection without giving an app their contact book |
| Elderly / vulnerable users (via family setup) | Scam-call protection that works with no configuration |
| De-googled / F-Droid users | An app with no proprietary dependencies |
| Anyone burned by TrueCaller/GetContact | Same utility, none of the data collection |

## 5. Product principles (the "anti-features" list)

These are commitments, displayed in-app and enforced by architecture:

1. **No contact upload — ever.** The app never requests the Contacts permission in MVP.
2. **No accounts.** Nothing to sign up for; nothing to breach.
3. **No analytics, trackers, or crash reporters that phone home.** Crash logs are
   local; the user may manually share them.
4. **Lookups never touch the network.** The only network activity is downloading the
   signed database update, and the user can see/schedule/disable it.
5. **Open source (GPL-3.0 or AGPL) with reproducible builds**, so claims 1–4 are
   verifiable, not marketing.

## 6. Core features

### MVP (Phase 1) — Android first
| # | Feature | Detail |
|---|---|---|
| F1 | Incoming-call spam label | Via `CallScreeningService` (Android 10+): show "⚠ Reported spam — Insurance robocall (reported 1,204×)" on the call screen |
| F2 | Auto-block by category | User chooses per category: Allow / Silence / Reject. Default: silence "Scam", allow the rest with warning |
| F3 | Offline database | Per-country DB shards; user downloads only their country/-ies (target 5–40 MB each) |
| F4 | Signed delta updates | Weekly (configurable) via WorkManager, Wi-Fi-only option; Ed25519-signed; full + delta files on static hosting |
| F5 | User rules | Personal block/allow list; prefix rules (e.g., block `+1-800-*`); "silence all unknown numbers" mode |
| F6 | Post-call lookup | After a missed call, user can open the app and check the number against the local DB |
| F7 | On-device heuristics | Neighbor-spoofing detection (caller number suspiciously similar to user's own), invalid/unallocated prefix detection — pure logic, no data needed |
| F8 | Zero-setup default | Install → grant call-screening role → protected. No account, no wizard beyond country selection (pre-filled from SIM) |

### Phase 2
- ~~VoIP (WhatsApp) call warnings~~ — shipped early (2026-08-30) as an
  opt-in notification-listener companion: warn-only, package-allowlisted,
  offline lookups. Blocking another app's VoIP calls is impossible on
  Android by design; disclosure of the broad notification-access
  permission lives in-app next to the toggle.
- iOS app (CallKit Call Directory + Call Blocking extensions).
- Opt-in anonymous community reporting (see §2 and §8).
- Business caller ID from open registries (separate optional DB shard).
- In-app "report to regulator" shortcut (deep link to FTC/TRAI/etc. complaint forms —
  turns users into contributors to the public datasets we consume).

### Phase 3 (exploratory)
- SMS spam filtering (Android `SmsFilterService` / iOS `ILMessageFilterExtension` —
  note iOS message filters may not use the network either, which fits us).
- P2P database distribution (torrent/IPFS mirror) for censorship resistance.

## 7. Architecture, platform notes & constraints

### Shared Rust core (`opencaller-core`)
All performance-critical and correctness-critical logic lives in one Rust crate,
shared across every target:
- DB format **encoder + decoder** (bloom filter, delta-encoded blocks, mmap'd reads),
  lookup engine, on-device heuristics (F7), and the update client (download →
  Ed25519 verify → delta apply).
- Bindings: **UniFFI** (Kotlin/Swift) or direct JNI where hot paths demand it.
- The **same crate powers the CI data pipeline and a dev CLI** — the code that builds
  the shards is the code that reads them, so the format can't drift, and every phone
  codepath is testable on a desktop.
- Perf targets owned by this crate: lookup < 1 ms (p99), screening decision end-to-end
  < 50 ms, zero heap-loading of the DB (mmap only) so the iOS extension memory budget
  is met by construction.

### UI layer decision
The OS call-screening entry points can never be Rust (Android requires a
Kotlin/Java `CallScreeningService`; iOS requires a Swift extension), and they are
where perceived speed lives — so the UI framework is not on the hot path.
- **MVP: Jetpack Compose** thin shell over the Rust core (fast cold start, small APK,
  boring and reliable).
- **Dioxus (Rust) considered** for a shared UI later: today its mobile renderer is
  webview-based (native Blitz renderer not yet production-ready on mobile), which
  costs cold-start time and APK size for no hot-path gain. Revisit when native
  mobile rendering stabilizes; the Rust core is UI-framework-agnostic either way.

### Android (MVP)
- **API:** `CallScreeningService` + `RoleManager.ROLE_CALL_SCREENING`. This is the
  Google-sanctioned path — it does **not** require the restricted `READ_CALL_LOG`
  permission and is compatible with Play Store policy. Avoid requesting
  Phone/Call-Log permissions beyond the role; Play policy on these is strict and a
  common rejection reason.
- Min SDK 29 (Android 10) for full screening; consider a degraded "post-call lookup
  only" mode for 26–28 if cheap, else cut it.
- Stack: thin Kotlin shell (screening service, Compose UI, WorkManager scheduling)
  delegating to `opencaller-core` (Rust) for lookups, heuristics, and update
  verification. No Google Play Services dependency (must run on de-googled devices;
  distribute on **F-Droid + Play + GitHub APK**). Rust NDK builds are F-Droid-
  compatible; keep reproducibility in CI from day one.

### iOS (Phase 2)
- **Call Directory Extension** (CallKit): numbers + labels are preloaded into the
  system, sorted ascending, via the extension. Constraints: tight extension memory
  budget (stream entries from a compact file, never load the DB into RAM), reload
  managed via `CXCallDirectoryManager`. No network at lookup — matches our model
  exactly.
- Blocking list and identification list are separate entry types; map categories to
  labels ("Scam — OpenCaller").

## 8. Data strategy

### Database contents
Per entry: E.164 number (or prefix) → `{category, confidence, report_count, last_seen}`.
**No names of private individuals, ever.** Categories: scam, robocall, telemarketing,
debt collection, survey, business (Phase 2), unallocated-range.

### Format & size budget
- Custom compact binary format: numbers delta-encoded within sorted per-prefix blocks,
  categories as enums; Bloom filter front-end so the 99% case (unknown number) is
  answered in microseconds without touching the main index; mmap'd, never fully
  loaded into RAM.
- Budget: 10 M entries ≈ 30–60 MB raw → 15–40 MB compressed per large country; small
  countries a few MB. Weekly deltas expected < 1 MB.
- SQLite is the fallback if the custom format isn't worth it for MVP scale —
  benchmark first, don't optimize prematurely.

### Pipeline (runs in CI, not on a server)
GitHub Actions (or similar) cron job running the `opencaller-core` CLI: fetch public
datasets → normalize to E.164 →
merge/dedupe → age-out entries not re-reported in N months (numbers get reassigned to
innocent people — **aging is a correctness and ethics requirement, not an
optimization**) → build shards → sign → publish to GitHub Releases + CDN mirror.
Total infra: a CI cron and static hosting.

### Community reports (Phase 2) — STAR threshold-encrypted reporting
Design detail: [docs/collection-mechanisms.md](docs/collection-mechanisms.md).
- Report = `{number, category, country}`, created only by an explicit user tap;
  implicit signals never leave the phone. Submitted via OHTTP relay using the
  **STAR protocol**: the aggregation server can only decrypt a number once ≥ K
  distinct users report it in the same epoch — k-anonymity enforced
  cryptographically, not by policy ("can't be evil").
- Infra: randomness server (ideally run by an independent partner org) +
  aggregation server + OHTTP relay; sub-threshold reports are deleted
  unreadable at epoch end.
- **Poisoning defense**: K doubles as the poisoning bar (≥ K authorized installs
  required), plus Privacy Pass per-install report tokens, temporal spread
  (reports across ≥ D days/epochs), allocation-range sanity checks, anomaly
  review before publication, and fast-path removal ("this number is wrongly
  listed" appeal form). Publish the moderation policy.
- Rollout: M0 regulator piggyback at launch → M1 moderated public ledger while
  small → M3 STAR shadow pilot at ≈ 5k users/country → primary.

## 9. Security & privacy architecture

**Threat model (summary):**

| Threat | Mitigation |
|---|---|
| App exfiltrates user data (the TrueCaller problem) | Architecture has no path for it: no contacts permission, no lookup network calls, open source, reproducible builds; F-Droid build as an independently verified artifact |
| Malicious/tampered DB update (MITM, compromised CDN) | Ed25519 signature on every DB file, key held offline; app rejects unsigned/rolled-back DBs (monotonic version + TLS + cert pinning as defense-in-depth) |
| DB poisoning via community reports | §8 defenses; MVP has no report channel at all |
| De-anonymizing a reporter | Reports carry no identity; no server logs retained beyond abuse-window; coarse timestamps |
| Defamation/wrong listing harms an innocent number holder | Aging policy, thresholds, public appeal/removal process, categories phrased as "reported as", never asserted fact |
| Supply chain (deps) | Minimal dependency policy, lockfiles, Dependabot/audit in CI, no closed-source SDKs |

**Privacy stance in one sentence:** the project's servers (CI + static hosting) store
*data about phone numbers*, never *data about users* — there is nothing to subpoena,
breach, or sell.

Independent security review of the update-verification code before 1.0 (budget
permitting; otherwise a public audit call on the repo).

## 10. UX outline (MVP)

1. **Onboarding (3 screens max):** what the app does & doesn't do (the §5 promises) →
   grant call-screening role → confirm country → done.
2. **Call screen overlay/label** during ring (Android supplies the surface via the
   screening role).
3. **Home:** protection status, DB version/freshness, last blocked calls (from our own
   screening history, not the system call log).
4. **Number check:** manual lookup field.
5. **Settings:** per-category action, user rules, update schedule (incl. Wi-Fi only /
   manual only / off), DB shard management.

Accessibility: large-text friendly, high-contrast warning states — the
scam-vulnerable-user persona depends on it.

## 11. Distribution & cost model

| Item | Choice | Cost |
|---|---|---|
| App distribution | F-Droid, Google Play, direct APK; App Store in Phase 2 | $25 Play one-time + $99/yr Apple (Phase 2) |
| DB hosting | GitHub Releases + free-tier CDN mirror (e.g., Cloudflare) | ~$0 |
| Data pipeline | GitHub Actions cron | ~$0 (public repo) |
| Community reports (Phase 2) | Serverless free tier | ~$0 at launch scale |

No ads, no premium tier. Optional: donations (GitHub Sponsors / Liberapay / OpenCollective).

## 12. Success metrics — without telemetry

We collect nothing, so measurement is indirect and that's accepted:
- Store installs/ratings, F-Droid popularity, GitHub stars/issues.
- **Opt-in, on-device stats screen** ("OpenCaller blocked 34 calls this month") that
  the user can choose to share — data never leaves the device automatically.
- DB quality proxy: % of top-N regulator-reported numbers present in our DB (measurable
  in CI against the public datasets, no users involved).

Targets for first 6 months post-launch: 10k installs, ≥ 4.3 store rating, DB covering
≥ 90% of numbers appearing in the source datasets ≥ 3 times, < 5 substantiated
wrong-listing appeals.

## 13. Risks & open questions

| Risk | Severity | Note / mitigation |
|---|---|---|
| DB coverage outside US/IN/EU is thin (few public datasets) | High | Be honest in-app about per-country coverage; community reporting (Phase 2) is the long-term fix; heuristics (F7) work everywhere |
| Users expect GetContact-style name lookup and rate it down | Medium | Set expectations hard in store listing + onboarding: "We can't tell you your ex's new number's owner. That's the point." |
| Play/App Store policy shifts on call-screening APIs | Medium | F-Droid + direct APK as policy-independent channels |
| Dataset licenses restrict redistribution | Medium | Legal check per source *before* ingestion; prefer government/public-domain data |
| Number reassignment → stale spam label on an innocent person | Medium | Aging (§8) + appeal process |
| Solo-maintainer sustainability | Medium | Boring tech, CI-automated pipeline, tiny dependency surface |

**Open questions**
1. Working name/brand ("OpenCaller" collides with nothing? trademark check).
2. License: GPL-3.0 vs AGPL-3.0 (AGPL matters only if the Phase-2 report service should be copyleft too).
3. Launch countries for DB shards (proposal: US + India + a EU pilot, based on dataset availability).
4. Ship degraded Android 8–9 support or min-SDK 29 only?
5. ~~Community reporting: serverless endpoint vs. moderated GitHub-based flow.~~
   **Resolved 2026-08-29:** layered M0→M1→M3 (STAR); see docs/collection-mechanisms.md.
6. STAR parameters: K threshold, epoch length, and — most importantly — who
   operates the independent randomness server (see §5.8 of the mechanisms doc).

## 14. Milestones

| Milestone | Scope | Target |
|---|---|---|
| M0 — Validation spike | Kotlin `CallScreeningService` calling Rust core over UniFFI/JNI + DB format benchmark (10 M entries: size, lookup latency, RAM) | ✅ done 2026-08-29 (JNI bridge, OCDB benchmark: 133 ns miss / 1.5 µs hit / 77 MB / RAM-flat) |
| M1 — Data pipeline | CI job producing signed shards for 2–3 launch countries | ◐ US pipeline done (FTC DNC → signed shard, validated on real data); CI cron + more countries pending |
| M2 — MVP alpha | F1–F8 integrated, internal testing | ◐ F1–F8 all implemented (2026-08-30; F1 via heads-up notification — no default-dialer takeover); remaining: on-device testing. Note: F3 recon confirmed §13 risk 1 — only the US publishes per-number data (FTC+FCC); other countries rely on F7 heuristics until M3 |
| M3 — Public beta | F-Droid + Play open beta, feedback loop, security review of update path | +4 wks |
| M4 — 1.0 | Stable release, store listings, docs | +4 wks |
| M5 — Phase 2 | iOS app; decide & possibly ship community reporting | post-1.0 |
