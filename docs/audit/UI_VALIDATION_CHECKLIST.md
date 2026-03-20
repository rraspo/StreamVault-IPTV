# StreamVault UI Validation Checklist

Last updated: 2026-03-20

Purpose: manual QA list for the user-visible changes completed from the pre-release missing-features audit.

Scope:
- Only includes changes that affect UI, user flows, or visible playback behavior.
- Does not list backend-only work such as schema changes, mappers, parsers, or repository internals unless the change alters something you can verify on screen.

How to use this file:
- Treat each item as a manual test case.
- Verify both the visible UI and the runtime behavior.
- If behavior differs from the Expected Behavior section, note the screen, provider type, and content type used.

## 1. Player

### 1.1 Manual quality selection

Where to test:
- Start a stream that exposes multiple Media3 video variants.
- Open the in-player track or quality controls.

What changed:
- The player now exposes actual video quality choices instead of only automatic adaptive playback.

Expected behavior:
- You should see an Auto option plus one or more manual quality options when the stream provides them.
- Selecting a manual option should lock playback to that variant instead of immediately bouncing back to Auto.
- Reopening the quality control during the same session should show the selected option as active.
- If the stream only has one video track, the quality chooser should not show a fake list of variants.

### 1.2 Network-specific quality preferences

Where to test:
- Settings.
- Use one Wi-Fi network and one Ethernet connection if available.

What changed:
- Settings now expose separate max-quality preferences for Wi-Fi and Ethernet.

Expected behavior:
- Settings should show separate rows for Wi-Fi and Ethernet quality preference.
- Each row should allow Auto, 2160p, 1080p, 720p, and 480p style caps.
- After changing a cap, new playback sessions should respect that network-specific preference.
- Auto should remove the cap instead of forcing a fixed resolution.

### 1.3 Default playback speed

Where to test:
- Settings.
- Movie playback.
- Episode playback.

What changed:
- Default VOD playback speed is now configurable and persisted.

Expected behavior:
- Settings should show a Default Playback Speed row.
- Allowed values should include 0.5x, 0.75x, 1x, 1.25x, 1.5x, and 2x.
- Starting VOD playback after changing the default should apply the selected speed automatically.
- Live TV should not behave like VOD speed control unless the screen already supports that explicitly.

### 1.4 Preferred audio language

Where to test:
- Settings.
- Any movie or episode with multiple audio languages.

What changed:
- Audio selection now supports an automatic language preference plus an explicit preferred language override.

Expected behavior:
- Settings should show a Preferred Audio Language row.
- The automatic option should map to app language first, otherwise device language.
- If the content contains a matching audio language, playback should choose it automatically.
- Manual in-player audio selection should still work after auto-selection has occurred.

### 1.5 Subtitle appearance

Where to test:
- Settings.
- Any subtitle-capable VOD item.

What changed:
- Subtitle size, text color, and background style are now configurable and persisted.

Expected behavior:
- Settings should show subtitle size, subtitle text color, and subtitle background rows.
- Changing these values should immediately affect newly shown subtitles in playback.
- The selected appearance should persist across sessions.
- Backup export and import should preserve these subtitle settings.

### 1.6 Seek preview thumbnails

Where to test:
- Movie and episode playback.
- Scrub the seek bar on a direct-file VOD stream.

What changed:
- Scrubbing now shows preview cards instead of only a time position.

Expected behavior:
- During scrubbing, a preview card should show a timestamp and either a frame thumbnail or artwork fallback.
- HLS or DASH streams that cannot supply preview frames should fall back cleanly to artwork.
- The seek UI should remain stable instead of flashing or collapsing when preview extraction is unsupported.

### 1.7 Stream failover

Where to test:
- A live channel with alternate stream URLs or a provider/source known to fail over.

What changed:
- The player now retries alternate stream URLs before surfacing a terminal playback failure.

Expected behavior:
- A failed primary stream should attempt recovery before the player gives up.
- If an alternate stream works, playback should recover without requiring a manual channel restart.
- The user should not immediately get a hard failure on the first stream error if alternates exist.

### 1.8 DRM playback error messaging

Where to test:
- Any stream that triggers DRM-related playback failure.

What changed:
- DRM failures are now separated from generic source failures.

Expected behavior:
- DRM-specific playback failures should surface a DRM-oriented error message, not a generic source or network message.
- Non-DRM failures should still show the appropriate network, source, or decoder error.

## 2. Search

### 2.1 Unified cross-content search

Where to test:
- Global search screen.

What changed:
- Search now flows through a shared domain use case across live, movies, and series.

Expected behavior:
- Search should return results across the active provider’s live, movie, and series catalogs.
- Switching tabs should scope results correctly to All, Live, Movies, or Series.
- Recent queries should remain trimmed, deduplicated, and capped.

### 2.2 Improved result ordering

Where to test:
- Global search screen.
- EPG search.

What changed:
- Search results are now ranked by relevance rather than arbitrary Room or FTS ordering.

Expected behavior:
- Exact title matches should appear before prefix matches.
- Prefix matches should appear before loose contains matches.
- Searches like Sports should prefer Sports over longer partial-match titles.

## 3. EPG And Live TV

### 3.1 Program search in the guide

Where to test:
- TV Guide or EPG screen.

What changed:
- The guide now supports in-window program-title search.

Expected behavior:
- The guide should expose a program search input.
- Entering a query should filter guide results to matching programs inside the active time window.
- If no programs match, the guide should show a clear no-results empty state rather than a broken or blank layout.

### 3.2 Category-aware guide browsing

Where to test:
- TV Guide or EPG screen.

What changed:
- Program loading is now category-aware at the repository level instead of being only channel-driven UI filtering.

Expected behavior:
- Changing guide category should load the matching guide content for that category.
- Search inside the guide should stay scoped to the selected category when a category is active.

### 3.3 Channel-number ordering and healthy-channel preference

Where to test:
- Live TV guide and live channel browsing.

What changed:
- Channels now preserve provider numbering and the browse layer can prefer channels without known errors.

Expected behavior:
- Channels should appear in provider number order rather than arbitrary app-side ordering.
- Favorites-only or other filtered views should still be able to show the user’s chosen channel even if it has prior errors.

### 3.4 Recent live category

Where to test:
- Home or live-TV category rail.

What changed:
- Recent live history is exposed as a virtual recent category.

Expected behavior:
- A Recent live category should be visible even when its item count is zero.
- After watching live channels, the Recent category should populate with recently viewed channels.

## 4. Movies And Series

### 4.1 Continue watching behavior

Where to test:
- Dashboard.
- Movies.
- Series.
- Favorites.

What changed:
- Continue-watching assembly now uses shared domain logic with resume filtering and series-aware deduplication.

Expected behavior:
- Continue-watching rows should not show duplicate entries for the same series.
- Near-complete titles should fall out of continue watching once they meet the completion threshold.
- Resume items should appear provider-scoped and ordered by recent activity.

### 4.2 Movie recommendations

Where to test:
- Launcher recommendations if available.
- Any in-app recommendation or preview surfaces that consume movie recommendations.

What changed:
- Recommendations are now personalized rather than always falling back to generic top-rated content.

Expected behavior:
- When enough viewing or favorite history exists, recommendations should reflect that history.
- If no personalized signal exists, the app should still fall back cleanly to top-rated preview behavior.

### 4.3 Browse filtering and sorting

Where to test:
- Movies and series library screens.

What changed:
- Full-library browse semantics now support repository-backed search, filtering, sorting, and paging.

Expected behavior:
- Search, sort, and filter choices should affect the actual loaded list instead of only superficial client-side transforms.
- Sorts like title, release, rating, and watch count should produce stable results.
- Filters like favorites, in progress, unwatched, and top rated should behave consistently across larger libraries.

### 4.4 Series unwatched badge

Where to test:
- Series detail screen.

What changed:
- Series detail now exposes repository-backed unwatched episode counts.

Expected behavior:
- The series detail UI should show how many episodes are left to finish.
- Completing or marking episodes watched should update that count correctly.

## 5. Recording

### 5.1 Recurring schedule actions

Where to test:
- Live player quick actions.
- Settings recording list.

What changed:
- Recording schedules now support one-time, daily, and weekly recurrence.

Expected behavior:
- The player should expose one-time, daily, and weekly recording actions.
- Settings should display recurrence labels for scheduled items.
- Daily and weekly schedules should roll forward to the next occurrence after the current one becomes due.

### 5.2 Recording conflict handling

Where to test:
- Create overlapping manual and scheduled recordings.

What changed:
- Overlapping recordings are now rejected instead of silently double-starting.

Expected behavior:
- Creating an overlapping recording should fail with a clear conflict message.
- Due scheduled recordings that overlap an active one should not start a second recording behind the scenes.

## 6. Backup And Restore

### 6.1 Backup import preview coverage

Where to test:
- Settings backup import preview dialog.

What changed:
- Backup preview now includes more restored sections and exposes recording schedule import as a selectable section.

Expected behavior:
- The import preview should show counts for preferences, providers, saved library, playback history, split screen presets, and recording schedules.
- Saved Library counts should include protected category data now that it is part of backup coverage.
- The import plan should allow toggling recording schedules on or off.

### 6.2 Backup persistence of player and parental settings

Where to test:
- Export backup.
- Change settings.
- Re-import backup.

What changed:
- Backup now preserves parental PIN data, protected categories, scheduled recordings, subtitle appearance, playback speed, preferred audio language, and network quality caps.

Expected behavior:
- After import, the app should restore those settings instead of only basic provider or favorite data.
- Restored protected categories should appear locked again.
- Restored recording schedules should reappear in recording-related settings surfaces.

## 7. Settings

### 7.1 New playback preference rows

Where to test:
- Settings playback section.

What changed:
- Several new settings rows now exist for player behavior.

Expected behavior:
- Settings should show rows for:
  - Default Playback Speed
  - Preferred Audio Language
  - Subtitle Size
  - Subtitle Text Color
  - Subtitle Background
  - Wi-Fi Quality Preference
  - Ethernet Quality Preference
- Each row should open a working selection dialog with the current choice highlighted.

### 7.2 Provider refresh warning messaging

Where to test:
- Settings provider management actions.

What changed:
- Provider refresh now routes through a shared sync use case and surfaces partial-warning messaging more consistently.

Expected behavior:
- A clean refresh should report success.
- A partial refresh should report completion with warnings instead of pretending the refresh was fully clean.

## 8. Android TV Platform Surfaces

### 8.1 Watch Next and launcher recommendations

Where to test:
- Android TV home screen if supported by the device.

What changed:
- The app now publishes Watch Next content and launcher recommendation rows using real playback and recommendation data.

Expected behavior:
- Recent playback should be eligible to appear in Watch Next.
- Launcher movie recommendations should not always be static top-rated content when personalized data exists.

### 8.2 Voice search and external routing

Where to test:
- Android TV global search or external app routing into StreamVault.

What changed:
- MainActivity now handles searchable and external route entry points.

Expected behavior:
- External search or route intents should land in the correct app flow instead of dropping the user at an unrelated default screen.

## 9. Quick Regression List

Use this section as a short smoke pass after bigger changes.

- Open Settings and verify all new playback rows exist and open dialogs.
- Start a multi-variant VOD item and verify quality selection works.
- Set non-default playback speed and confirm the next VOD session starts at that speed.
- Change subtitle appearance and verify playback reflects the new style.
- Run a global search for an exact title and confirm result ordering looks relevance-based.
- Use EPG search and verify no-results handling is clean.
- Schedule daily and weekly recordings and confirm recurrence labels appear in Settings.
- Export and re-import a backup, then verify restored player preferences, protected categories, and recording schedules.
- Check continue-watching rows for duplicate-series cleanup and completed-title removal.
- Trigger a provider refresh and verify partial warnings show when appropriate.
