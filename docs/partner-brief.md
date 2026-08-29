# OpenCaller — Randomness-Server Partner Brief

*One page for a prospective operating partner (digital-rights org, university
lab, privacy foundation).*

## What OpenCaller is

A free, open-source (GPL) mobile app that identifies and blocks spam/scam
calls **entirely on-device** — no accounts, no ads, no contact harvesting, no
user data collected, ever. The spam-number database is built from public
regulator datasets plus community reports, and ships to phones as signed
static files. Project docs: `PRD.md`, `docs/collection-mechanisms.md`.

## The ask

Operate one small service: the **randomness server** of the STAR protocol
(Davidson et al., the threshold-aggregation scheme Brave deploys for private
telemetry) — a stateless Rust service (Brave's open-source `ppoprf`) that
answers blinded PRF evaluations under a per-epoch key and **punctures** each
epoch's key when the epoch closes.

- Footprint: one tiny VM; CPU per query ≈ 1 elliptic-curve operation; traffic
  a few requests per app user per month.
- No moderation duties, no data custody, no user-facing anything.

## Why the protocol needs a second institution

STAR lets our aggregation server decrypt a reported phone number **only when
≥ K distinct users report the same number in the same epoch** — k-anonymity
enforced by secret sharing, not policy. The one residual attack is a
*dictionary probe*: whoever controls BOTH the aggregation server and
unrestricted PRF evaluation could test candidate numbers. Splitting the two
services across **two independent institutions** is what turns "we promise
not to" into "we structurally can't" — a single organization (including us)
must never hold both halves.

## What your server can and cannot see

| Sees | Never sees |
|---|---|
| Blinded curve points (cryptographically opaque) | Any phone number (queries are blinded) |
| Query volume / timing | Who is reporting (queries arrive via OHTTP relay) |
| — | Report contents, categories, or the aggregated database |

Misbehavior is *detectable*: every evaluation carries a DLEQ proof that
clients verify against your published per-epoch public key.

## Status / evidence

Working Rust implementation of the full client + aggregation flow using
Brave's production crates (`sta-rs`, `ppoprf`) with measured results from a
simulated 10k-user epoch (`cargo run --release --bin star_sim`):

- Client cost: ~0.7 ms per report (incl. OPRF round + proof verification);
  259-byte wire messages; epoch aggregation < 1 ms.
- Correctness demos: a 23-report campaign number is recovered with category
  breakdown; 309 sub-threshold reports stay cryptographically unreadable; a
  6-report poisoning attempt fails; a targeted probe learns only a bucket
  count (never reporter identities); after epoch puncturing, retroactive
  probes are refused by the math, not by policy.

## Next step

A 30-minute call to walk through the threat model
(`docs/collection-mechanisms.md` §5.5) and your operational requirements.
