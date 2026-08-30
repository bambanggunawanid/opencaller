//! F7: data-free call heuristics (PRD §6). These run when the DB misses,
//! need no dataset at all, and therefore work identically in every country
//! — the mitigation for thin data coverage outside launch regions
//! (PRD §13 risk 1). Pure functions over the dialed digits; nothing here
//! touches state, clocks, or the network.

/// Why a number looks suspicious without any database hit.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Suspicion {
  /// Caller ID equals the user's own number — a classic spoof; a real call
  /// from your own number cannot ring your phone.
  OwnNumberSpoof,
  /// Same length and identical except the last 4 digits of the user's own
  /// number ("neighbor spoofing": spammers mimic your prefix to look local).
  NeighborSpoof,
  /// Structurally invalid under numbering-plan rules (currently NANP):
  /// impossible area/exchange codes or the fictional 555-01xx range.
  InvalidNumber,
  /// Too few digits to be a routable number.
  TooShort,
}

impl Suspicion {
  pub fn label(&self) -> &'static str {
    match self {
      Self::OwnNumberSpoof => "own-number-spoof",
      Self::NeighborSpoof => "neighbor-spoof",
      Self::InvalidNumber => "invalid-number",
      Self::TooShort => "too-short",
    }
  }
}

fn digits_of(s: &str) -> String {
  s.chars().filter(|c| c.is_ascii_digit()).collect()
}

/// Evaluate a caller number, optionally against the user's own number
/// (user-entered in settings; never read from the SIM without consent).
/// Inputs may be in any dialable format; only digits are considered.
/// Returns `None` for numbers with no structural red flags.
pub fn evaluate(caller: &str, own_number: Option<&str>) -> Option<Suspicion> {
  let caller = digits_of(caller);
  if caller.is_empty() || caller.len() > 15 {
    return Some(Suspicion::InvalidNumber);
  }
  if caller.len() < 7 {
    return Some(Suspicion::TooShort);
  }

  if let Some(own) = own_number.map(digits_of).filter(|o| o.len() >= 10) {
    if caller == own {
      return Some(Suspicion::OwnNumberSpoof);
    }
    if caller.len() == own.len() && caller[..caller.len() - 4] == own[..own.len() - 4] {
      return Some(Suspicion::NeighborSpoof);
    }
  }

  // NANP structural rules (11 digits, country code 1). Other numbering
  // plans get their rules alongside their DB shards (F3).
  if caller.len() == 11 && caller.starts_with('1') {
    let b = caller.as_bytes();
    let (area, exchange, line) = (&b[1..4], &b[4..7], &b[7..11]);
    let invalid = area[0] == b'0'
      || area[0] == b'1'
      || (area[1] == b'1' && area[2] == b'1') // N11 service codes
      || exchange[0] == b'0'
      || exchange[0] == b'1'
      || (exchange == b"555" && &line[..2] == b"01"); // fictional range
    if invalid {
      return Some(Suspicion::InvalidNumber);
    }
  }

  None
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn own_and_neighbor_spoofing() {
    let own = Some("+1 (828) 300-3919");
    assert_eq!(evaluate("18283003919", own), Some(Suspicion::OwnNumberSpoof));
    assert_eq!(evaluate("+1 828 300 1234", own), Some(Suspicion::NeighborSpoof));
    // Different exchange: not a neighbor.
    assert_eq!(evaluate("+1 828 555 3919", own), None);
    // No own number configured: no spoof heuristics.
    assert_eq!(evaluate("18283003919", None), None);
    // Own number too short to trust: ignored.
    assert_eq!(evaluate("1234567", Some("1234567")), None);
  }

  #[test]
  fn nanp_structure() {
    assert_eq!(evaluate("+1 055 123 4567", None), Some(Suspicion::InvalidNumber)); // 0xx area
    assert_eq!(evaluate("+1 911 123 4567", None), Some(Suspicion::InvalidNumber)); // N11 area
    assert_eq!(evaluate("+1 828 055 1234", None), Some(Suspicion::InvalidNumber)); // 0xx exchange
    assert_eq!(evaluate("+1 828 555 0142", None), Some(Suspicion::InvalidNumber)); // 555-01xx
    assert_eq!(evaluate("+1 828 555 2368", None), None); // 555 outside 01xx is real
    assert_eq!(evaluate("+1 828 300 3919", None), None); // clean
  }

  #[test]
  fn length_rules() {
    assert_eq!(evaluate("12345", None), Some(Suspicion::TooShort));
    assert_eq!(evaluate("", None), Some(Suspicion::InvalidNumber));
    assert_eq!(evaluate("1234567890123456", None), Some(Suspicion::InvalidNumber));
    // Non-NANP lengths pass structural checks (rules ship with shards).
    assert_eq!(evaluate("+62 812 3456 789", None), None);
  }
}
