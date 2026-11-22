# Attribution and licensing

This project is a merge of two upstream codebases.

## OpenCode for Termux

- Source: `https://github.com/<owner>/opencode-termux` (local fork used during integration).
- Upstream base: `termux/termux-app`, released under **GPLv3 only**.
- Files carried over: `runtime/` (installer, `ElfPatch`, native `oc-patch`, daemon service),
  `termux-shared/`, `terminal-emulator/` and `terminal-view/`, plus selected resources.
- `terminal-emulator` and `terminal-view` carry the Android Terminal Emulator Apache-2.0
  exception; see their own `LICENSE.md`/`NOTICE` files where present.

Because GPLv3 code is included, the combined application must be distributed under GPLv3-compatible
terms, with corresponding source available. Do not publish a release until that is understood.

## OpenCode Android

- Source: `https://github.com/anomalyco/opencode-android` (`dev` branch, snapshot 2026-11-22).
- The repository does not currently ship a top-level license file. Confirm redistribution terms
  with the maintainers before publishing a binary built from this merged project.
- Modules kept: `app`, `core`, `server`, `feature:*`, `devtools`, `build-logic`.

## OpenCode CLI

The runtime downloads the official `@opencode-ai/cli-linux-<arch>` npm package at install time.
No CLI binary is committed here.
