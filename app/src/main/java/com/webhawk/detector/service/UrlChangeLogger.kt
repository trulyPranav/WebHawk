package com.webhawk.detector.service

import com.webhawk.detector.data.model.RedirectChain
import com.webhawk.detector.data.model.UrlEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UrlChangeLogger collects URL events from the AccessibilityService stream,
 * deduplicates rapid-fire events, detects when navigation has settled,
 * and emits a completed RedirectChain once the sequence is stable.
 */
class UrlChangeLogger(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    companion object {
        private const val SETTLE_TIMEOUT_MS = 3_000L
        private const val MIN_REDIRECT_INTERVAL_MS = 50L
    }

    private val _currentChain = MutableStateFlow<RedirectChain?>(null)
    val currentChain: StateFlow<RedirectChain?> = _currentChain.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val entries = mutableListOf<UrlEntry>()
    private var settleJob: Job? = null
    private var trackingJob: Job? = null

    /**
     * Start monitoring for a specific seed URL. Collects all subsequent URL
     * changes until navigation has settled for SETTLE_TIMEOUT_MS.
     */
    fun startTracking(seedUrl: String) {
        stopTracking()
        entries.clear()
        entries.add(UrlEntry(url = seedUrl, timestamp = System.currentTimeMillis()))
        _isTracking.value = true

        trackingJob = scope.launch {
            WebHawkAccessibilityService.urlStream.collect { entry ->
                if (!_isTracking.value) return@collect
                entry ?: return@collect

                val last = entries.lastOrNull()
                if (last != null) {
                    val timeDiff = entry.timestamp - last.timestamp
                    // Deduplicate: skip if URL is same or arrived too fast (debounce)
                    if (entry.url == last.url) return@collect
                    if (timeDiff < MIN_REDIRECT_INTERVAL_MS) return@collect
                }

                entries.add(entry)
                resetSettleTimer()
            }
        }

        resetSettleTimer()
    }

    /**
     * Force-stop tracking and emit whatever chain was collected so far.
     */
    fun stopTracking() {
        settleJob?.cancel()
        trackingJob?.cancel()
        _isTracking.value = false
        if (entries.isNotEmpty()) emitChain()
    }

    private fun resetSettleTimer() {
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(SETTLE_TIMEOUT_MS)
            stopTracking()
        }
    }

    private fun emitChain() {
        val snapshot = entries.toList()
        if (snapshot.isEmpty()) return

        val startMs = snapshot.first().timestamp
        val endMs = snapshot.last().timestamp

        _currentChain.value = RedirectChain(
            entries = snapshot,
            startUrl = snapshot.first().url,
            finalUrl = snapshot.last().url,
            durationMs = endMs - startMs
        )
        entries.clear()
    }

    fun clearChain() {
        _currentChain.value = null
    }
}
