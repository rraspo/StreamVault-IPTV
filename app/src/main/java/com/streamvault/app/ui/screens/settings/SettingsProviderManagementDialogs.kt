package com.streamvault.app.ui.screens.settings

import androidx.compose.runtime.Composable

@Composable
internal fun SettingsProviderManagementDialogs(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    providerState: SettingsProviderSectionState
) {
    val pendingSyncProvider = providerState.pendingSyncProviderId?.let { providerId ->
        uiState.providers.firstOrNull { it.id == providerId }
    }

    if (providerState.showCreateCombinedDialog) {
        CreateCombinedM3uDialog(
            providers = uiState.availableM3uProviders,
            onDismiss = { providerState.showCreateCombinedDialog = false },
            onCreate = { name, providerIds ->
                providerState.showCreateCombinedDialog = false
                viewModel.createCombinedProfile(name, providerIds)
            }
        )
    }

    if (providerState.showRenameCombinedDialog) {
        val selectedProfile = uiState.combinedProfiles.firstOrNull { it.id == providerState.selectedCombinedProfileId }
        if (selectedProfile != null) {
            RenameCombinedM3uDialog(
                profile = selectedProfile,
                onDismiss = { providerState.showRenameCombinedDialog = false },
                onRename = { name ->
                    providerState.showRenameCombinedDialog = false
                    viewModel.renameCombinedProfile(selectedProfile.id, name)
                }
            )
        }
    }

    if (providerState.showAddCombinedMemberDialog) {
        val selectedProfile = uiState.combinedProfiles.firstOrNull { it.id == providerState.selectedCombinedProfileId }
        if (selectedProfile != null) {
            AddCombinedProviderDialog(
                profile = selectedProfile,
                availableProviders = uiState.availableM3uProviders,
                onDismiss = { providerState.showAddCombinedMemberDialog = false },
                onAddProvider = { providerId ->
                    providerState.showAddCombinedMemberDialog = false
                    viewModel.addProviderToCombinedProfile(selectedProfile.id, providerId)
                }
            )
        }
    }

    if (providerState.showProviderSyncDialog && pendingSyncProvider != null) {
        ProviderSyncOptionsDialog(
            provider = pendingSyncProvider,
            onDismiss = {
                providerState.showProviderSyncDialog = false
                providerState.pendingSyncProviderId = null
            },
            onSelect = { selection ->
                providerState.showProviderSyncDialog = false
                if (selection == null) {
                    providerState.showCustomProviderSyncDialog = true
                } else {
                    viewModel.syncProviderSection(pendingSyncProvider.id, selection)
                    providerState.pendingSyncProviderId = null
                }
            }
        )
    }

    if (providerState.showCustomProviderSyncDialog && pendingSyncProvider != null) {
        ProviderCustomSyncDialog(
            provider = pendingSyncProvider,
            selected = providerState.customSyncSelections,
            onToggle = { option ->
                providerState.customSyncSelections =
                    if (option in providerState.customSyncSelections) {
                        providerState.customSyncSelections - option
                    } else {
                        providerState.customSyncSelections + option
                    }
            },
            onDismiss = {
                providerState.showCustomProviderSyncDialog = false
                providerState.pendingSyncProviderId = null
            },
            onConfirm = {
                providerState.showCustomProviderSyncDialog = false
                viewModel.syncProviderCustom(pendingSyncProvider.id, providerState.customSyncSelections)
                providerState.pendingSyncProviderId = null
            }
        )
    }
}