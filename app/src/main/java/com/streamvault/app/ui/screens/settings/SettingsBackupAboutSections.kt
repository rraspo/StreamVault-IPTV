package com.streamvault.app.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.BuildConfig
import com.streamvault.app.R
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.Secondary

internal fun LazyListScope.settingsBackupSection(
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit
) {
    item {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            TvClickableSurface(
                onClick = onCreateBackup,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Primary.copy(alpha = 0.12f),
                    focusedContainerColor = Primary.copy(alpha = 0.28f)
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "\u2191", style = MaterialTheme.typography.titleLarge, color = Primary, fontWeight = FontWeight.Bold)
                    Text(text = stringResource(R.string.settings_backup_data), style = MaterialTheme.typography.titleSmall, color = Primary, textAlign = TextAlign.Center)
                    Text(text = stringResource(R.string.settings_backup_subtitle), style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim, textAlign = TextAlign.Center)
                }
            }
            TvClickableSurface(
                onClick = onRestoreBackup,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Secondary.copy(alpha = 0.12f),
                    focusedContainerColor = Secondary.copy(alpha = 0.28f)
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "\u2193", style = MaterialTheme.typography.titleLarge, color = Secondary, fontWeight = FontWeight.Bold)
                    Text(text = stringResource(R.string.settings_restore_data), style = MaterialTheme.typography.titleSmall, color = Secondary, textAlign = TextAlign.Center)
                    Text(text = stringResource(R.string.settings_backup_subtitle), style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

internal fun LazyListScope.settingsAboutSection(
    uiState: SettingsUiState,
    context: Context,
    buildVerificationLabel: String,
    onOpenUri: (String) -> Unit,
    onCheckForUpdates: () -> Unit,
    onInstallDownloadedUpdate: () -> Unit,
    onDownloadLatestUpdate: () -> Unit,
    onSetAutoCheckAppUpdates: (Boolean) -> Unit,
    onSetAutoDownloadAppUpdates: (Boolean) -> Unit,
    onRefreshDownloadState: () -> Unit
) {
    item {
        val downloadStatus = uiState.appUpdate.downloadStatus
        LaunchedEffect(downloadStatus) {
            if (downloadStatus == com.streamvault.app.update.AppUpdateDownloadStatus.Downloading) {
                while (true) {
                    kotlinx.coroutines.delay(2000L)
                    onRefreshDownloadState()
                }
            }
        }
        SettingsSectionHeader(
            title = stringResource(R.string.settings_updates_title),
            subtitle = stringResource(R.string.settings_updates_subtitle)
        )
        SettingsRow(label = stringResource(R.string.settings_app_version), value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        SwitchSettingsRow(
            label = stringResource(R.string.settings_update_auto_check),
            value = stringResource(
                if (uiState.autoCheckAppUpdates) R.string.settings_enabled else R.string.settings_disabled
            ),
            checked = uiState.autoCheckAppUpdates,
            onCheckedChange = onSetAutoCheckAppUpdates
        )
        if (uiState.autoCheckAppUpdates) {
            SwitchSettingsRow(
                label = stringResource(R.string.settings_update_auto_download),
                value = stringResource(
                    if (uiState.autoDownloadAppUpdates) R.string.settings_enabled else R.string.settings_disabled
                ),
                checked = uiState.autoDownloadAppUpdates,
                onCheckedChange = onSetAutoDownloadAppUpdates
            )
        }
        SettingsRow(
            label = stringResource(R.string.settings_update_latest_release),
            value = formatLatestReleaseLabel(uiState.appUpdate, context)
        )
        SettingsRow(
            label = stringResource(R.string.settings_update_status),
            value = formatUpdateStatusLabel(uiState.appUpdate, context)
        )
        SettingsRow(
            label = stringResource(R.string.settings_update_last_checked),
            value = formatUpdateCheckTimeLabel(uiState.appUpdate.lastCheckedAt, context)
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_update_check_now),
            value = stringResource(
                if (uiState.isCheckingForUpdates) R.string.settings_update_checking else R.string.settings_update_check_action
            ),
            onClick = {
                if (!uiState.isCheckingForUpdates) {
                    onCheckForUpdates()
                }
            }
        )
        if (shouldShowUpdateDownloadAction(uiState.appUpdate)) {
            ClickableSettingsRow(
                label = stringResource(R.string.settings_update_download),
                value = formatUpdateDownloadLabel(uiState.appUpdate, context),
                onClick = {
                    if (uiState.appUpdate.downloadStatus == com.streamvault.app.update.AppUpdateDownloadStatus.Downloaded) {
                        onInstallDownloadedUpdate()
                    } else if (uiState.appUpdate.downloadStatus != com.streamvault.app.update.AppUpdateDownloadStatus.Downloading) {
                        onDownloadLatestUpdate()
                    }
                }
            )
        }
        if (!uiState.appUpdate.releaseUrl.isNullOrBlank()) {
            ClickableSettingsRow(
                label = stringResource(R.string.settings_update_view_release),
                value = uiState.appUpdate.latestVersionName ?: stringResource(R.string.settings_update_release_notes),
                onClick = { onOpenUri(uiState.appUpdate.releaseUrl.orEmpty()) }
            )
        }
        if (!uiState.appUpdate.errorMessage.isNullOrBlank()) {
            SettingsRow(
                label = stringResource(R.string.settings_update_error),
                value = uiState.appUpdate.errorMessage.orEmpty()
            )
        }
    }

    item {
        SettingsRow(label = stringResource(R.string.settings_build), value = stringResource(R.string.settings_build_desc))
        SettingsRow(label = stringResource(R.string.settings_build_verification), value = buildVerificationLabel)
        SettingsRow(label = stringResource(R.string.settings_developed_by), value = stringResource(R.string.settings_developer_name))
        ClickableSettingsRow(
            label = stringResource(R.string.settings_github),
            value = stringResource(R.string.settings_github_url),
            onClick = { onOpenUri(context.getString(R.string.settings_github_url)) }
        )
        ClickableSettingsRow(
            label = stringResource(R.string.settings_donate),
            value = stringResource(R.string.settings_donate_url),
            onClick = { onOpenUri(context.getString(R.string.settings_donate_url)) }
        )
    }
}