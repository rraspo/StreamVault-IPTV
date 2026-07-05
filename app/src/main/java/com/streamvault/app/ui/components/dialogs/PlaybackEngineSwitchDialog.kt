package com.streamvault.app.ui.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.interaction.mouseClickable
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import kotlinx.coroutines.delay

/**
 * Confirms switching the playback engine. Because the engine is chosen once at app
 * startup, applying the change requires a restart, so this makes the restart explicit
 * rather than letting the toggle silently do nothing until the next launch.
 */
@Composable
fun PlaybackEngineSwitchDialog(
    switchingToVlc: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    var canInteract by remember { mutableStateOf(false) }
    val isTelevisionDevice = rememberIsTelevisionDevice()
    LaunchedEffect(Unit) {
        delay(500)
        canInteract = true
    }

    val safeDismiss = { if (canInteract) onDismissRequest() }
    val targetEngine = stringResource(
        if (switchingToVlc) R.string.settings_engine_name_vlc else R.string.settings_engine_name_exoplayer
    )

    Dialog(
        onDismissRequest = safeDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogContent: @Composable (Modifier) -> Unit = { resolvedModifier ->
            Surface(
                modifier = resolvedModifier,
                shape = RoundedCornerShape(24.dp),
                colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_engine_switch_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = OnSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_engine_switch_body, targetEngine),
                        style = MaterialTheme.typography.bodyLarge,
                        color = OnSurfaceDim
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = safeDismiss,
                            modifier = Modifier.mouseClickable(onClick = safeDismiss),
                            colors = ButtonDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.08f),
                                contentColor = OnSurface
                            )
                        ) {
                            Text(stringResource(R.string.settings_engine_switch_cancel))
                        }
                        Button(
                            onClick = { if (canInteract) onConfirm() },
                            modifier = Modifier.mouseClickable(onClick = { if (canInteract) onConfirm() }),
                            colors = ButtonDefaults.colors(
                                containerColor = Primary,
                                contentColor = Color.White
                            )
                        ) {
                            Text(stringResource(R.string.settings_engine_switch_confirm))
                        }
                    }
                }
            }
        }

        if (isTelevisionDevice) {
            dialogContent(Modifier.fillMaxWidth(0.42f))
        } else {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val dialogModifier = when {
                    maxWidth < 700.dp -> Modifier.fillMaxWidth(0.9f)
                    maxWidth < 1280.dp -> Modifier.fillMaxWidth(0.56f)
                    else -> Modifier.fillMaxWidth(0.42f)
                }
                dialogContent(dialogModifier)
            }
        }
    }
}
