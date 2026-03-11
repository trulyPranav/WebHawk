package com.webhawk.detector.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.webhawk.detector.WebHawkApp
import com.webhawk.detector.data.model.AppUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AuthUiState {
    object Idle : AuthUiState()
    object Loading : AuthUiState()
    data class Success(val user: AppUser) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as WebHawkApp).authRepository

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    val isLoggedIn: Boolean get() = repo.isLoggedIn

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = AuthUiState.Error("Email and password are required")
            return
        }
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = repo.login(email.trim(), password)
            _uiState.value = result.fold(
                onSuccess = { AuthUiState.Success(it) },
                onFailure = { AuthUiState.Error(friendlyError(it.message)) }
            )
        }
    }

    fun register(email: String, password: String, displayName: String) {
        if (email.isBlank() || password.isBlank() || displayName.isBlank()) {
            _uiState.value = AuthUiState.Error("All fields are required")
            return
        }
        if (password.length < 8) {
            _uiState.value = AuthUiState.Error("Password must be at least 8 characters")
            return
        }
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = repo.register(email.trim(), password, displayName.trim())
            _uiState.value = result.fold(
                onSuccess = { AuthUiState.Success(it) },
                onFailure = { AuthUiState.Error(friendlyError(it.message)) }
            )
        }
    }

    fun sendPasswordReset(email: String) {
        if (email.isBlank()) {
            _uiState.value = AuthUiState.Error("Enter your email address")
            return
        }
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = repo.sendPasswordReset(email.trim())
            _uiState.value = result.fold(
                onSuccess = { AuthUiState.Error("Reset email sent — check your inbox") },
                onFailure = { AuthUiState.Error(friendlyError(it.message)) }
            )
        }
    }

    fun resetState() {
        _uiState.value = AuthUiState.Idle
    }

    private fun friendlyError(raw: String?): String {
        return when {
            raw == null -> "An unknown error occurred"
            raw.contains("password") -> "Incorrect password"
            raw.contains("no user record") || raw.contains("user-not-found") -> "No account found with that email"
            raw.contains("email address is already") -> "An account with this email already exists"
            raw.contains("network") -> "Network error — check your connection"
            else -> raw
        }
    }
}
