//! §5.9 client report lifecycle: QUEUED → (token, jitter) → OPRF → SUBMIT →
//! tombstone. Platform-agnostic and side-effect-free by construction: the
//! shell (WorkManager / BGTaskScheduler) calls [`Outbox::tick`] with the
//! current time; transport and randomness access are injected per call.
//!
//! Privacy invariants enforced here, per docs/collection-mechanisms.md §5.9:
//! 1. The ONLY exit path is the injected [`Transport`] (the OHTTP relay in
//!    production) — there is no direct-connection fallback to fall back to.
//! 2. Reports exist only through [`Outbox::enqueue`], which the shell calls
//!    from an explicit user tap. Nothing in this module creates reports.
//! 3. Spend-once tokens cap reports per install per epoch; no token, no
//!    submission — the report just waits for the next refill.
//! 4. Random 0–24 h jitter decorrelates submission time from call time.
//! 5. After submission (or drop) the payload is erased; only a
//!    `(number_hash, epoch)` tombstone survives for dedup.
//! 6. A report that misses its epoch re-derives randomness once for the new
//!    epoch, then is dropped — reports never linger.

use std::collections::HashSet;

use rand::Rng;

use crate::db::parse_number;
use crate::star::{create_report_message, RandomnessMode, Report};

pub const MAX_JITTER_SECS: u64 = 24 * 60 * 60;
pub const MAX_SUBMIT_ATTEMPTS: u32 = 5;
const BACKOFF_BASE_SECS: u64 = 60;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DropReason {
  /// Missed two epochs (queued in one, still unsent when the next closed).
  EpochMissed,
  /// Transport/OPRF kept failing past `MAX_SUBMIT_ATTEMPTS`.
  SubmitFailed,
}

#[derive(Debug, PartialEq, Eq)]
pub enum EnqueueError {
  /// Same number already queued or already reported this epoch.
  Duplicate,
  /// Not a parseable E.164 number.
  BadNumber,
}

/// One pending report, visible in the user-facing Outbox screen until sent.
#[derive(Debug)]
pub struct QueuedReport {
  pub id: u64,
  pub report: Report,
  pub epoch: String,
  /// Jittered earliest submission time (unix secs).
  pub scheduled_at: u64,
  pub attempts: u32,
  next_attempt_at: u64,
  oprf_retried: bool,
}

/// The production implementation is the OHTTP relay client. Being the only
/// egress makes invariant 1 structural: code that never sees a socket cannot
/// leak around the relay.
pub trait Transport {
  fn submit(&mut self, wire: &[u8]) -> Result<(), String>;
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct OutboxStats {
  pub submitted: u64,
  pub dropped_epoch_missed: u64,
  pub dropped_submit_failed: u64,
  pub cancelled: u64,
  pub deduplicated: u64,
  /// Ticks where at least one due report waited because tokens ran out.
  pub token_starved_ticks: u64,
}

pub struct Outbox {
  next_id: u64,
  tokens: u32,
  queue: Vec<QueuedReport>,
  /// (splitmix64(number), epoch) — all that survives a sent/dropped report.
  tombstones: HashSet<(u64, String)>,
  pub stats: OutboxStats,
}

fn number_hash(report: &Report) -> Option<u64> {
  parse_number(&report.number).map(crate::db::splitmix64)
}

impl Outbox {
  pub fn new(initial_tokens: u32) -> Self {
    Self {
      next_id: 1,
      tokens: initial_tokens,
      queue: Vec::new(),
      tombstones: HashSet::new(),
      stats: OutboxStats::default(),
    }
  }

  /// Monthly Privacy Pass batch arrival.
  pub fn refill_tokens(&mut self, n: u32) {
    self.tokens = self.tokens.saturating_add(n);
  }

  pub fn tokens(&self) -> u32 {
    self.tokens
  }

  /// The Outbox screen: pending reports, inspectable and cancellable.
  pub fn pending(&self) -> &[QueuedReport] {
    &self.queue
  }

  /// Called from the explicit user tap (invariant 2).
  pub fn enqueue(
    &mut self,
    report: Report,
    epoch: &str,
    now: u64,
    rng: &mut impl Rng,
  ) -> Result<u64, EnqueueError> {
    let Some(hash) = number_hash(&report) else {
      return Err(EnqueueError::BadNumber);
    };
    let dup_in_queue = self
      .queue
      .iter()
      .any(|q| q.epoch == epoch && q.report.number == report.number);
    if dup_in_queue || self.tombstones.contains(&(hash, epoch.to_owned())) {
      self.stats.deduplicated += 1;
      return Err(EnqueueError::Duplicate);
    }
    let id = self.next_id;
    self.next_id += 1;
    let scheduled_at = now + rng.gen_range(0..MAX_JITTER_SECS);
    self.queue.push(QueuedReport {
      id,
      report,
      epoch: epoch.to_owned(),
      scheduled_at,
      attempts: 0,
      next_attempt_at: 0,
      oprf_retried: false,
    });
    Ok(id)
  }

  /// User deletes a pending report (possible right up until submission).
  pub fn cancel(&mut self, id: u64) -> bool {
    let before = self.queue.len();
    self.queue.retain(|q| q.id != id);
    let removed = self.queue.len() != before;
    if removed {
      self.stats.cancelled += 1;
    }
    removed
  }

  /// Epoch boundary: re-target unsent reports once (fresh jitter, fresh
  /// OPRF next tick), drop them the second time (invariant 6).
  pub fn epoch_rollover(
    &mut self,
    new_epoch: &str,
    now: u64,
    rng: &mut impl Rng,
  ) {
    let mut dropped: Vec<(u64, String)> = Vec::new();
    for q in &mut self.queue {
      if q.epoch == new_epoch {
        continue;
      }
      if q.oprf_retried {
        if let Some(h) = number_hash(&q.report) {
          dropped.push((h, q.epoch.clone()));
        }
        q.attempts = u32::MAX; // mark for removal below
        continue;
      }
      q.epoch = new_epoch.to_owned();
      q.oprf_retried = true;
      q.scheduled_at = now + rng.gen_range(0..MAX_JITTER_SECS);
      q.attempts = 0;
      q.next_attempt_at = 0;
    }
    let n_dropped = dropped.len() as u64;
    self.stats.dropped_epoch_missed += n_dropped;
    self.tombstones.extend(dropped);
    self.queue.retain(|q| q.attempts != u32::MAX);
  }

  /// Process everything due at `now`. Call from the background scheduler.
  pub fn tick(
    &mut self,
    now: u64,
    k: u32,
    mode: &RandomnessMode,
    transport: &mut impl Transport,
  ) {
    let mut token_starved = false;
    let mut finished: Vec<(u64, Option<(u64, String)>)> = Vec::new(); // (id, tombstone)

    for q in &mut self.queue {
      if q.scheduled_at > now || q.next_attempt_at > now {
        continue;
      }
      if self.tokens == 0 {
        token_starved = true;
        continue; // invariant 3: wait for refill, never bypass
      }

      // OPRF + message generation + relay submission count as one attempt;
      // the token is only spent on success (a failed relay call spends
      // nothing at the issuer).
      let outcome = create_report_message(&q.report, k, q.epoch.as_bytes(), mode)
        .map_err(|e| e.to_string())
        .and_then(|wire| transport.submit(&wire));

      match outcome {
        Ok(()) => {
          self.tokens -= 1;
          self.stats.submitted += 1;
          finished.push((q.id, number_hash(&q.report).map(|h| (h, q.epoch.clone()))));
        }
        Err(_) => {
          q.attempts += 1;
          if q.attempts >= MAX_SUBMIT_ATTEMPTS {
            self.stats.dropped_submit_failed += 1;
            finished.push((q.id, number_hash(&q.report).map(|h| (h, q.epoch.clone()))));
          } else {
            q.next_attempt_at =
              now + BACKOFF_BASE_SECS * (1 << q.attempts.min(10));
          }
        }
      }
    }

    if token_starved {
      self.stats.token_starved_ticks += 1;
    }
    // Invariant 5: payload erased (report dropped from queue), tombstone kept.
    for (id, tomb) in finished {
      self.queue.retain(|q| q.id != id);
      if let Some(t) = tomb {
        self.tombstones.insert(t);
      }
    }
  }
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::star::Category;
  use rand::rngs::StdRng;
  use rand::SeedableRng;

  const K: u32 = 3;
  const EPOCH: &str = "2026-W35";

  fn report(number: &str) -> Report {
    Report { number: number.into(), category: Category::Scam, country: "US".into() }
  }

  #[derive(Default)]
  struct MockTransport {
    sent: Vec<Vec<u8>>,
    fail: bool,
  }
  impl Transport for MockTransport {
    fn submit(&mut self, wire: &[u8]) -> Result<(), String> {
      if self.fail {
        return Err("relay unreachable".into());
      }
      self.sent.push(wire.to_vec());
      Ok(())
    }
  }

  fn drain(outbox: &mut Outbox, transport: &mut MockTransport, from: u64) -> u64 {
    // Advance time past jitter + all backoffs.
    let mut now = from + MAX_JITTER_SECS;
    for _ in 0..MAX_SUBMIT_ATTEMPTS + 1 {
      outbox.tick(now, K, &RandomnessMode::Local, transport);
      now += MAX_JITTER_SECS; // larger than any backoff
    }
    now
  }

  #[test]
  fn happy_path_submits_and_tombstones() {
    let mut rng = StdRng::seed_from_u64(1);
    let mut outbox = Outbox::new(10);
    let mut transport = MockTransport::default();

    let id = outbox.enqueue(report("+15551234567"), EPOCH, 1_000, &mut rng).unwrap();
    assert_eq!(outbox.pending().len(), 1);
    let q = &outbox.pending()[0];
    assert_eq!(q.id, id);
    assert!(q.scheduled_at >= 1_000 && q.scheduled_at < 1_000 + MAX_JITTER_SECS);

    // Before the jittered time: nothing happens.
    outbox.tick(999, K, &RandomnessMode::Local, &mut transport);
    assert!(transport.sent.is_empty());

    drain(&mut outbox, &mut transport, 1_000);
    assert_eq!(transport.sent.len(), 1);
    assert!(outbox.pending().is_empty());
    assert_eq!(outbox.stats.submitted, 1);
    assert_eq!(outbox.tokens(), 9);

    // Tombstone blocks re-reporting the same number this epoch...
    assert_eq!(
      outbox.enqueue(report("+15551234567"), EPOCH, 2_000, &mut rng),
      Err(EnqueueError::Duplicate)
    );
    // ...but a new epoch is fine.
    assert!(outbox.enqueue(report("+15551234567"), "2026-W36", 2_000, &mut rng).is_ok());
  }

  #[test]
  fn dedup_within_queue_and_bad_numbers() {
    let mut rng = StdRng::seed_from_u64(2);
    let mut outbox = Outbox::new(10);
    outbox.enqueue(report("+15551234567"), EPOCH, 0, &mut rng).unwrap();
    assert_eq!(
      outbox.enqueue(report("+15551234567"), EPOCH, 0, &mut rng),
      Err(EnqueueError::Duplicate)
    );
    assert_eq!(
      outbox.enqueue(report("not-a-number"), EPOCH, 0, &mut rng),
      Err(EnqueueError::BadNumber)
    );
    assert_eq!(outbox.stats.deduplicated, 1);
  }

  #[test]
  fn token_exhaustion_blocks_until_refill() {
    let mut rng = StdRng::seed_from_u64(3);
    let mut outbox = Outbox::new(0);
    let mut transport = MockTransport::default();
    outbox.enqueue(report("+15551234567"), EPOCH, 0, &mut rng).unwrap();

    drain(&mut outbox, &mut transport, 0);
    assert!(transport.sent.is_empty());
    assert_eq!(outbox.pending().len(), 1);
    assert!(outbox.stats.token_starved_ticks > 0);

    outbox.refill_tokens(1);
    drain(&mut outbox, &mut transport, MAX_JITTER_SECS * 10);
    assert_eq!(transport.sent.len(), 1);
    assert_eq!(outbox.tokens(), 0);
  }

  #[test]
  fn transport_failure_backs_off_then_drops() {
    let mut rng = StdRng::seed_from_u64(4);
    let mut outbox = Outbox::new(10);
    let mut transport = MockTransport { fail: true, ..Default::default() };
    outbox.enqueue(report("+15551234567"), EPOCH, 0, &mut rng).unwrap();

    // First failing attempt sets a backoff — an immediate retry is a no-op.
    outbox.tick(MAX_JITTER_SECS, K, &RandomnessMode::Local, &mut transport);
    assert_eq!(outbox.pending()[0].attempts, 1);
    outbox.tick(MAX_JITTER_SECS + 1, K, &RandomnessMode::Local, &mut transport);
    assert_eq!(outbox.pending()[0].attempts, 1);

    drain(&mut outbox, &mut transport, 0);
    assert!(outbox.pending().is_empty());
    assert_eq!(outbox.stats.dropped_submit_failed, 1);
    assert_eq!(outbox.tokens(), 10, "failed submissions must not burn tokens");
  }

  #[test]
  fn epoch_rollover_retries_once_then_drops() {
    let mut rng = StdRng::seed_from_u64(5);
    let mut outbox = Outbox::new(10);
    outbox.enqueue(report("+15551234567"), EPOCH, 0, &mut rng).unwrap();

    outbox.epoch_rollover("2026-W36", 100, &mut rng);
    assert_eq!(outbox.pending().len(), 1);
    assert_eq!(outbox.pending()[0].epoch, "2026-W36");

    outbox.epoch_rollover("2026-W37", 200, &mut rng);
    assert!(outbox.pending().is_empty());
    assert_eq!(outbox.stats.dropped_epoch_missed, 1);
  }

  #[test]
  fn cancel_removes_pending() {
    let mut rng = StdRng::seed_from_u64(6);
    let mut outbox = Outbox::new(10);
    let id = outbox.enqueue(report("+15551234567"), EPOCH, 0, &mut rng).unwrap();
    assert!(outbox.cancel(id));
    assert!(!outbox.cancel(id));
    assert!(outbox.pending().is_empty());
    assert_eq!(outbox.stats.cancelled, 1);
  }
}
