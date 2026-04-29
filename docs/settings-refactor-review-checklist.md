# Settings Refactor Review Checklist

Generated from the current working tree against `master` on 2026-04-29.

Use this file in phases:

1. Compare each extracted file against the original logic on `master` and tick `Drift` when behavior still matches.
2. Check the current branch entrypoints and call sites, then tick `Wiring` when the new file is connected in the right place.
3. Tick `Done` only after both checks are complete for that row.

## Phase 1: Orchestration And State Wiring

Reviewed on 2026-04-29. No drift or wiring findings recorded in this phase.

| Done | Drift | Wiring | Status | File | Focus |
| --- | --- | --- | --- | --- | --- |
| [x] | [x] | [x] | modified | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsScreen.kt | top-level settings screen composition |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsContentPane.kt | category-to-section content routing |
| [x] | [x] | [x] | modified | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsScreenDialogs.kt | dialog wiring hub |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsScreenOverlays.kt | overlay and browser dialog wiring |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsScreenDialogState.kt | remembered dialog and screen state |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsScreenState.kt | derived labels and screen helpers |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsNavigationRail.kt | navigation rail extraction |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsUiStateModel.kt | main `SettingsUiState` home |
| [x] | [x] | [x] | modified | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsViewModel.kt | registration entrypoints and main coordinator |
| [x] | [x] | [x] | modified | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsStateBindings.kt | preference snapshot observation surface |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsObserverRegistrations.kt | observer registration splits |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsDerivedStateObservers.kt | provider diagnostics and category derived flows |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsGuideDefaultCategoryBindings.kt | guide category options flow |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsPreferenceSnapshotMapper.kt | snapshot-to-ui-state mapping |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsPreferenceModels.kt | preference snapshot and sync enums |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsOperationalModels.kt | diagnostics and maintenance models |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsAppUpdateModels.kt | app update model split |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsAppUpdateFormatting.kt | app update label formatting |

## Phase 2: Shared UI And Dialog Infrastructure

Reviewed on 2026-04-29. No drift or wiring findings recorded in this phase.

| Done | Drift | Wiring | Status | File | Focus |
| --- | --- | --- | --- | --- | --- |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsRowComponents.kt | reusable settings rows and quick-filter dialog |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsSelectionDialogComponents.kt | selection dialog shell and level options |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsSelectionDialogs.kt | quality and sort selection dialogs |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsSharedUiComponents.kt | shared chips, overview cards, text field |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsFormatting.kt | shared formatting helpers |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsTimeoutValueDialog.kt | timeout editing dialog |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsValueDialogs.kt | timer, offset, and text value dialogs |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsModeDialogs.kt | mode picker dialogs |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsPlayerPreferenceDialogs.kt | player preference dialog bundle |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsPreferenceDialogs.kt | preference dialog aggregation |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsProtectionDialogs.kt | parental PIN and level dialogs |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsLiveTvComponents.kt | live TV and parental card components |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsBackupAboutSections.kt | backup and about sections |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsBackupImportPreviewDialog.kt | backup import preview dialog |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsSyncOverlay.kt | sync blocking overlay |

## Phase 3: Browsing, Provider, And EPG Slices

Reviewed on 2026-04-29. No drift or wiring findings recorded in this phase.

| Done | Drift | Wiring | Status | File | Focus |
| --- | --- | --- | --- | --- | --- |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsBrowsingSection.kt | browsing section extraction |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsPlaybackSection.kt | playback section extraction |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsPrivacySection.kt | privacy section extraction |
| [x] | [x] | [x] | modified | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsProviderSection.kt | provider section orchestration |
| [x] | [x] | [x] | modified | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsProviderComponents.kt | provider component call sites |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsProviderUiComponents.kt | provider tabs, badges, compact stats |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsProviderCardSections.kt | provider actions and warning panels |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsProviderDiagnosticsPanel.kt | provider diagnostics rendering |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsProviderManagementDialogs.kt | provider management dialog wiring |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsProviderSyncDialogs.kt | provider sync dialog flows |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsCombinedM3uComponents.kt | combined M3U widgets and dialogs |
| [x] | [x] | [x] | modified | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsEpgSection.kt | EPG section orchestration |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsEpgAssignmentComponents.kt | provider assignment UI |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsEpgSourceComponents.kt | EPG source cards and add flow |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsInternetSpeedTestCard.kt | speed test card extraction |

## Phase 4: Recording Slice

Reviewed on 2026-04-29. Clean after restoring dialog ownership to the top-level settings dialog hub.

| Done | Drift | Wiring | Status | File | Focus |
| --- | --- | --- | --- | --- | --- |
| [x] | [x] | [x] | modified | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsRecordingComponents.kt | legacy recording component call sites |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsRecordingSection.kt | recording section and browser entrypoint |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsRecordingDialogs.kt | recording-related dialogs |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsRecordingDashboardCards.kt | dashboard cards |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsRecordingItemComponents.kt | recording item cards and actions |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsRecordingBrowserDialog.kt | recording browser shell |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsRecordingBrowserDisplay.kt | browser display helpers |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsRecordingBrowserSidebarControls.kt | browser filters and search |
| [x] | [x] | [x] | new | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsRecordingBrowserDetailSections.kt | browser detail metrics and actions |

Phase 4 note: `SettingsRecordingDialogs.kt` now owns only recording-specific dialogs. `BackupImportPreviewDialog` and the clear-history confirmation were returned to `SettingsScreenDialogs.kt`, matching the top-level ownership shape used in `master` without changing visible behavior.

## Removed Source Files To Map During Review

These are not review targets on their own, but they are the original surfaces to compare against when checking extraction drift.

| Done | Drift | Wiring | Status | File | Focus |
| --- | --- | --- | --- | --- | --- |
| [x] | [x] | - | deleted | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsDialogComponents.kt | compare old dialog logic against extracted dialog files |
| [x] | [x] | - | deleted | app/src/main/java/com/streamvault/app/ui/screens/settings/SettingsViewModelModels.kt | compare old models and helpers against extracted model files |

## Notes

- `Drift` means the extracted file still matches the behavior that lived in `master`.
- `Wiring` means the current branch still calls, observes, or renders that behavior from the correct owner.
- If a row fails review, leave `Done` unchecked and add a short note in the `Focus` column or below the table before moving on.