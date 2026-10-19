# SD Launcher changelog

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
