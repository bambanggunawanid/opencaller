# Collection Mechanisms — Design & Research Notes

**Status:** Strategy approved 2026-08-29 — layered M0 → M1 → M3; M2 fallback only; M4 parked.
**Parent doc:** [PRD.md](../PRD.md) §8

---

## 1. Framing

Spam is invisible to a single phone: a campaign is "one number → 10,000 people",
and each phone sees one data point. The collective signal cannot be computed
offline. Therefore:

- **Lookup** is offline (local DB — solved in PRD).
- **Collection** is collective by nature. The research question is not *"no
  server"* but *"how little can the collection point know, hold, and be trusted
  with."*
- Design goal: **"can't be evil"** — the collector is architecturally or
  cryptographically unable to learn anything about individual users, rather than
  merely promising not to.

### What counts as a report
- **Explicit user tap only** ("Report spam → category"). One report queued
  locally as `{number, category, country, epoch}`.
- Implicit signals (call duration < 5 s, immediate hang-up, repeated rings)
  **never leave the phone** — they only tune the local shield. Auto-reporting
  would leak call metadata patterns and widen the poisoning surface.

### The loop
```
┌─────────────────────────── PHONE (offline island) ───────────────────────────┐
│  incoming call → local DB lookup → warn/block                                │
│  user taps "report spam" → queued: {number, category, country, epoch}        │
└──────────────┬───────────────────────────────────────────▲──────────────────┘
               │ ① submission leg (M0–M4 below)            │ ④ signed static shards
               ▼                                           │
┌── COLLECTION POINT (as blind as possible) ──┐   ┌── CI PIPELINE ─────────────┐
│  learns: numbers reported ≥ K times         │──▶│ merge with public datasets │
│  cannot learn: who, or numbers < K          │ ② │ age-out, moderate, sign  ③ │
└─────────────────────────────────────────────┘   └────────────────────────────┘
```

---

## 2. M0 — Regulator piggyback (approved: always-on layer)

**Flow:** app deep-links "Report to regulator" → user files with FTC/TRAI/etc. →
regulator publishes complaint datasets → our CI ingests them.

- We run nothing; we learn nothing; users trust only their government's
  complaint process.
- Weaknesses: slow cadence (days–weeks), limited countries, coarse categories.
- Role: permanent baseline layer and cold-start data source. Never removed.

## 3. M1 — Public ledger drop-box (approved: bootstrap phase)

**Flow:** app submits `{number, category, country}` through a tiny stateless
relay → lands in a public, moderated repository → human/rule moderation → CI.

- Full transparency: anyone can audit exactly what is collected and how
  moderation decides. Transparency doubles as an early Sybil defense (mass
  fake reports are publicly visible).
- Weaknesses: relay sees source IPs (mitigate: accept via Tor / OHTTP; retain
  nothing), manual moderation does not scale.
- Role: while user base is too small for thresholds to trip (see §7). Retired
  in favor of M3 once sustainable.

## 4. M2 — Anonymous drop-box + software thresholds (fallback only)

Plain serverless endpoint, IP-stripping, K-threshold + D-day-spread applied in
software before publication. Same infra cost as M3 but only "won't be evil"
(operator *could* log IPs and sub-threshold reports; users must trust config).
**Superseded by M3; kept documented as fallback** if M3 hits an implementation
wall.

## 5. M3 — STAR threshold-encrypted reporting (approved: research flagship) 🔬

### 5.1 Property

> The aggregation server can decrypt reports for a given number **only when
> ≥ K distinct clients reported the same number in the same epoch.** Below K,
> reports are cryptographically unreadable — k-anonymity by math, not policy.

Protocol family: **STAR** (Distributed Secret Sharing for Private Threshold
Aggregation Reporting — Davidson et al., deployed by Brave for private
telemetry). Comparison point: Poplar/DPF heavy-hitters (stronger hiding, two
interactive non-colluding aggregators, far more complex) — see §5.6.

### 5.2 Parties

| Party | Runs | Must not collude with |
|---|---|---|
| Client (phone) | report generation | — |
| **Randomness server** | (P)OPRF evaluations, per-epoch keys | Aggregation server |
| **Aggregation server** | collects reports, threshold recovery | Randomness server |
| OHTTP relay (optional but recommended) | strips network identity | Aggregation server |
| CI pipeline | consumes recovered numbers → signed DB | — |

Two small stateless-ish services. Ideal: randomness server operated by an
independent org (digital-rights group, university lab) so collusion requires
two institutions.

### 5.3 Protocol walkthrough (concrete)

User reports `+15551234567` as "insurance robocall" in epoch `2026-W35`:

```
CLIENT
 1. rand ← OPRF(randomness_server, msg = number ∥ epoch)
      • oblivious: rand server never sees the number, only a blinded point
      • deterministic: every client reporting the same number in the same
        epoch derives the SAME rand
 2. split rand → (key_seed, share_seed, tag)
 3. c  = AEAD_Enc(KDF(key_seed), number ∥ category ∥ country)
 4. s  = Shamir share of key_seed  (threshold K, random evaluation point
         from share_seed’s randomness)
 5. send {c, s, tag} → OHTTP relay → aggregation server
      • relay sees who but not what; server sees what-bucket but not who

AGGREGATION SERVER (per epoch)
 6. bucket reports by tag           (equal numbers ⇒ equal tags)
 7. |bucket| < K  → shares cannot interpolate; ciphertexts stay noise;
                    delete at epoch end
    |bucket| ≥ K  → interpolate Shamir shares → key_seed → decrypt bucket
                  → learn: number, categories, count → hand to CI pipeline
```

Epoch rotation (rand server rotates OPRF key): tags are unlinkable across
epochs, and stale sub-threshold reports expire by construction.

### 5.4 What each party learns — honest ledger

| Party | Learns | Does NOT learn |
|---|---|---|
| Randomness server | volume of OPRF queries | any number, any reporter identity |
| Aggregation server | numbers with ≥ K reports (+ categories, counts); *bucket-size histogram* of sub-threshold tags | sub-threshold numbers' values; reporter identities (with relay) |
| Relay | client IPs, timing | report contents |
| Everyone (published DB) | threshold-crossing numbers | — |

### 5.5 Leakage & attacks — the real research content

1. **Dictionary attack on a small message space.** E.164 numbers are a ~10¹⁰
   space, and a *targeted* query ("did anyone report number X?") needs only ONE
   OPRF evaluation: an attacker who controls the aggregation server and can
   also query the randomness server *as a client* computes tag(X) and checks
   the bucket table. This is THE central weakness for our use case (Brave's
   telemetry values are less enumerable/targetable).

   **Adversary walkthrough — what the attack actually yields.** Assume the
   worst case: aggregation server fully compromised, attacker holds valid
   Privacy Pass tokens (one real install suffices for a few queries).
   - *Targeted probe on number X*: attacker runs the client OPRF path for X,
     gets tag(X, epoch), finds the bucket → learns **"X was reported j times
     this epoch"**. A single probe is indistinguishable from honest report
     preparation, so no rate limit or anomaly detector can prevent it.
     **However — and this is the key result — reporter anonymity survives
     intact.** Reports carry no identity and arrive via OHTTP relay; even a
     fully compromised aggregator learns report *counts*, never *reporters*.
     The harm ceiling of a successful targeted probe is low: e.g., a spammer
     learns their number is "close to threshold", or a stalker learns a
     victim's number was reported j times — but never by whom.
   - *Bulk enumeration of the number space*: requires one token-gated OPRF
     query per candidate number per epoch. At token-supply-limited rates,
     enumerating even one country's allocated ranges (~10⁸–10⁹) per weekly
     epoch is infeasible. Bulk enumeration is **blocked**; targeted probing is
     **possible but low-harm**. This is the honest security posture and must be
     stated in public docs exactly this way.

   **Defenses (must implement all):**
   - Rate-limit + anonymously-authorize OPRF queries (Privacy Pass tokens
     issued per app install; one token ≙ one report, spend-once) — kills bulk
     enumeration.
   - Non-collusion split: rand server logs/alerts on aggregate query-pattern
     anomalies; institutional separation makes *systematic* probing detectable
     even though single probes are not.
   - Per-epoch keys: a dictionary answer only covers one epoch.
   - Sub-threshold buckets + tags deleted at epoch close (limits retro attacks).
   - **Puncturable POPRF** (Brave's `ppoprf`): past epochs become
     cryptographically unqueryable even if keys later leak.
   - **Chaff reports — evaluated 2026-08-29, deprioritized.** Simulated at a
     5% client rate (495 chaff reports over a 10k-user epoch, `star_sim`):
     the published DB stays unchanged and the sealed-bucket histogram is
     heavily noised (302 → 797 buckets — decent *distributional* cover for
     "how many distinct numbers got reported"), but a **targeted probe's
     count is completely unblurred** (campaign bucket ×3 → ×3): uniform
     chaff in a ~10¹⁰ space essentially never lands on any given number,
     and directing enough chaff at every plausible target is infeasible by
     volume. Conclusion: chaff cannot defend against targeted probes;
     that defense rests entirely on token gating, the institutional split,
     and epoch puncturing. Revisit chaff only if aggregate-shape leakage
     becomes a concern.
2. **Equality leakage below threshold:** server sees that *some* tag has j < K
   reports (not its value). Acceptable; disclose in docs.
3. **Poisoning / Sybil:** attacker needs K colluding "installs" to force a
   number in — K is simultaneously the anonymity floor AND the poisoning bar.
   Combine with: Privacy Pass per-install issuance friction, temporal spread
   (require reports across ≥ D days ⇒ across epochs ⇒ multiple OPRF windows),
   allocation-range sanity checks, moderation on outliers, public appeal path
   (PRD §8–§9).
4. **Malicious client garbage:** bad shares/ciphertexts can corrupt a bucket's
   interpolation. Mitigate: interpolate over share subsets (K-of-N robustness),
   validate decryption against the tag re-derivation, drop non-verifying
   reports.
5. **Metadata at the relay:** relay learns "this IP reported something this
   week." Mitigate: batching + random submission delay on-device (reports are
   not urgent — hours of jitter are free privacy), optional Tor.

### 5.6 Why STAR over Poplar/DPF heavy-hitters (for now)

| | STAR | Poplar (IDPF) |
|---|---|---|
| Servers required | 1 aggregator + 1 rand server | 2 interactive non-colluding aggregators |
| Sub-threshold leakage | bucket-size histogram + equality tags | essentially none |
| Maturity / Rust code | **`sta-rs` + `ppoprf` (Brave, production)** | research/IETF-DAP stage, heavier |
| Client cost | one OPRF round + symmetric crypto | DPF key gen, larger reports |

Decision: STAR first — production-grade Rust exists and the leakage is
acceptable *given the dictionary-attack defenses above*. Poplar is the upgrade
path if the leakage profile proves unacceptable; revisit after M3 pilot.

### 5.7 Implementation path (fits our Rust core) — VALIDATED 2026-08-29

Implemented in `crates/opencaller-core/src/star.rs`; simulation in
`src/bin/star_sim.rs` (`cargo run --release --bin star_sim`).

- `sta-rs` 0.3.3 (Brave, Rust) provides the message layer. **Gotcha:** its
  `star2` feature is bit-rotted against `ppoprf 0.5` on crates.io (does not
  compile), so we integrate `ppoprf` directly — better anyway, since we own
  the OPRF client flow where Privacy Pass tokens and OHTTP plug in.
- Full flow working: blind → eval → DLEQ verify → unblind → finalize →
  message generate → tag-bucket → threshold share-recover → decrypt.
- **Measured** (10k-user epoch, 332 reports, K=10, release build):
  ~0.7 ms/report client-side incl. OPRF + proof verify (~51 µs in STARLite
  baseline); 259 B wire message; < 1 ms epoch aggregation. Battery/bandwidth
  are non-issues; the aggregation server is trivially cheap.
- Demonstrated in-sim: 23-report campaign recovered with category counts;
  3-report campaign + 300 one-offs + 6-report poisoning attempt all sealed;
  targeted probe reveals bucket count only; post-puncture retroactive probe
  refused (`NoPrefixFound`).
- OHTTP: Rust `ohttp` crates exist; relay can be a stock OHTTP gateway.
- Aggregation server: small Rust service, stateless between epochs; output
  feeds the existing CI pipeline (PRD §8) unchanged.

### 5.8 Parameters to decide (open)

| Param | Trade-off | Straw-man |
|---|---|---|
| **K** (threshold) | ↑K: anonymity + poisoning bar ↑, detection latency + cold-start pain ↑ | 10 at pilot; revisit per-country |
| **Epoch length** | ↓: fresher DB, more OPRF churn; ↑: bigger linkage window | 1 week (matches DB cadence) |
| **aux payload** | every field revealed at threshold; keep minimal | category + country only |
| **N shares kept per bucket** | robustness vs storage | all reports of epoch |
| Rand-server operator | must be institutionally independent | seek partner (digital-rights org / university) |

### 5.9 Client report lifecycle (phone-side logic flow)

Everything below lives in `opencaller-core`; the Kotlin/Swift shell only
provides UI triggers and the WorkManager/BackgroundTasks scheduling hooks.

```
 user taps "Report spam" on an entry in the app's own screening history
   │        (never the system call log; category picked in the same sheet)
   ▼
 QUEUED       report persisted app-private & encrypted at rest:
   │          {number, category, country, epoch_target, state}
   │          • local dedup: max 1 report per number per epoch
   │          • visible in an "Outbox" screen — user can delete before send
   ▼
 TOKEN_OK     spend-once Privacy Pass token attached
   │          • tokens issued in small monthly batches per install via blind
   │            signature: issuer knows an install got tokens, but a spent
   │            token is unlinkable to issuance
   │          • no tokens left → report waits for next batch (natural
   │            per-user rate cap, no identity involved)
   ▼
 (random jitter: uniform 0–24 h, Wi-Fi + battery constraints)
   │          • decorrelates submission time from call time — the relay/
   │            aggregator cannot match a report to a ringing event
   ▼
 OPRF_DONE    blinded OPRF round for (number ∥ epoch) via OHTTP relay
   │          → derive (key_seed, share_seed, tag)
   ▼
 SUBMITTED    AEAD ciphertext + Shamir share + tag → aggregation server,
   │          separate OHTTP connection, separate jitter
   ▼
 DONE         local record reduced to a tombstone (number hash + epoch) kept
              only for dedup; payload erased
```

**Failure & epoch-boundary rules**
- Retries: exponential backoff within the epoch.
- Epoch rollover before submission: the derived randomness is stale (per-epoch
  OPRF keys). Policy: re-run the OPRF **once** for the new epoch, then drop.
  Reports must not linger — a long-queued report is a growing metadata
  liability, and spam data is perishable anyway.

**Client-side privacy invariants (hard-fail, not degrade)**
1. No submission without the OHTTP relay — never fall back to a direct
   connection.
2. Reports originate only from an explicit user tap (enforced by API shape:
   core exposes `report(screening_history_id, category)`, nothing else).
3. Token supply caps reports per epoch per install.
4. Outbox is user-visible and user-deletable up to the moment of submission.
5. The app never holds the Contacts permission, so reports cannot be
   cross-checked against contacts even by a compromised build (can't-be-evil
   beats won't-be-evil at the permission layer too).

---

## 6. M4 — Pure P2P gossip (parked)

Considered and rejected for active development: Sybil resistance without an
identity anchor is unsolved (one attacker ≙ unlimited fake phones voting a
number in), mobile OSes throttle background networking, and DHT bootstrap
nodes are servers anyway — the trust problem returns, worse. Kept here as a
research note; nothing in M0–M3 precludes a future P2P *distribution* mirror
(torrent/IPFS of the signed DB — that part is safe because the DB is signed).

---

## 7. Cold start, K tuning & rollout triggers

### 7.1 The model
Expected reports for a campaign number in one epoch:

```
reports ≈ U × c × r
  U = active opted-in users in the country
  c = fraction of users the campaign calls during the epoch
  r = report rate among called users (tap-through)
```

Straw-man values: c = 2 % (aggressive weekly campaign), r = 10 %.
`r` is the big unknown — **measuring real r is a primary goal of the M3
shadow pilot** (M1's transparent ledger gives us the ground truth to calibrate
against).

| U (users) | reports/epoch (c=2%, r=10%) | K=5 | K=10 | K=20 |
|---|---|---|---|---|
| 1,000 | ~2 | ✗ | ✗ | ✗ |
| 2,500 | ~5 | ~✓ | ✗ | ✗ |
| 5,000 | ~10 | ✓ | ~✓ | ✗ |
| 10,000 | ~20 | ✓ | ✓ | ~✓ |
| 50,000 | ~100 | ✓ | ✓ | ✓ |

### 7.2 Two effects that soften the cliff
- **Multi-epoch accumulation:** persistent campaigns get repeated chances. If a
  single epoch crosses K with probability p, W weeks give 1−(1−p)^W — a
  campaign that trips K only 30 % of weeks is still caught within a month with
  ~76 % probability. Detection *latency* rises at small U before detection
  *fails*.
- **Per-country K via signed config:** K ships in the signed DB/config bundle
  and can differ per country and per epoch. Floor: **K ≥ 5 always** (below
  that, the anonymity set and the poisoning bar are both too weak). Schedule:
  K=5 at pilot → raise stepwise toward 10–20 as U grows. Raising K is a
  one-line config change; the crypto is unchanged.

### 7.3 Rollout triggers

1. **Launch:** M0 only (public datasets). Fully serverless.
2. **M1 switch-on:** immediately post-launch; moderation compensates while
   reports/week is human-scale.
3. **M3 pilot:** at ≈ 2.5–5k active users in a launch country, run M3 in
   shadow mode with K=5 (collect via both M1 and M3; compare outputs; measure
   real `r`).
4. **M3 primary / M1 retired:** when M3 shadow output ⊇ M1 output for
   consecutive epochs; raise K per §7.2 as U grows.

---

## 8. Threat matrix (summary across mechanisms)

| Threat | M0 | M1 | M3 |
|---|---|---|---|
| Operator learns reporter identity | n/a (no operator) | relay IP (unless Tor/OHTTP) | no (OHTTP relay) |
| Operator learns sub-threshold numbers | n/a | yes (public anyway) | **cryptographically no** |
| Targeted "was X reported?" probe | public data | public data | dictionary attack — defended §5.5.1 |
| Poisoning cost | regulator's problem | publicly visible, moderated | ≥ K authorized installs + D-day spread |
| Infra we must run | none | stateless relay | rand server + aggregator + relay |
| Trust model | government | "watch us" (transparency) | **"can't be evil"** (non-collusion) |

## 9. Decision log

- 2026-08-29 — Approved: layered M0 → M1 → M3; M2 documented as fallback only;
  M4 parked. STAR selected over Poplar for M3 (maturity, Rust ecosystem);
  Poplar noted as upgrade path.
- 2026-08-29 — M3 protocol validated in code (`opencaller-core::star` +
  `star_sim`): full STAR with direct `ppoprf` integration (sta-rs `star2`
  feature bit-rotted upstream). Perf, threshold behavior, probe leakage, and
  puncturing all confirmed; see §5.7. Partner brief for the randomness-server
  operator: [partner-brief.md](partner-brief.md).
- 2026-08-29 — Chaff evaluated in simulation and **deprioritized**: provides
  distributional cover only, does not blur targeted probes (§5.5.1).
- 2026-08-29 — §5.9 client lifecycle implemented (`opencaller-core::lifecycle`);
  offline DB engine implemented and benchmarked (`opencaller-core::db`,
  10M entries: 77 MB, 133 ns miss / 1.5 µs hit, RAM-flat via mmap).
