# SUPER DESKTOP in Omalauncher: fork plan

Goal: a personal fork of Omalauncher with a native SUPER DESKTOP client on the
home screen, that keeps pulling upstream (SimonSchubert/omalauncher) with
minimal conflicts. Not for resale.

## The idea in one paragraph

SUPER DESKTOP is a new OWL element, `SuperDesktop { … }`, rendered by a
separate Gradle module. A "SUPER DESKTOP page" is an ordinary widget file whose
tile fills a whole home page. Everything else is already the launcher's job:
pages, placement, resizing, the tile frame, gaps, corners and glass background
(the padding that shows it is embedded, not a full-screen app), theme
colors, removing the widget and reloading the file. The fork touches four
upstream files with about 10 lines; the rest lives in files upstream never has.

## What the user sees

- **A SUPER DESKTOP page**: one tile filling a home page, inside the usual
  widget frame and gaps, above the prompt bar. Swiping left and right still
  turns pages.
- **Per-page selection**: a ⚙ in the tile's corner opens its settings. Pick
  which PCs it shows, and a mode:
  - **Overview**: usage strip on top, then one section per PC with harness
    cards (agent icon, status, model, effort, folder, last prompt, the last 3
    lines of output), and ＋ to start a harness on that PC.
  - **Pinned terminal**: one harness, live terminal filling the tile, a slim
    header and a prompt field.
  - **Split** (when the tile is wide, e.g. unfolded): list on the left,
    terminal on the right.
- **Several pages**: "＋ New SUPER DESKTOP page" in the settings writes a new
  widget file on the next free page. For example: page 2 shows the work PC,
  page 3 is pinned to one Claude terminal, page 4 shows the laptop.
- **Tap a card** to open its terminal inside the tile; Back returns to the
  overview. "Open in app" hands the harness to the SUPER DESKTOP Android app
  for things the tile doesn't do (files, attachments, editor actions).
- **Notifications stay with the SUPER DESKTOP app.** The launcher module only
  works while its page is on screen.

## Architecture

```
omalauncher (fork)
├── app/                     upstream, plus 2 small hooks
├── owl/                     upstream, plus 1 line in Schema.kt
└── superdesktop/            NEW Android library module, all fork code
    ├── bridge/              client copied from OmarchyAILauncher: models and parser
    │                        (HarnessApi.kt minus BridgePrefs), BridgeTls (pinned
    │                        OkHttp), BridgeConnection, TerminalInput, PairingLink
    ├── store/               paired PCs and tokens (EncryptedSharedPreferences),
    │                        per-page config keyed by the element's `id`
    ├── live/                one connection manager per process: a single
    │                        /harnesses/stream WebSocket per PC, shared by every page,
    │                        opened while any page needs it, closed on ON_STOP
    ├── ui/                  Compose: SuperDesktopTile (overview, pinned, split),
    │                        settings sheet, terminal (TerminalOutputView via AndroidView)
    └── PairingActivity      paste a superdesktop://pair link or scan a QR, show the
                             6-digit code, poll until approved on the PC. Declared in
                             the module's own manifest, so the app manifest is untouched.
```

The module depends on Compose and OkHttp, never on launcher internals. The
launcher passes in what it needs: theme colors and fonts as a small
`SdTheme` value, and a callback to write widget files.

### The widget file (one per page)

```
widget "SUPER DESKTOP" {
  meta { owl: 9 }
  size: 9x99          // clamped to the page: the tile fills it
  page: 2
  padding: 0
  SuperDesktop { id: "sd-7f3a" }
}
```

`id` keys the page's config (PCs, mode, pinned harness) in the module's store,
so choices made in the UI survive edits to the file and launcher restarts.
"New page" writes `superdesktop-<id>.owl` into the widgets folder; the
launcher's FileObserver loads it within a second.

### The four upstream touch points

| File | Change |
|---|---|
| `settings.gradle.kts` | `include(":superdesktop")` |
| `app/build.gradle.kts` | `implementation(project(":superdesktop"))`; applicationId `com.gladimdim.sdlauncher`, so the build installs next to the Play version |
| `owl/…/lang/Schema.kt` | `"SuperDesktop" to Kind(setOf("id"), Children.NONE)` as the **first** entry of `KINDS` (upstream appends new kinds at the end, so the first line rarely conflicts) |
| `app/…/widgets/render/Render.kt` | one branch, first in `Element`'s `when`: `"SuperDesktop" -> SdElement(p, inst, mod)`, where `SdElement` lives in a fork-only file in `app/` (`widgets/render/SdHook.kt`) and adapts launcher types (Palette, LocalHomeTiles, widgets dir) to the module's `SdHost` |

Since then: one line in `ui/HomeMenu.kt` adds SUPER DESKTOP's row to the home
long-press menu (`SdMenuRow`, in the fork-only `ui/SdMenu.kt`).

Plus a new asset, `assets/widgets/90-superdesktop.owl`: a default widget, so
the launcher writes it into the widgets folder on start (new and existing
installs) and never brings it back once deleted. It is a new file, so it never
conflicts. (The launcher has no gallery for bundled examples.)
`SdHook.kt` is also a new file: it can break when upstream renames launcher
types, but it can't conflict.

Rules that keep the diff small:
- No `SINCE` entry, no compiler level bump: the element is valid at any level.
- No edits to the app manifest, settings screens, version catalog or launcher
  classes. Dependency versions (OkHttp, security-crypto, ZXing) are declared in
  `superdesktop/build.gradle.kts`, not in `libs.versions.toml`.
- Keep the hook in one commit, `fork: SUPER DESKTOP hook`, so
  `git log upstream/main..superdesktop` always shows the whole fork.

## Behaviour details to get right

- **Gestures**: vertical scrolling in the overview is fine inside the pager.
  The terminal follows the Terminal tile's model: inert until tapped, then it
  takes keyboard and touch; tapping outside or Back lets go. Avoid long-press
  (home arrange owns it) and horizontal drags outside a focused terminal
  (page swipes own them).
- **Live only when visible**: open sockets while the page is composed (the
  current page only: Omalauncher disposes every other page), close 10 s after
  the page leaves and on `ON_STOP`. One stream per PC
  however many pages show it.
- **TLS**: reuse BridgeTls: pinned SHA-256 of the leaf cert, hostname
  `super-desktop.local`. Tokens are encrypted at rest; never written into
  widget files.
- **Pairing** registers the launcher as its own device on the PC, separate
  from the SUPER DESKTOP app's token. It shows in the PC's device list and can
  be revoked on its own.
- **Local network**: Android 17+ needs `ACCESS_LOCAL_NETWORK`. The launcher
  already requests it from Settings; the tile shows a hint when a LAN address
  is unreachable. Tailscale addresses work through the VPN.
- **Release builds** are minified; add keep rules for the bridge models if
  they are parsed by reflection (they are parsed by hand today, so likely
  none).

## Phases

| # | Phase | Result | Effort |
|---|---|---|---|
| 0 | Fork hygiene | `upstream` remote, `superdesktop` branch, applicationId, debug build installs next to the Play app | 0.5 d |
| 1 | Spike | Hook plus a "hello" tile that fills a page in theme colors. Check: clamped full-page size in portrait, landscape and unfolded; page swipes over the tile; lifecycle of a neighbouring page; tile frame and padding look | 0.5–1 d |
| 2 | Bridge module | Copied client, pinned TLS, encrypted store, pairing activity, shared connection manager | 1 d |
| 3 | Overview page | Machine sections, harness cards, usage strip, per-page settings sheet, "new page" writer, open-in-app | 1.5–2 d |
| 4 | Terminal | TerminalOutputView in the tile, prompt input over `/keys` or the input WebSocket, focus model, soft keyboard, Back; pinned and split modes | 2–3 d |
| 5 | Sync automation | A scheduled GitHub Action on the fork: fetch upstream, rebase `superdesktop`, build, run `:owl:test :app:testDebugUnitTest :superdesktop:test`, open an issue on conflict or failure | 0.5–1 d |

Total: about 6–9 days. Phases 1–3 alone give a usable status page.

## Syncing with upstream

```
git remote add upstream https://github.com/SimonSchubert/omalauncher.git
git fetch upstream
git rebase upstream/main superdesktop    # hook commit first, module commits after
./gradlew :owl:test :app:testDebugUnitTest :superdesktop:test assembleDebug
```

What can break, and where to look:
- `Render.kt` or `Schema.kt` refactored: re-apply the one-line hook.
- Compose or Kotlin upgraded: the module uses the same plugins, so it
  usually only needs a rebuild.
- OWL changes how elements get properties (`p.observe`): adjust the hook's
  arguments.
- The grid's size clamping changes: the page file's `size: 9x99` may need a
  new value.

A test in `:app` that compiles `90-superdesktop.owl` and checks that `SuperDesktop` is
a known element catches the first and third cases right after a rebase.

## Open decisions

1. **Bridge client: copy or share.** Start by copying (fast). When the
   protocol moves past v3, extract `bridge-client` into a library module in
   OmarchyAILauncher and consume it from both apps (JitPack from a tag, or a
   composite build).
2. **Name and icon of the fork build**: for example "Omalauncher SD", so the
   two installs are easy to tell apart.
3. **Ask Simon about a generic hook later.** If upstream accepted a small
   "native element registry" (other apps' modules register elements), the
   fork would shrink to the module and the build lines.
