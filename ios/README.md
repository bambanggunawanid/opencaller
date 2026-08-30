# OpenCaller for iOS

The same offline, signed-shard core as Android, adapted to what Apple
allows:

| Android | iOS equivalent |
|---|---|
| CallScreeningService (per-call verdict) | CallKit **Call Directory** — the whole list is pre-loaded; iOS blocks/labels calls itself |
| Silence / block per category | iOS has no silence: **label** (always, every reported number) or **block** (per category, Settings tab) |
| On-screen badge + notifications | The label appears natively on the incoming-call screen |
| SMS warn/mute via notification access | **ILMessageFilter** extension: reported SMS-spam senders go to Messages → Junk, silently |
| WhatsApp call warnings | Not possible on iOS (no notification-listener equivalent) |
| Prefix spam-blocks, heuristics | Not in v1 (the directory takes exact numbers only) |

Everything stays offline: shards download from GitHub releases, verify
against the pinned Ed25519 key on-device, and no query ever leaves the
phone (the SMS filter deliberately configures no network deferral).

## Building

CI (`.github/workflows/build-ios.yml`) builds an **unsigned IPA** on a
macOS runner. Locally on a Mac:

```sh
bash scripts/build-ios-rust.sh   # Rust static lib + seed resources
cd ios && brew install xcodegen && xcodegen generate
open OpenCaller.xcodeproj        # set your team, run on device
```

## Installing the unsigned IPA

Unsigned builds can't be tapped-to-install. Use AltStore or Sideloadly
with a free Apple ID — both re-sign the IPA on install (7-day validity
on free accounts; the tools auto-refresh). App Group support may need
the tool's "app groups" option enabled.

After installing: open the app once (it seeds and syncs the database),
then enable it in **Settings → Phone → Call Blocking & Identification**
and **Settings → Messages → Unknown & Spam**.
