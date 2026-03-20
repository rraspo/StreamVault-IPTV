package com.streamvault.app.tvinput

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.media.tv.TvInputInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.streamvault.app.MainActivity
import com.streamvault.app.navigation.Routes
import com.streamvault.app.ui.theme.ErrorColor
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.StreamVaultTheme
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.domain.model.Provider
import com.streamvault.domain.repository.ProviderRepository
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TvInputSetupActivity : ComponentActivity() {
    private val viewModel: TvInputSetupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inputId = intent.getStringExtra(TvInputInfo.EXTRA_INPUT_ID)
            ?: ComponentName(this, StreamVaultTvInputService::class.java).flattenToShortString()
        viewModel.startSetup(inputId)
        setContent {
            StreamVaultTheme {
                TvInputSetupRoute(
                    onOpenProviderSetup = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_EXTERNAL_ROUTE, Routes.providerSetup())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                    },
                    onRetry = { viewModel.retry() },
                    onFinishSuccess = { resolvedInputId ->
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(TvInputInfo.EXTRA_INPUT_ID, resolvedInputId)
                        )
                        finish()
                    },
                    onFinishCanceled = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.resumeIfAwaitingProvider()
    }
}

@Composable
private fun TvInputSetupRoute(
    onOpenProviderSetup: () -> Unit,
    onRetry: () -> Unit,
    onFinishSuccess: (String) -> Unit,
    onFinishCanceled: () -> Unit,
    viewModel: TvInputSetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.status, uiState.inputId) {
        val resolvedInputId = uiState.inputId
        if (uiState.status == TvInputSetupStatus.SUCCESS && resolvedInputId != null) {
            delay(350)
            onFinishSuccess(resolvedInputId)
        }
    }

    TvInputSetupScreen(
        uiState = uiState,
        onOpenProviderSetup = onOpenProviderSetup,
        onRetry = onRetry,
        onCancel = onFinishCanceled
    )
}

@Composable
private fun TvInputSetupScreen(
    uiState: TvInputSetupUiState,
    onOpenProviderSetup: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = SurfaceElevated,
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when (uiState.status) {
                        TvInputSetupStatus.SCANNING -> "Scanning StreamVault channels"
                        TvInputSetupStatus.SUCCESS -> "Live TV is ready"
                        TvInputSetupStatus.NO_PROVIDER -> "No IPTV provider configured"
                        TvInputSetupStatus.ERROR -> "Auto scan failed"
                        TvInputSetupStatus.IDLE -> "Preparing Live TV integration"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = OnBackground,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = when (uiState.status) {
                        TvInputSetupStatus.SCANNING -> "StreamVault is syncing your active provider into Android TV Live TV."
                        TvInputSetupStatus.SUCCESS -> uiState.message ?: "Your channels were synced successfully."
                        TvInputSetupStatus.NO_PROVIDER -> "Add at least one IPTV provider in StreamVault, then return here to scan channels into Android TV Live TV."
                        TvInputSetupStatus.ERROR -> uiState.message ?: "The TV input sync did not complete."
                        TvInputSetupStatus.IDLE -> "Please wait."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnSurfaceDim,
                    textAlign = TextAlign.Center
                )

                if (uiState.status == TvInputSetupStatus.SCANNING) {
                    CircularProgressIndicator(color = Primary)
                }

                if (uiState.providerName != null && uiState.status != TvInputSetupStatus.NO_PROVIDER) {
                    Text(
                        text = "Source: ${uiState.providerName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Primary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                when (uiState.status) {
                    TvInputSetupStatus.NO_PROVIDER -> {
                        Button(onClick = onOpenProviderSetup) {
                            Text("Open StreamVault Setup")
                        }
                        Button(onClick = onRetry) {
                            Text("Try Auto Scan Again")
                        }
                        Button(onClick = onCancel) {
                            Text("Cancel")
                        }
                    }
                    TvInputSetupStatus.ERROR -> {
                        Text(
                            text = uiState.message.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = ErrorColor,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = onRetry) {
                            Text("Retry Auto Scan")
                        }
                        Button(onClick = onCancel) {
                            Text("Cancel")
                        }
                    }
                    TvInputSetupStatus.SUCCESS -> {
                        Text(
                            text = "Android TV Live TV can now open your StreamVault channels.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim,
                            textAlign = TextAlign.Center
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

@HiltViewModel
class TvInputSetupViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val tvInputChannelSyncManager: TvInputChannelSyncManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(TvInputSetupUiState())
    val uiState: StateFlow<TvInputSetupUiState> = _uiState.asStateFlow()

    fun startSetup(inputId: String) {
        if (_uiState.value.status == TvInputSetupStatus.SCANNING) return
        scan(inputId)
    }

    fun retry() {
        val inputId = _uiState.value.inputId ?: return
        scan(inputId)
    }

    fun resumeIfAwaitingProvider() {
        val state = _uiState.value
        if (state.status == TvInputSetupStatus.NO_PROVIDER && state.inputId != null) {
            scan(state.inputId)
        }
    }

    private fun scan(inputId: String) {
        viewModelScope.launch {
            _uiState.value = TvInputSetupUiState(
                inputId = inputId,
                status = TvInputSetupStatus.SCANNING
            )

            val providers = providerRepository.getProviders().first()
            if (providers.isEmpty()) {
                _uiState.value = TvInputSetupUiState(
                    inputId = inputId,
                    status = TvInputSetupStatus.NO_PROVIDER
                )
                return@launch
            }

            var activeProvider = providerRepository.getActiveProvider().first()
            if (activeProvider == null) {
                val fallbackProvider = providers.first()
                providerRepository.setActiveProvider(fallbackProvider.id)
                activeProvider = fallbackProvider
            }

            val syncResult = tvInputChannelSyncManager.refreshTvInputCatalogResult()
            syncResult.onSuccess {
                _uiState.value = TvInputSetupUiState(
                    inputId = inputId,
                    status = TvInputSetupStatus.SUCCESS,
                    providerName = activeProvider?.name,
                    message = "Synced ${providers.size} configured provider(s). Android TV Live TV will use ${activeProvider?.name ?: "your active source"}."
                )
            }.onFailure { throwable ->
                _uiState.value = TvInputSetupUiState(
                    inputId = inputId,
                    status = TvInputSetupStatus.ERROR,
                    providerName = activeProvider?.name,
                    message = throwable.message ?: "Unknown TV input sync error"
                )
            }
        }
    }
}

data class TvInputSetupUiState(
    val inputId: String? = null,
    val status: TvInputSetupStatus = TvInputSetupStatus.IDLE,
    val providerName: String? = null,
    val message: String? = null
)

enum class TvInputSetupStatus {
    IDLE,
    SCANNING,
    SUCCESS,
    NO_PROVIDER,
    ERROR
}