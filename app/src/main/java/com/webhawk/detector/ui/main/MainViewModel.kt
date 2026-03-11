package com.webhawk.detector.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.webhawk.detector.WebHawkApp
import com.webhawk.detector.data.model.FlaggedUrl
import com.webhawk.detector.data.model.RedirectChain
import com.webhawk.detector.data.model.RiskResult
import com.webhawk.detector.engine.FeatureExtractor
import com.webhawk.detector.engine.RiskEngine
import com.webhawk.detector.service.WebHawkAccessibilityService
import kotlinx.coroutines.Job
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

    companion object {
        private const val TAG = "WebHawk.ViewModel"
    }

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

    // FIX Bug 3: hold a reference so we can cancel the old collector before starting a new scan.
    // Without this, every call to startLiveTracking() piles up an additional collector on
    // logger.currentChain, causing analyzeChain() to be invoked N times on the Nth scan.
    private var chainCollectorJob: Job? = null

    fun scanUrl(url: String) {
        if (url.isBlank()) {
            _scanState.value = ScanUiState.Error("Please enter a URL")
            return
        }
        lastCheckedUrl = url.trim()
        Log.d(TAG, "scanUrl: ${lastCheckedUrl}")
        viewModelScope.launch {
            // Step 1: Check global flag database
            _scanState.value = ScanUiState.CheckingDatabase
            Log.d(TAG, "  checking database...")
            val flagResult = flagRepo.checkUrl(lastCheckedUrl)
            val flaggedEntry = flagResult.getOrNull()

            if (flaggedEntry != null && flaggedEntry.flagCount > 0) {
                Log.d(TAG, "  URL is flagged (count=${flaggedEntry.flagCount}) — showing caution")
                _scanState.value = ScanUiState.Flagged(flaggedEntry)
            } else {
                Log.d(TAG, "  URL clean in DB — starting live tracking")
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
        Log.d(TAG, "startLiveTracking: $url")

        // Cancel any leftover collector from a previous scan BEFORE starting a new one.
        // Without this guard, N scans produce N simultaneous collectors and analyzeChain()
        // is called N times when the chain is finally emitted.
        chainCollectorJob?.cancel()
        chainCollectorJob = null

        logger.clearChain()
        logger.startTracking(url)

        chainCollectorJob = viewModelScope.launch {
            logger.currentChain.collect { chain ->
                if (chain != null && !logger.isTracking.value) {
                    Log.d(TAG, "Chain received — redirectCount=${chain.redirectCount}, duration=${chain.durationMs}ms")
                    analyzeChain(chain, url)
                    // Collector has done its job; cancel so it doesn't fire again on replay.
                    chainCollectorJob?.cancel()
                }
            }
        }
    }

    fun stopTrackingAndAnalyze() {
        logger.stopTracking()
    }

    private suspend fun analyzeChain(chain: RedirectChain, originalUrl: String) {
        _scanState.value = ScanUiState.Analyzing
        Log.d(TAG, "analyzeChain: entries=${chain.entries.size}, redirects=${chain.redirectCount}")

        val features = FeatureExtractor.extract(chain)
        Log.d(TAG, "  features: redirects=${features.redirectCount}, uniqueDomains=${features.uniqueDomains}, shortener=${features.hasShortener}, suspTld=${features.hasSuspiciousTld}, avgInterval=${String.format("%.3f", features.avgIntervalSeconds)}s")

        val globalRep = flagRepo.getGlobalReputation(originalUrl)
        Log.d(TAG, "  globalReputation=$globalRep")

        val result = RiskEngine.evaluate(features, globalRep)
        Log.d(TAG, "  result: level=${result.riskLevel}, finalRisk=${String.format("%.4f", result.finalRisk)}")

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
        Log.d(TAG, "reset()")
        chainCollectorJob?.cancel()
        chainCollectorJob = null
        logger.stopTracking()
        logger.clearChain()
        lastRiskResult = null
        lastCheckedUrl = ""
        _scanState.value = ScanUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        chainCollectorJob?.cancel()
        logger.stopTracking()
    }
}
