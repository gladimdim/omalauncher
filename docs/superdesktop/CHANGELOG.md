# SD Launcher changelog

## 1.11.0 (115)

Built on **Omalauncher 1.4.0**, as 1.10.1. The SUPER DESKTOP bridge is
unchanged, so it works with the same desktop versions as 1.10.1.

### Tiling desktop (experimental)
- **More control over windows**: float or tile with Super+T, pop a window onto
  every workspace with Super+O, and keep windows in a scratchpad with Super+S
  and Super+Alt+S. Each pop-out starts centred at 80% of the available window
  area's width and height. Resize with Super+minus/equal or Super+right drag, move with
  Super+left drag, turn a split with Super+J and toggle gaps with
  Super+Shift+Backspace. Full screen can include the bar or leave it visible.
- **Ten workspaces**: Super+1…9 and 0 switch; add Shift to move a window there
  and follow it, or Shift+Alt to stay. Super+Ctrl+Tab returns to the previous
  workspace, Super+wheel steps between them, and Alt+Tab cycles windows.
- **Your app keys**: edit `tiling.conf` beside the widgets to choose what
  Super+Shift+letter opens. Super+Return opens the Linux terminal; Linux
  windows join the tiled desktop. Super+K or the bar's question mark lists
  the shortcuts. The start button opens home's app list, the gear opens
  Android Settings, and Super+comma and its modifiers manage notifications.
- **Returning to the desktop**: taking the keyboard off returns windows to
  ordinary Android apps and keeps the focused app in front. Reconnecting it
  brings windows back. The helper enables the shortcut accessibility service
  and restarts it when Android stops it. Hidden home widgets pause their
  updates; virtual displays request the screen's refresh rate on Android 14+.
- **Opening SD Launcher from its icon** while it is the default home app now
  opens the home task, avoiding a duplicate entry in Recents.

### Setup and known limits
- Restart the adb helper after updating (and after every reboot); its
  interface changed. On HyperOS, allow SD Launcher to autostart.
- Tested during development on a Xiaomi Pad 7 Pro with HyperOS 3 only.
  Three-finger touchscreen swipes work. HyperOS can intercept touchpad
  swipes before the launcher sees them; this remains unresolved. Use
  Super+wheel or Super+number. The final 80% pop-out sizing has automated
  build checks but has not been checked on the Pad.

## 1.10.1 (114)

Built on **Omalauncher 1.4.0**, as 1.10.0. The bridge is unchanged, so it works
with the same desktop versions as 1.10.0.

### Tiling desktop (experimental)
- **The app finder follows the keyboard**: the up and down arrows move through
  its list and Esc closes it. Home had taken the arrows for its own tiles, and
  closing the finder left the focus in home's bar, which opened home's app list.

## 1.10.0 (113)

Built on **Omalauncher 1.4.0**, as 1.9.1. The bridge is unchanged, so it works
with the same desktop versions as 1.9.1.

### Tiling desktop (experimental)
- **A Hyprland-style desktop while a hardware keyboard is connected**: Android
  apps open as tiled windows on five workspaces, with a bar (workspaces, clock,
  notifications, Bluetooth, Wi-Fi, battery) that a right click moves to another
  screen edge. Home and its widgets stay under the windows; workspace N shows
  home's page N, and a swipe between pages switches the workspace with it.
- **Shortcuts**: Super+Space opens an app, Super+1…5 switches workspace,
  Super+Shift+1…5 moves the window there, Super+Alt+arrows moves focus,
  Super+Shift+arrows swaps windows, Super+F is full screen, Super+W closes,
  Super+Shift+D leaves the desktop. Three fingers swiped sideways switch
  workspace too.
- **It is off until you set it up**, and changes nothing for anyone who doesn't.
  It needs a helper started over adb after every boot and every update (see the
  README), and "Tiling desktop shortcuts" turned on in Accessibility settings
  for the Super shortcuts. Apps in windows get no on-screen keyboard.
- Tried on a Xiaomi Pad 7 Pro (HyperOS 3, Android 16) only. HyperOS keeps
  Super with a bare arrow, Super+Tab, Super+L and Super+Enter for itself.

## 1.9.1 (112)

Built on **Omalauncher 1.4.0**, as 1.9.0. The bridge is unchanged, so it works
with the same desktop versions as 1.9.0.

### SUPER DESKTOP
- **Pairing asks for local network access**: on Android 17 a first pairing
  only timed out until "Nearby devices" was allowed, because the popup that asks
  for it appeared only once a PC was paired. The pairing screen now asks when it
  opens and again on connect, continues pairing once it's allowed, and says what
  to turn on (opening the app's settings when Android no longer asks) instead of
  timing out in silence.

## 1.9.0 (111)

Built on **Omalauncher 1.4.0** (was 0.16.0). No SUPER DESKTOP changes; the
bridge is unchanged, so it works with the same desktop versions as 1.8.0.
The 32-bit TV and box build of 1.8.0 carries over.

### From Omalauncher 0.17.0 to 1.4.0
- **Linux is much faster**: proot keeps itself on one fast CPU core and a small
  "fast system calls" library answers file lookups inside the rootfs
  (`pacman -Qk` 8x, `ls -lR` 6x, `du` 5x, `grep -r` 3x, `git status` 2x). The
  Linux screen has switches for both. `omalauncher` answers in about 35 ms, and
  Linux follows the phone's DNS servers.
- **3D in widgets and apps** (Owl 22): a Canvas `scene(camera)` draws lit,
  fogged, textured models, with vector math for games and multi-finger
  `onTouch`. Also tilt (`android.motion`), `fps:` up to 60, a Shader that can't
  freeze the screen, and new example apps (sky raid, sky forge, boomtown, last
  outpost, sunset heist, canyon run).
- **Store**: widgets and apps are two sections; faces are full-screen widgets
  on their own tab; an image opens full screen on a tap.
- **Home**: it can open on any page (`home.home-page`); a Fold's inner screen
  and a Flip's cover screen get layouts of their own (`[unfolded]`, `[cover]`);
  apps with notifications show a dot.
- **Terminals**: keyboard suggestions, glide typing and dictation work there
  and are on by default.
- **Voice**: replies use the phone's chosen text-to-speech engine at once, the
  settings name the default voice, and the orb is a glossy blob.
- **Files**: the launcher no longer asks for all-files access; folders come
  from Android's folder picker.
- **Fixes**: AirPods no longer crash the launcher when their link stops, a
  notification with unreadable extras no longer crashes the dot, turning the
  phone sideways no longer sends home back to page 1, and a Linux program
  opening hundreds of Wayland connections no longer runs the launcher out of
  memory.

## 1.8.0 (110)

Built on **Omalauncher 0.16.0**, as 1.7.0. No SUPER DESKTOP changes; the
bridge is unchanged, so it works with the same desktop versions as 1.7.0.

### Devices
- **32-bit Android TVs and boxes**: SD Launcher now installs on devices that
  run 32-bit apps only (armeabi-v7a), such as many Google TV and Android TV
  sets, which refused the APK before (`INSTALL_FAILED_NO_MATCHING_ABIS`).
  Themes, wallpapers, Android apps, widgets that don't run commands and the
  SUPER DESKTOP pages and widgets all work there. Linux needs a 64-bit device,
  so on these the welcome screen doesn't offer it and the Linux screen says
  so instead of starting a download that couldn't run. 64-bit phones and TVs
  are unchanged. Thanks to @ghpo for the report and the armv7 build (#7 on
  super-desktop).

## 1.7.0 (109)

Built on **Omalauncher 0.16.0** (was 0.12.2). No SUPER DESKTOP changes; the
bridge is unchanged, so it works with the same desktop versions as 1.6.0.

### SUPER DESKTOP
- The **super desktop** row in home's long-press menu wraps its buttons onto a
  second line when they don't fit, at a large font size, instead of cutting
  the last one off.

### From Omalauncher 0.13.0 to 0.16.0
- **Languages**: settings has a language; the launcher comes in Indonesian,
  Spanish and Brazilian Portuguese (SUPER DESKTOP's own text stays English).
- **Terminal images**: the key bar's photos key, Gboard's clipboard images and
  images copied on Android go into a Linux terminal as their /tmp paths, which
  Claude Code and opencode attach.
- **Widgets**: dictation (`speech("toggle")`, `android.speech`), charging
  current (`android.power`), WebSockets (`ws(...)`), certificates pinned for
  LAN servers (`pin:`), pull to refresh, turning home to a page
  (`launcher("page:N")`) and text in several languages.
- **Faces** can draw under the status bar (`edge: true`) and come with several
  pages from the store.
- **Coding agents** take the Omarchy theme (Claude Code, Pi, OpenCode).
- **Linux**: GTK3 apps drop their own title bar, windows too big for the
  screen are shrunk to fit, pacman installs bubblewrap again, and a job's log
  keeps up with progress bars.
- **Fixes**: a widget moved to another page kept updating only until two pages
  away, a crash on a clipped node with a negative radius, frosted glass on the
  recent apps and prompt bar, and a theme render emptying a linked folder.

## 1.6.0 (108)

Built on **Omalauncher 0.12.2** (was 0.12.0). No SUPER DESKTOP changes; the
bridge is unchanged, so it works with the same desktop versions as 1.5.0.

### SUPER DESKTOP
- **Local network access popup**: on Android 17, a PC on the same Wi-Fi only
  timed out ("No answer in time") until SD Launcher was allowed on the local
  network, which only a row in settings asked for. While a PC is paired and the
  permission is off, home now shows a popup that explains it, with a button
  that asks for it; if Android no longer asks, the button opens SD Launcher's
  settings. "Not now" hides it until the launcher starts again. The connection
  check (Retry) has the same button.

### From Omalauncher 0.12.1 and 0.12.2
- **Store updates keep your edits**: a widget, app or face you or your agent
  edited is left out of a store update, and the store asks before replacing it.
- **Faster**: home starts about twice as fast, swipes are smoother, animated
  widgets and games draw about twice as fast, and widgets and terminals use
  less battery on 120 Hz screens.
- **Owl apps ripple** where you press them; pinned apps can be edited and
  shared from home, and apps in folders long-pressed.
- **Fixes**: a home screen crash while swiping between pages, Shift with
  FlorisBoard, the weather tile after midnight, a crash when Linux setup runs
  out of space, and Linux setup hanging silently on some custom ROMs. Tapping
  a terminal's prompt brings the keyboard back, and Home goes back to page 1.

## 1.5.0 (107)

Built on **Omalauncher 0.12.0**, as 1.4.0. No SUPER DESKTOP changes; the bridge
is unchanged.

- **Pick a PC's address**: ⚙ → paired computers → **address** lists the PC's
  saved addresses (Tailscale, LAN) in the order they're tried; the one you pick
  is tried first, and the feed reconnects there at once. Each PC shows the
  address its feed uses now ("via …"). When the picked address doesn't answer,
  the others are still tried in turn. No bridge change.

## 1.4.0 (106)

Built on **Omalauncher 0.12.0** (was 0.11.0), with upstream fixes since. No
SUPER DESKTOP changes; the bridge is unchanged, so it works with the same
desktop versions as 1.3.1.

### SUPER DESKTOP
- **Tailscale away from home**: a PC paired on home Wi-Fi is reached over
  Tailscale elsewhere. The feed moved two addresses per failed try, so a PC with
  one LAN and one Tailscale address only ever retried the LAN one; it now tries
  each in turn. The launcher also saves a PC's addresses while connected, so
  Tailscale switched on after pairing is used later. Turn Tailscale on on the
  phone.

### From Omalauncher 0.12
- **Control center**: controls.owl, for swiping down on home, with the phone's
  switches, brightness, volume, Do Not Disturb, the torch, what's playing, the
  next alarm and the notifications. Pick it in settings › gestures.
- **Keyboards and gamepads** reach the whole launcher, with a focus ring;
  Super+D opens the drawer, Super+T a terminal.
- **Widgets' glass** is a setting: opacity and frosted glass in Style.
- **Owl 13**: settings panels, Do Not Disturb levels, torch brightness, network
  and SIM details, notification actions and replies.
- **Faster**: home shows complete sooner after the launcher starts, swipes
  between pages are smoother, and animated widgets draw at most 60 frames a second.
- **Fixes**: Codex runs commands again under proot, agent updates no longer keep
  old versions, a crash with some voice assistants, blank store previews on
  Android 10 and 11, and very large widget images.

## 1.3.1 (105)

Built on **Omalauncher 0.11.0**, with upstream fixes since. No SUPER DESKTOP
changes; the bridge is unchanged.

- **Bigger store items**: the widget store, share links and pasted widgets take
  widgets, faces and apps up to 1 MB instead of 256 KB, for apps that carry
  their own data.

## 1.3.0 (104)

Built on **Omalauncher 0.11.0** (was 0.9.0). No SUPER DESKTOP changes; the bridge
is unchanged, so it works with the same desktop versions as 1.2.0.

### From Omalauncher 0.10 and 0.11
- **The app drawer and the terminal key bar are owl files** (drawer.owl,
  keybar.owl) that you or an agent can edit, with stock fallbacks. Settings ›
  style shows the key bar's state and sets its gaps and border width.
- **Home's layout is a file**: ~/.config/omalauncher/home/layout.toml lists the
  pages and where every widget, app and folder sits; edits to it move home.
- **Swipe panels**: owl 11 `panel` files open on a swipe up or down.
- **Owl 12**: encrypted `secret` state, password inputs, PUT/PATCH/DELETE
  requests, base64 helpers, and widgets that press keys in Linux windows.
- **Safer widgets**: requests send headers only to the server they name, and a
  runaway regex, a huge value or a stuck stream can't freeze the launcher.
- **Linux install** checks for about 3 GB of free space first and can be cancelled.
- **Fixes**: a crash from the store's panels tab, a crash when going home during
  a backup, backups on phones with Linux, status bar icon colours after a
  rotation, store preview memory use, and Files with broken links.

## 1.2.0 (103)

Built on **Omalauncher 0.9.0**.

### New
- **Usage widgets.** Long-press home → super desktop **[ usage ]**, or use a
  SUPER DESKTOP page's **⚙ → + usage widget**. Four configurable cards show
  plan limits, reset times, tokens, sessions and prompts, with a separate
  computer/provider/statistic choice for each card. Live session counts can
  follow one PC or all paired PCs. Each widget keeps its own settings and
  resizes down to 3×2 cells; smaller widgets scroll. Offline/missing values
  show **—**, and incomplete all-PC totals are unavailable. Uses the existing
  bridge feed and permissions; no desktop update is needed.
- **Retry an offline PC, and see why it's offline.** An offline PC's row has
  **retry**. It opens a popup that walks every address of the PC step by step —
  the phone's network, name lookup, TCP, TLS and the pinned certificate, the
  pairing token — with timings and a plain explanation of what failed. The
  live feed then reconnects at once on the address that answered, without
  waiting out the backoff (up to 30 s). Close it with **close** or Back. On
  Android 17 it also warns when the launcher's **local network** setting is
  off, since LAN addresses then time out.

### Fixed
- **Wake works.** Pressing **wake** on an offline PC failed with a
  ConnectivityService permission error; the launcher now asks for network
  state, which Android grants at install. The button reads **wake**, without
  the ⏻ symbol the launcher's font lacked.

## 1.1.0 (102)

Built on **Omalauncher 0.9.0** (was 0.8.1).

### New
- **SUPER DESKTOP as a face.** Omalauncher 0.9.0's faces are whole home screens left of page 1;
  long-press home → super desktop **[ face ]** puts your PCs' agents there.
- **Share to SUPER DESKTOP.** From any app's share menu, send text, images or files to a harness on
  any paired PC, or keep text as that harness's draft. Images are resized and stripped of metadata;
  at most 4 items and 16 MB per prompt.
- **"Done" and "needs you" cues.** A card or terminal widget flashes green with a short buzz when its
  agent finishes, and a waiting agent is framed in yellow and labelled NEEDS YOU.
- **Prompt history.** A harness's last 200 prompts: tap one to put it back in the prompt, or copy it.
  Needs SUPER DESKTOP 1.3.0+ on the PC.
- **Wake-on-LAN.** ⏻ wake on an offline PC's header. Needs SUPER DESKTOP 1.5.0+, the phone on the PC's
  network, and Wake-on-LAN enabled on the PC.
- **Fuller key row.** y, n, PgUp and PgDn join esc, tab, arrows, ^C and ⏎.
- **Drafts are kept** per harness across pages, restarts and failed sends.
- **Every working folder** the PC offers when starting a harness, in a scrollable list (was the first 6).
- **New app icon:** the SUPER DESKTOP thunderbolt mascot.

### Better connection
- **Pairing opens a page first**, with **scan QR** and a field for a pasted `superdesktop://pair` link
  or invitation, instead of jumping straight into the camera.
- **Offline is visible.** While a PC is unreachable, its cards are dimmed and labelled OFFLINE instead
  of looking live.

### Fixed
- The launcher no longer restarts when it draws a terminal widget's preview image: SD tiles draw a still
  picture there (no Back to handle, no connection opened).
- Buttons with symbols (+, ⌨, ⚙, ⇄, ↗) are the same height as the others.
- Symbols the phone has no font for (Claude Code's ⏵⏵) show as close substitutes instead of boxes.

## 1.0.0 (100)

First release: SUPER DESKTOP pages and resizable terminal widgets on the home screen, live terminals
sized to the tile, starting and closing harnesses, QR pairing with certificate pinning.
