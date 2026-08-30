import Foundation
import IdentityLookup

/// SMS spam filtering (Android's WARN/MUTE sibling). iOS only routes
/// messages from senders NOT in the user's contacts through this filter;
/// a .junk verdict files them silently under Messages → Junk. Fully
/// offline: the query never leaves the device (no deferral URL is
/// configured), and only the sender field is examined.
final class MessageFilterHandler: ILMessageFilterExtension,
  ILMessageFilterQueryHandling
{
  func handle(
    _ queryRequest: ILMessageFilterQueryRequest,
    context: ILMessageFilterExtensionContext,
    completion: @escaping (ILMessageFilterQueryResponse) -> Void
  ) {
    let response = ILMessageFilterQueryResponse()
    response.action = .none
    defer { completion(response) }

    guard let sender = queryRequest.sender else { return }
    let digits = sender.filter(\.isNumber)

    // User block rules first (exact digits).
    if let n = Int64(digits), Shared.userBlockedNumbers().contains(n) {
      response.action = .junk
      return
    }

    guard digits.count >= 7, let db = Shared.openDb(),
      let hit = db.lookup(digits)
    else { return }
    if hit.category == Shared.smsSpamCategory
      || Shared.blockedCategories().contains(hit.category)
    {
      response.action = .junk
    }
  }
}
