# Changelog

## [v1.1.1] - 2026-10-10

### Fixed
- **Import:** Background notifications that are disabled now show an explanation instead of being silently skipped. (c0a42f5)

## [v1.1.0] - 2026-10-10

### Added
- **Playlist import from links** — Add playlists straight from Spotify and YouTube Music URLs, no manual track entry required.
- **Import progress bar** — Link imports now display a progress bar that tracks the import as it runs.
- **Cancellable imports** — Link imports can be cancelled mid-flight if you change your mind or picked the wrong playlist.
- **Background importing** — Playlist imports now run in the background, so you can keep using the app while they finish, with progress reported through a notification.

## [v1.0.2] - 2026-10-10

### Added
- **Lyrics provider settings** — Added a desktop-style lyrics provider settings screen, giving you control over which sources are used when fetching lyrics. (`237263e`)
- **Smart shuffle** — The player now offers smart shuffle, which reorders upcoming tracks to avoid repetition and keep playback varied. (`88019f3`)
- **Launcher shortcuts** — Added launcher shortcuts for quick access to common player actions right from your home screen. (`88019f3`)

### Changed
- **Listen Together URL migration** — Migrated the old `metrolist.caliph.dev` Listen Together endpoint to `utaloom.caliph.dev`. Existing sessions will continue to work against the new host. (`0015cca`)

## [v1.0.1] - 2026-10-09

### Changed
- New app icon: the **Utaloom Woven U** logo replaces the previous Metrolist-derived icon. The artwork is now original to Utaloom, so it no longer carries the licensing and attribution baggage of the borrowed design.

## [v1.0.0] - 2026-10-09

### Added
- **listentogether:** Default to the `utaloom.caliph.dev` server, and ensure v1 + v2 release signing ([a810c37])
