package com.streamvault.data.validation

import com.streamvault.data.util.ProviderInputSanitizer
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.manager.ProviderSetupInputValidator
import com.streamvault.domain.manager.ValidatedM3uProviderInput
import com.streamvault.domain.manager.ValidatedXtreamProviderInput
import com.streamvault.domain.model.Result
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderSetupInputValidatorImpl @Inject constructor() : ProviderSetupInputValidator {

    override fun validateXtream(
        serverUrl: String,
        username: String,
        name: String
    ): Result<ValidatedXtreamProviderInput> {
        val normalizedServerUrl = ProviderInputSanitizer.normalizeUrl(serverUrl)
        val normalizedUsername = ProviderInputSanitizer.normalizeUsername(username)
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)

        if (normalizedServerUrl.isBlank()) {
            return Result.error("Please enter server URL")
        }
        ProviderInputSanitizer.validateUrl(normalizedServerUrl)?.let { message ->
            return Result.error(message)
        }
        UrlSecurityPolicy.validateXtreamServerUrl(normalizedServerUrl)?.let { message ->
            return Result.error(message)
        }
        if (normalizedUsername.isBlank()) {
            return Result.error("Please enter username")
        }

        return Result.success(
            ValidatedXtreamProviderInput(
                serverUrl = normalizedServerUrl,
                username = normalizedUsername,
                name = normalizedName
            )
        )
    }

    override fun validateM3u(
        url: String,
        name: String
    ): Result<ValidatedM3uProviderInput> {
        val normalizedUrl = ProviderInputSanitizer.normalizeUrl(url)
        val normalizedName = ProviderInputSanitizer.normalizeProviderName(name)

        if (normalizedUrl.isBlank()) {
            return Result.error("Please enter M3U URL")
        }
        ProviderInputSanitizer.validateUrl(normalizedUrl)?.let { message ->
            return Result.error(message)
        }
        UrlSecurityPolicy.validatePlaylistSourceUrl(normalizedUrl)?.let { message ->
            return Result.error(message)
        }

        return Result.success(
            ValidatedM3uProviderInput(
                url = normalizedUrl,
                name = normalizedName
            )
        )
    }
}