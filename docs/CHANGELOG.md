# Changelog

All notable product changes are recorded in this document.

## 1.0.1 - 08/04/2026

### Added
- Added manual EPG match management directly from the full guide, including channel-level override selection and a quick return to automatic matching.
- Added EPG resolution coverage summaries and source priority controls in Settings so provider assignments can be reviewed and reordered without leaving the app.
- Added targeted regression coverage for manual EPG source assignment behavior, M3U `url-tvg` header parsing, and gzip-compressed XMLTV imports.

### Changed
- Updated live playback EPG loading to prefer resolved multi-source mappings for current, next, history, and upcoming programme data before falling back to provider-native lookups.
- Updated Home live channel now-playing badges to use the resolved EPG pipeline, aligning the home surface with the guide and player.
- Updated EPG source refresh and assignment flows to immediately re-resolve affected providers after source refresh, enable/disable, delete, assign, unassign, and priority changes.
- Updated provider XMLTV refresh to accept both standard XMLTV files and gzip-compressed `.xml.gz` feeds discovered from M3U playlist headers.

### Fixed
- Fixed M3U playlist imports so header-declared `url-tvg` and `x-tvg-url` feeds are carried through into provider EPG refresh more reliably.
- Fixed manual overrides so they persist as explicit external mappings instead of being lost during normal assignment updates.
- Fixed guide and playback consumers that previously bypassed resolved mappings and could ignore external or manually overridden EPG matches.