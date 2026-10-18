# Where this bridge client comes from

Ported from the SUPER DESKTOP Android app, `~/GitHub/OmarchyAILauncher`
(`android/app/src/main/java/com/omarchy/ailauncher/`), at commit `98b6129`.

| Here | There | How |
|---|---|---|
| `BridgeTls.kt` | `BridgeTls.kt` | copied, without the demo transport |
| `HarnessFeedPolicy.kt` | `HarnessFeed.kt` | copied |
| `Invitation.kt` | `PairingLink.kt` | `decodePairingInvitation` copied, `Invitation` added |
| `BridgeApi.kt` | `HarnessApi.kt` | the calls a home page needs: ping, pairing, harnesses, keys, create, close; no Pro gating, images, assets or editor actions |
| `Model.kt` | `HarnessApi.kt`, `BridgeConnection.kt`, `UsageHeader.kt` | slimmer models and parsers of the same JSON |

Protocol: `PROTOCOL.md` in that repository (wire v3). When the app's client
changes, diff it against that commit and bring the changes here.
