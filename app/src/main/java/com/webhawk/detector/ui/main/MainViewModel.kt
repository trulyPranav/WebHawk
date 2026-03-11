package com.webhawk.detector.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.webhawk.detector.WebHawkApp
import com.webhawk.detector.data.model.FlaggedUrl
import com.webhawk.detector.data.model.RedirectChain
import com.webhawk.detector.data.model.RiskResult
import com.webhawk.detector.engine.FeatureExtractor
import com.webhawk.detector.engine.RiskEngine
import com.webhawk.detector.service.WebHawkAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ScanUiState {
    object Idle : ScanUiState()
    object CheckingDatabase : ScanUiState()
    data class Flagged(val flaggedUrl: FlaggedUrl) : ScanUiState()
    object Tracking : ScanUiState()
    object Analyzing : ScanUiState()
    data class Result(val riskResult: RiskResult, val wasFlagged: Boolean) : ScanUiState()
    data class Error(val message: String) : ScanUiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as WebHawkApp
    private val flagRepo = app.flagRepository
    private val authRepo = app.authRepository
    private val logger = app.urlChangeLogger

    private val _scanState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val scanState: StateFlow<ScanUiState> = _scanState.asStateFlow()

    val serviceRunning: StateFlow<Boolean> = WebHawkAccessibilityService.serviceRunning
    val isLoggedIn: Boolean get() = authRepo.isLoggedIn

    private var lastRiskResult: RiskResult? = null
    private var lastCheckedUrl: String = ""

    fun scanUrl(url: String) {
        if (url.isBlank()) {
            _scanState.value = ScanUiState.Error("Please enter a URL")
            return
        }
        lastCheckedUrl = url.trim()
        viewModelScope.launch {
            // Step 1: Check global flag database
            _scanState.value = ScanUiState.CheckingDatabase
            val flagResult = flagRepo.checkUrl(lastCheckedUrl)
            val flaggedEntry = flagResult.getOrNull()

            if (flaggedEntry != null && flaggedEntry.flagCount > 0) {
                // URL is in the flagged DB — show caution popup first
                _scanState.value = ScanUiState.Flagged(flaggedEntry)
            } else {
                // URL is clean in DB — proceed to live tracking
                startLiveTracking(lastCheckedUrl)
            }
        }
    }

    /**
     * Called when user taps "Continue Anyway" on the flagged caution screen.
     */
    fun continueWithFlaggedUrl() {
        startLiveTracking(lastCheckedUrl)
    }

    private fun startLiveTracking(url: String) {
        _scanState.value = ScanUiState.Tracking
        logger.clearChain()
        logger.startTracking(url)

        viewModelScope.launch {
            logger.currentChain.collect { chain ->
                if (chain != null && !logger.isTracking.value) {
                    analyzeChain(chain, url)
                }
            }
        }
    }

    fun stopTrackingAndAnalyze() {
        logger.stopTracking()
    }

    private suspend fun analyzeChain(chain: RedirectChain, originalUrl: String) {
        _scanState.value = ScanUiState.Analyzing

        val features = FeatureExtractor.extract(chain)
        val globalRep = flagRepo.getGlobalReputation(originalUrl)
        val result = RiskEngine.evaluate(features, globalRep)

        lastRiskResult = result
        val wasFlagged = flagRepo.checkUrl(originalUrl).getOrNull()?.flagCount?.let { it > 0 } ?: false
        _scanState.value = ScanUiState.Result(result, wasFlagged)
    }

    fun flagCurrentUrl() {
        val result = lastRiskResult ?: return
        val url = lastCheckedUrl
        if (url.isBlank()) return

        viewModelScope.launch {
            val chain = result.features.chain.entries.map { it.url }
            flagRepo.flagUrl(url, result.finalRisk, chain)
        }
    }

    fun reset() {
        logger.stopTracking()
        logger.clearChain()
        lastRiskResult = null
        lastCheckedUrl = ""
        _scanState.value = ScanUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        logger.stopTracking()
    }
}
