# StreamVault Comprehensive Code Review

Date: 2026-03-20

Scope:
- Repo-wide audit of app, data, domain, and player modules.
- Focus on edge cases, logic correctness, feature completeness, UI linkage, duplicate code, and test health.

Validation performed:
- `./gradlew.bat :app:compileDebugKotlin :domain:test :data:testDebugUnitTest` -> passed.
- `./gradlew.bat :app:compileDebugAndroidTestKotlin` -> passed after the follow-up fixes below.

Status update:
- Fixed: player provider-switch playlist state.
- Fixed: stale player overlay Android test call sites by restoring source compatibility.
- Fixed: misleading static decoder and buffer rows in Settings.
- Fixed: guide scheduled-only persistence.
- Fixed: persisted recent search queries.
- Fixed: stale duplicate tmp player overlay snapshot.
- Reduced: duplicated Movies and Series VOD constants, shared screen plumbing, favorite/group dialog actions, browse-state setters, reorder mechanics, and preview/search catalog builders.
- Remaining larger structural follow-up: deeper Movies and Series selected-category loading and overall viewmodel surface duplication.

## Findings

### 1. High: Player provider switches can retain the previous playlist and stale channel navigation state

Evidence:
- [PlayerViewModel.kt](app/src/main/java/com/streamvault/app/ui/screens/player/PlayerViewModel.kt#L602) assigns `currentProviderId = providerId` before the playlist reload gate.
- [PlayerViewModel.kt](app/src/main/java/com/streamvault/app/ui/screens/player/PlayerViewModel.kt#L676) checks whether the playlist should reload with `providerId != currentProviderId`.
- [PlayerViewModel.kt](app/src/main/java/com/streamvault/app/ui/screens/player/PlayerViewModel.kt#L679) assigns `currentProviderId = providerId` again inside the reload branch.

Why it matters:
- If the player is opened for a different provider but the category ID is unchanged, the provider-change branch can be skipped because the comparison is already invalidated.
- That leaves `channelList`, `currentChannelIndex`, numeric zapping, and last-channel fallback attached to the old provider context.
- The user-visible outcome is wrong next/previous channel behavior and stale live overlays after provider switches.

### 2. High: Android instrumentation test sources are broken by player overlay API drift

Evidence:
- The current overlay signature requires new parameters such as `playbackSpeed`, `quickActionsFocusRequester`, recurring recording callbacks, PiP, and Cast callbacks in [PlayerControlsChrome.kt](app/src/main/java/com/streamvault/app/ui/screens/player/overlay/PlayerControlsChrome.kt#L83).
- Existing test call sites still use the old signature in [PlayerSmokeTest.kt](app/src/androidTest/java/com/streamvault/app/ui/PlayerSmokeTest.kt#L91) and [PlayerOverlayGoldenTest.kt](app/src/androidTest/java/com/streamvault/app/ui/screens/player/overlay/PlayerOverlayGoldenTest.kt#L61).

Observed failure:
- `:app:compileDebugAndroidTestKotlin` fails with missing-parameter errors for the added overlay arguments.

Why it matters:
- UI test coverage for the player is effectively offline.
- This is exactly the surface where multiple recent features were added, so regression risk is high.

### 3. Medium: Settings exposes decoder mode and buffer duration as settings rows, but they are not settings

Evidence:
- [SettingsScreen.kt](app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsScreen.kt#L475) shows a decoder mode row with a fixed `Auto` value.
- [SettingsScreen.kt](app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsScreen.kt#L476) shows a buffer duration row with a fixed `5s` value.
- There is player-side decoder logic in [PlayerEngine.kt](player/src/main/java/com/streamvault/player/PlayerEngine.kt#L44) and recovery-mode switching in [PlayerViewModel.kt](app/src/main/java/com/streamvault/app/ui/screens/player/PlayerViewModel.kt#L282), but no editable settings flow or persistence for either row.

Why it matters:
- The UI implies these controls are configurable when they are not.
- That creates a feature-completeness gap and misleads users during troubleshooting.

### 4. Medium: The guide does not persist or restore the scheduled-only filter, unlike the rest of its state

Evidence:
- The scheduled-only toggle only flips in-memory state in [EpgViewModel.kt](app/src/main/java/com/streamvault/app/ui/screens/epg/EpgViewModel.kt#L181).
- Restored guide state in [EpgViewModel.kt](app/src/main/java/com/streamvault/app/ui/screens/epg/EpgViewModel.kt#L410) includes density, channel mode, favorites-only, and anchor time, but not scheduled-only.
- Preferences support exists for the other guide settings in [PreferencesRepository.kt](data/src/main/java/com/streamvault/data/preferences/PreferencesRepository.kt#L395), [PreferencesRepository.kt](data/src/main/java/com/streamvault/data/preferences/PreferencesRepository.kt#L405), [PreferencesRepository.kt](data/src/main/java/com/streamvault/data/preferences/PreferencesRepository.kt#L415), and [PreferencesRepository.kt](data/src/main/java/com/streamvault/data/preferences/PreferencesRepository.kt#L425).

Why it matters:
- The guide appears stateful, but one of its main filters silently resets.
- That inconsistency is a TV UX problem because users expect return-to-guide behavior to preserve the current browsing mode.

### 5. Medium: Recent search queries are view-model-only and disappear across process death or app restart

Evidence:
- Recent searches live only in the in-memory state flow in [SearchScreen.kt](app/src/main/java/com/streamvault/app/ui/screens/search/SearchScreen.kt#L77).
- They are updated and trimmed in [SearchScreen.kt](app/src/main/java/com/streamvault/app/ui/screens/search/SearchScreen.kt#L142) and cleared in [SearchScreen.kt](app/src/main/java/com/streamvault/app/ui/screens/search/SearchScreen.kt#L161).
- There is no matching persistence in [PreferencesRepository.kt](data/src/main/java/com/streamvault/data/preferences/PreferencesRepository.kt).

Why it matters:
- The implementation satisfies dedupe and cap rules only for the current process lifetime.
- For a living-room device app, losing recent queries on restart is a noticeable UX downgrade.

### 6. Medium: Movies and Series flows are duplicated heavily enough that behavior drift is already likely

Evidence:
- The view models mirror the same structure and state choreography in [MoviesViewModel.kt](app/src/main/java/com/streamvault/app/ui/screens/movies/MoviesViewModel.kt#L39) and [SeriesViewModel.kt](app/src/main/java/com/streamvault/app/ui/screens/series/SeriesViewModel.kt#L38).
- The screens also mirror the same layout and interaction structure in [MoviesScreen.kt](app/src/main/java/com/streamvault/app/ui/screens/movies/MoviesScreen.kt#L70) and [SeriesScreen.kt](app/src/main/java/com/streamvault/app/ui/screens/series/SeriesScreen.kt#L69).

Why it matters:
- Every browse, search, reorder, favorite, and dialog change has to be kept in sync twice.
- The risk is not theoretical: the player overlay tests already drifted after API changes, and these mirrored screens have the same maintenance profile.

Status update:
- This was partially reduced in follow-up fixes.
- Shared VOD defaults, snackbar/PIN screen helpers, favorite/group dialog repository actions, browse-state helper functions, reorder helper functions, and preview/search catalog builders now live under the shared VOD package.
- The remaining duplication is concentrated more narrowly in selected-category loading and some viewmodel-specific state wiring rather than the earlier full end-to-end duplicated surface.

### 7. Low: A stale source snapshot in tmp duplicates active player overlay code and increases review noise

Evidence:
- The live implementation is in [PlayerControlsChrome.kt](app/src/main/java/com/streamvault/app/ui/screens/player/overlay/PlayerControlsChrome.kt).
- A duplicate snapshot also exists in [tmp/PlayerControlsChrome_HEAD.kt](tmp/PlayerControlsChrome_HEAD.kt).

Why it matters:
- It increases search noise and makes code review, refactoring, and grep-based exploration less reliable.
- It also raises the chance of updating the wrong file during future edits.

## Additional Notes

### Confirmed strengths

- Core compile and unit-test path is healthy: `:app:compileDebugKotlin`, `:domain:test`, and `:data:testDebugUnitTest` passed.
- The playback settings called out in the recent UI checklist are actually wired into Settings for playback speed, preferred audio language, subtitle appearance, and per-network quality caps. See [SettingsScreen.kt](app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsScreen.kt#L443) and [SettingsScreen.kt](app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsScreen.kt#L742).
- EPG program search is exposed in the guide UI and routes into repository-backed search. See [EpgScreen.kt](app/src/main/java/com/streamvault/app/ui/screens/epg/EpgScreen.kt#L108) and [EpgViewModel.kt](app/src/main/java/com/streamvault/app/ui/screens/epg/EpgViewModel.kt#L458).
- Provider onboarding is centralized through the shared validation/use-case path instead of raw screen logic. See [ProviderSetupViewModel.kt](app/src/main/java/com/streamvault/app/ui/screens/provider/ProviderSetupViewModel.kt#L17).

### Recommended priorities

1. Fix the player provider-switch state bug first because it affects live playback correctness.
2. Restore Android test compilation for the player overlays before adding more player UI features.
3. Either make decoder and buffer rows configurable or relabel/remove them so Settings stays truthful.
4. Persist guide scheduled-only and search recent-query state for consistent TV UX.
5. Start consolidating Movies and Series browse flows into shared VOD browse primitives.