# SUPER DESKTOP in Omalauncher: implementation plan

Design and reasoning: [DESIGN.md](DESIGN.md). This file is the build order:
each step lists the files, what to do, and how to know it is done.

Branches on `origin` (gladimdim/omalauncher):
- `main`: a mirror of `upstream/main` (SimonSchubert/omalauncher). Never commit here.
- `superdesktop`: the fork. First commit is the hook, everything after it is fork-only files.
- `archive/gesture-navigation`: the gesture navigation and recents work that
  was on `main` before the reset (4 commits, closed PR #19). Kept, not used.

Source of the bridge client: `~/GitHub/OmarchyAILauncher/android/app/src/main/java/com/omarchy/ailauncher/`
(called **SD** below). Bridge protocol: `~/GitHub/OmarchyAILauncher/PROTOCOL.md` (wire v3).

## Status

- [x] Phase 0: fork builds; `com.gladimdim.sdlauncher`, named "SD Launcher" (via
  `app/src/{debug,release}/AndroidManifest.xml` overlays, no main manifest edit);
  `scripts/sd-sync.sh`.
- [x] Phase 1: hook, `:superdesktop` module, placeholder tile, checked on a
  Xiaomi 2602BPC18G (Android 16). Findings below.
- [x] 5.1 guard test (`SdHookTest`), done early.
- [x] Phase 2: bridge client, encrypted store, pairing, live feed. Paired with
  gladimdim-rog (SUPER DESKTOP 1.7.1) on 2026-10-18; the page shows its
  harnesses and usage live, and a tap opens the harness in the SUPER DESKTOP app.
- [x] Phase 3: per-page settings (computers, every harness or one), new pages,
  start and close harnesses, one-harness view with a prompt. Checked on the
  phone against gladimdim-rog: started a Terminal, pinned a page to it, sent a
  command, closed it after the in-card confirmation.
- [ ] Phase 4 next: the live terminal in the tile.

### Phase 3 notes

- Settings are an overlay inside the tile (`Panels.kt`), not a new screen, so the
  page stays on the home screen. They're stored per `id` in plain preferences
  (`SdPages`); a page always shows at least one computer, and "all" is stored as
  null so computers paired later show up too.
- "＋ new SUPER DESKTOP page" writes `superdesktop-<id>.owl` asking for page 2;
  the launcher moves it to a new last page and **jumps to it**, rebuilding the
  tile that asked. So the new page carries the note ("⚙ picks its computers and
  harness"), kept in a process-wide map for five seconds.
- Closing asks inside the card first (red frame, "keep" / "close").
- Actions go to the address the live feed reached last (`SdLive.api`).
- The one-harness view still shows the preview text; phase 4 swaps in the
  terminal. Its prompt goes through `POST /keys` with Enter, and lands on top of
  whatever is already typed on that terminal's command line.

### Phase 2 notes

- The client is a lean port, not a copy of `HarnessApi.kt`: the app's version is
  tied to Pro gating, the demo transport, images and assets. What came from where
  is in `superdesktop/.../bridge/SOURCE.md` (SD commit `98b6129`).
- `PairingActivity` takes `superdesktop://pair` links (VIEW intent, so the
  phone camera's QR scan can open it; Android asks which app when the SUPER
  DESKTOP app is installed too) or a pasted link or JSON. A link only fills the
  field: pairing starts on "connect". The launcher pairs as its own device,
  "SD Launcher · <phone>".
- Approving from the PC's CLI needs `super-desktop connection approve ID --code
  CODE --allow-access --request-id ID`; the desktop's own prompt works too.
- `SdLive` streams `/harnesses/stream` per computer, polls while it's down, moves
  to the computer's other addresses (Tailscale next) when one stops answering,
  and closes 10 s after the last page leaves.
- The tile's colors are also kept in `SdTheme` for the pairing screen, which has
  no launcher host; it shows Tokyo Night until a tile has drawn.
- On a fresh install the SD page takes page 2 and the launcher's default egg
  moves to page 3.

### Phase 1 findings (2026-10-18)

- **Adding a page**: the launcher has no gallery for bundled examples (long-press
  on home offers "create with an agent", the store, themes and settings). So the
  page ships as a default widget, `assets/widgets/90-superdesktop.owl`
  (`id: "home"`). Defaults are written on every start, existing installs too,
  and never come back once deleted. More pages come from the tile's own
  "＋ New page" (3.4).
- **Placement**: a file asking for a full page (`page: 2`) goes to the next page
  with room. `9x99` is clamped to the grid: 357×606 dp on this phone, all grid
  rows. The space under it is the launcher's recent-apps strip.
- **Gestures**: horizontal swipes over the tile turn pages both ways; vertical
  scroll inside works; nothing in the tile fights long-press.
- **Keyboard**: the `TileField` handover works. The tile gets the accent focus
  border and shrinks above the keyboard (606 → 436 dp); Back releases it.
- **Lifecycle**: only the **current** page is composed. A neighbouring page is
  disposed as soon as you leave it. So live connections follow the visible
  page, with a grace period (2.4).
- **Release build**: R8-minified release runs and draws the tile.
- **Not checked**: landscape and unfolded foldables (no foldable at hand).

## Facts the plan relies on

Checked against upstream `06d4b48` (Release 0.8.1):

- Build: AGP 9.4.1, Kotlin 2.4.20, Compose Multiplatform 1.12.1 artifacts
  (`org.jetbrains.compose.*`), no Material. The root `build.gradle.kts`
  applies `com.android.application` with `apply false`, so a module can use
  `id("com.android.library")` with no version.
- Release builds minify (`isMinifyEnabled = true`).
- OWL elements are a map in `owl/src/main/kotlin/dev/omalauncher/widgets/lang/Schema.kt:22`
  (`KINDS`), drawn by `when (el.kind)` in `Element()`,
  `app/src/main/kotlin/dev/omalauncher/widgets/render/Render.kt:255`.
  `Terminal` (`Render.kt:427`) is the precedent: a native view in a tile.
- `ExamplesTest` compiles every `.owl` file under `assets/widgets`.
  `readmeNamesEveryBuiltinAndAction` checks builtins and actions only, not
  element kinds, so the README needs no edit.
- Text fields on tiles hand the keyboard over through `LocalHomeTiles` and
  `TileField` (`ui/TerminalTiles.kt:127-138, 216`; usage in
  `widgets/render/Inputs.kt:168-188`).
- The theme is `Palette.current` (`ui/Theme.kt:67`), a snapshot state, so
  reading it in composition follows theme changes.
- The widgets folder is `LauncherData.widgetsDir(context)` = `filesDir/widgets`,
  watched by the engine.
- Placement clamps a tile's height to the page's rows
  (`widgets/WidgetLayout.kt:195`).
- SD's bridge client: `HarnessApi.kt` (683 lines, OkHttp, org.json,
  `BridgePrefs` at :71-186 uses EncryptedSharedPreferences), `BridgeTls.kt`,
  `BridgeConnection.kt`, `TerminalInput.kt`, `PairingLink.kt`,
  `HarnessFeed.kt`, `CompletionState.kt`, `BridgeDiscovery.kt` (NSD),
  `TerminalOutputView.kt` (700 lines, plain Android View). SD depends on
  okhttp 4.12.0, security-crypto 1.1.0-alpha06 and coroutines-android 1.8.1.

---

## Phase 0: fork setup (0.5 d) ✓

**0.1 Build the untouched fork.**
`./scripts/fetch-proot.sh && ./gradlew :app:assembleDebug :owl:test :app:testDebugUnitTest`.
Done when: everything passes on `superdesktop` before any change, so later
failures are ours.

**0.2 Install next to the Play version.**
In `app/build.gradle.kts`, `defaultConfig`: `applicationId = "com.gladimdim.sdlauncher"`.
Keep `namespace = "dev.omalauncher"`. Label: add a `resValue` or a fork-only
`res/values/sd_strings.xml` override of the app name, e.g. "Omalauncher SD",
whichever doesn't need a manifest edit (check how the manifest's label is
set first).
Done when: the debug APK installs beside the Play app and both can be
picked as the home app.

**0.3 Sync script.** Create `scripts/sd-sync.sh` (fork-only):
```sh
git fetch upstream
git push origin upstream/main:main
git rebase upstream/main superdesktop
./gradlew :owl:test :app:testDebugUnitTest :superdesktop:testDebugUnitTest :app:assembleDebug
```
Done when: it runs clean on the empty fork.

## Phase 1: hook and spike (0.5–1 d) ✓

**1.1 Module skeleton.** `settings.gradle.kts`: `include(":superdesktop")`.
`superdesktop/build.gradle.kts`:
- plugins `id("com.android.library")` and `alias(libs.plugins.kotlin.compose)`;
- `namespace = "dev.gladimdim.superdesktop"`, compileSdk and minSdk the same as `app`;
- dependencies: compose runtime, foundation and ui from `libs` (shared with
  the app, so upgrades come with upstream); okhttp 4.12.0, security-crypto,
  coroutines-android and zxing as literal coordinates in this file only.

`app/build.gradle.kts`: `implementation(project(":superdesktop"))`.

**1.2 The module's public API**, `superdesktop/src/main/kotlin/.../SdHost.kt`:
```kotlin
class SdColors(val background: Color, val foreground: Color, val accent: Color,
               val muted: Color, val surface: Color, val border: Color,
               val ansi: List<Color> /* color0..15 */)

interface SdHost {
    val colors: SdColors            // read in composition, follows the theme
    val monoFont: FontFamily?
    val widgetsDir: File            // for "new page"
    fun fieldFocused(release: () -> Unit)   // -> LocalHomeTiles.focused(TileField(...))
    fun fieldUnfocused()
}

@Composable fun SuperDesktopTile(id: String?, host: SdHost, modifier: Modifier)
```

**1.3 The hook (the only commit that edits upstream files).**
- `Schema.kt`, first entry of `KINDS`:
  `"SuperDesktop" to Kind(setOf("id"), Children.NONE),`
- `Render.kt`, first branch of `when (el.kind)`:
  `"SuperDesktop" -> SdElement(p, inst, mod)`
- New `app/src/main/kotlin/dev/omalauncher/widgets/render/SdHook.kt`:
  `SdElement` builds the `SdHost` from `Palette.current` (mapping the theme's
  keys to `SdColors`), `LocalHomeTiles.current`, `LocalContext.current` and
  `LauncherData.widgetsDir`, then calls `SuperDesktopTile(p.observe("id")?.toString(), host, mod.fillMaxSize())`.
- Commit message: `fork: SUPER DESKTOP hook`.

**1.4 Spike tile.** `SuperDesktopTile` draws a placeholder: the id, the
tile size, a scrollable column of 50 rows in theme colors, one text field.
Shipped as the default widget `app/src/main/assets/widgets/90-superdesktop.owl` (see findings):
```
widget "SUPER DESKTOP" {
  meta { owl: 9; description: "SUPER DESKTOP harnesses on a home page" }
  size: 9x99
  padding: 0
  SuperDesktop { id: "example" }
}
```
(Check the exact `meta` fields `ExamplesTest` needs, and whether examples need
`version`, `license` or `category`.)

**1.5 Verify, and write down the answers in DESIGN.md:**
- [x] How the user adds an example to home (the gallery flow), and whether adding it twice works.
- [x] `9x99` fills the page in portrait, landscape and on an unfolded foldable, and doesn't push extra rows onto the page. If it does, find the real clamp and pick the size from `cols`/`rows`.
- [x] Horizontal swipes over the tile still turn pages; vertical scroll works.
- [x] The text field gets the keyboard, the tile lifts above it, and tapping outside releases it.
- [ ] The theme changes live. (Not tried yet; colors are read from `Palette.current`, which is snapshot state.)
- [x] Which neighbouring pages stay composed (log `DisposableEffect` enter and leave per id), to size the connection lifetime.
- [x] A release build with R8 runs.

## Phase 2: bridge client and pairing (1 d) ✓

**2.1 Copy the client** into `superdesktop/src/main/kotlin/dev/gladimdim/superdesktop/bridge/`:
`HarnessApi.kt` (without `BridgePrefs`), `BridgeTls.kt`, `BridgeConnection.kt`,
`TerminalInput.kt`, `PairingLink.kt`, `HarnessFeed.kt`, `CompletionState.kt`,
`BridgeDiscovery.kt`. Change only the package. Record the SD commit copied
from in `bridge/SOURCE.md`, so later diffs against SD are easy.

**2.2 Storage**, `store/SdStore.kt`, built from `BridgePrefs`:
- PCs: id, name, addresses, pinned fingerprint, token. Encrypted, in
  EncryptedSharedPreferences file `sd_bridges_v1`.
- Page config, plain SharedPreferences `sd_pages`, keyed by element id:
  `{machines:[bridgeId…] | "all", mode:"overview"|"pinned"|"split", harness:"sd_term_…"?}`.
- Tests: config round trip; an unknown id gives the default (all PCs, overview).

**2.3 Pairing**, `PairingActivity` in the module's manifest (merged into the
app's, so no manifest edit). It handles:
- pasting a `superdesktop://pair?data=…` link or scanning a QR with ZXing core and the camera;
- `POST /api/v1/pair` with `deviceName = "Omalauncher on <model>"` and `deviceType = "phone"`;
- showing the 6-digit code;
- polling `/api/v1/pair/poll` once a second;
- saving the PC.

Errors: expired invite, denied, fingerprint mismatch, unreachable (with the
local-network hint on Android 17+).
Done when: the PC lists the launcher as its own device, and revoking it there
makes the tile show "not paired".

**2.4 Live data**, `live/SdLive.kt`, a process-wide object:
- `fun harnesses(bridgeId): StateFlow<HarnessDocument?>`, reference counted by
  the tiles collecting it;
- while anything collects, one `WS /api/v1/harnesses/stream` per PC; on
  failure, poll `GET /api/v1/harnesses` every 5 s with SD's back-off;
- close all sockets 10 s after the last collector leaves or on `ON_STOP`.
  Only the visible page is composed, so the grace period is what keeps a quick
  swipe away and back from reconnecting;
- reuse one OkHttp client per pinned PC.

Tests: the parser against a captured document (save one from a real bridge
into `src/test/resources`), and the ref counting.

## Phase 3: overview page (1.5–2 d) ✓

**3.1 `ui/OverviewPane.kt`**:
- a usage strip: one compact bar per provider from `usage[]`;
- per PC, a header (name, online dot, ＋) and harness cards: agent icon,
  name, status chip, model · effort, folder, last prompt, a 3-line preview in
  the mono font;
- a `LazyVerticalGrid` with adaptive columns, so one column in a 6-column
  portrait tile and two or three when wide;
- empty states: no PCs paired (a "Pair a PC" button), PC offline, no harnesses.

**3.2 Status colors** from `SdColors`: WORKING is accent, WAITING is yellow
(color3), ERROR is red (color1), IDLE and FINISHED are muted. No hard-coded colors.

**3.3 Agent icons**: copy SD's harness icons into module resources, keyed by `agentType`.

**3.4 Per-page settings**, `ui/PageSettings.kt`: an overlay inside the tile
(not a new activity, so it stays "on the home screen"), opened by a ⚙ in the
tile's corner. It holds:
- PC checkboxes;
- the mode (overview, pinned, split);
- for pinned, a harness picker;
- "Pair another PC";
- "＋ New SUPER DESKTOP page", which writes `widgetsDir/superdesktop-<6 hex>.owl`
  (the example with a new id, and `page:` set to the next free page).

Confirm in Phase 1 how a new file gets placed, since `page:` only applies to
widgets you add yourself.

**3.5 Actions**:
- ＋ opens a create sheet: harness types from `GET /api/v1/harness-types`,
  folder from `GET /api/v1/workspaces`, then `POST /api/v1/harnesses`;
- close via `DELETE /api/v1/harnesses/<id>`, behind a confirm;
- "Open in app": an explicit intent to `com.gladimdim.superdesktop` with the
  `OPEN_TERMINAL` extras (see SD `MainActivity.kt:977-1025`). Hide it when the
  app isn't installed.

**3.6 Gestures**: no long-press handlers anywhere in the tile; cards react to taps only.

Done when: two pages with different PCs show live status, survive a launcher
restart, and follow a theme change.

## Phase 4: terminal in the tile (2–3 d)

**4.1 Output.** Wrap SD's `TerminalOutputView` in `AndroidView`. Feed it from
`WS /api/v1/harnesses/<id>/stream?ansiOnly=1&viewport=1` (`tailAnsi`). Port
SD's ANSI-to-spans code (find where `Fragments.kt` or `HarnessPageFragment.kt`
builds the spans). Colors come from `SdColors.ansi`.

**4.2 Focus model**, as on the Terminal tile:
- inert until tapped, so swipes turn pages;
- after a tap, it takes horizontal pans and the keyboard, and shows a thin accent border;
- tapping outside, Back or changing page releases it.

Use `host.fieldFocused` and `host.fieldUnfocused` so the launcher lifts the
tile above the keyboard.

**4.3 Input.** A prompt field under the output:
- Send uses `POST /api/v1/harnesses/<id>/keys {"text","enter":true}` (4 KiB at most);
- a key row for Esc, Tab, ↑, ↓, Ctrl-C and Enter, through `TerminalInput`
  over the input WebSocket, as SD does.

**4.4 Modes**:
- overview: a tap on a card opens the terminal in the tile, and Back returns;
- pinned: the terminal always fills the tile, with a header (agent, status,
  model) and a ⇄ button to pick another harness;
- split: when the tile is at least 600 dp wide, the list on the left and the
  terminal on the right, with the same 40/60 split SD uses.

**4.5 Lifecycle**: one stream per visible terminal. Close it when the page is
no longer composed (from 1.5) or on `ON_STOP`.

Done when: you can follow a Claude Code session and answer it from the home
screen, and swiping away and back resumes within a second.

## Phase 5: hardening and sync automation (0.5–1 d)

**5.1 Guard test**, `app/src/test/.../SdHookTest.kt`: compile `90-superdesktop.owl`,
assert it has a `SuperDesktop` element, and assert `Schema.KINDS` contains
it. It fails right after a rebase that lost the hook.

**5.2 CI**: `.github/workflows/sd-sync.yml` (a new file; it runs on the fork only):
- weekly or on demand, run `scripts/sd-sync.sh` in a runner;
- on success, push `superdesktop` with `--force-with-lease` and attach the debug APK;
- on a conflict or failing test, open an issue with the log.

Check that upstream's `ci.yml` and `release.yml` don't run, or fail
harmlessly, on the fork (they may need Play secrets).

**5.3 Install path**: `scripts/sd-deploy.sh` builds and installs over wireless
adb, the same way as SD's `deploy.sh`.

**5.4 README note**, in a new `docs/superdesktop/README.md` only; upstream's
README stays untouched.

---

## Order of work and checkpoints

| Day | Work | Checkpoint |
|---|---|---|
| 1 | Phase 0, then 1.1–1.4 | Placeholder tile fills a home page on the phone |
| 2 | 1.5, then Phase 2 | Paired to the PC; logs show live harness documents |
| 3–4 | Phase 3 | Usable status pages, several of them |
| 5–7 | Phase 4 | Live terminal in the tile |
| 8 | Phase 5 | Weekly upstream sync runs by itself |

## Rules for every change

- Edit upstream files only in the hook commit. If something seems to need
  another upstream edit, add a seam in `SdHook.kt` or the module first.
- No new entries in `libs.versions.toml`, no edits to the app manifest or the
  upstream README.
- Tokens never go into widget files, logs or crash reports.
- Run `:owl:test :app:testDebugUnitTest :superdesktop:testDebugUnitTest`
  before every push.
