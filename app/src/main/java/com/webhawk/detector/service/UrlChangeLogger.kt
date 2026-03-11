package com.webhawk.detector.service

import android.util.Log
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UrlChangeLogger collects URL events from the AccessibilityService stream,
 * deduplicates rapid-fire events, detects when navigation has settled,
 * and emits a completed RedirectChain once the sequence is stable.
 *
 * Thread-safety:
 *   - entries list is protected by [mutex] (accessed from Default coroutines + Main thread)
 *   - double-stop is prevented by [activeTracking] AtomicBoolean CAS
 *   - stale StateFlow replay is filtered by [trackingStartTime]
 */
class UrlChangeLogger(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    companion object {
        private const val TAG = "WebHawk.Logger"
        private const val SETTLE_TIMEOUT_MS = 3_000L
        private const val MIN_REDIRECT_INTERVAL_MS = 50L
    }

    private val _currentChain = MutableStateFlow<RedirectChain?>(null)
    val currentChain: StateFlow<RedirectChain?> = _currentChain.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    // Protects [entries] against concurrent access from multiple coroutines
    private val mutex = Mutex()
    private val entries = mutableListOf<UrlEntry>()

    // AtomicBoolean CAS prevents two concurrent stopTracking() calls from both emitting
    private val activeTracking = AtomicBoolean(false)

    private var settleJob: Job? = null
    private var trackingJob: Job? = null

    // Records wall-clock time when tracking began; used to discard stale StateFlow replay
    @Volatile private var trackingStartTime: Long = 0L

    /**
     * Start monitoring for a specific seed URL. Collects all subsequent URL
     * changes until navigation has settled for SETTLE_TIMEOUT_MS.
     */
    fun startTracking(seedUrl: String) {
        Log.d(TAG, "══════════════════════════════════════════")
        Log.d(TAG, "startTracking()  seed=$seedUrl")

        // Teardown any existing session WITHOUT emitting (we're starting fresh)
        activeTracking.set(false)
        settleJob?.cancel().also { settleJob = null }
        trackingJob?.cancel().also { trackingJob = null }
        _isTracking.value = false

        // Record start time BEFORE launching coroutines so the collect filter
        // can reject the StateFlow's immediately-replayed last value.
        trackingStartTime = System.currentTimeMillis()

        // Initialise entries synchronously — no other coroutine is alive at this point
        entries.clear()
        entries.add(UrlEntry(url = seedUrl, timestamp = trackingStartTime))
        Log.d(TAG, "  trackingStartTime = $trackingStartTime")
        Log.d(TAG, "  entries seeded    : [$seedUrl]")

        // Only after entries are ready, mark as active
        activeTracking.set(true)
        _isTracking.value = true

        trackingJob = scope.launch {
            WebHawkAccessibilityService.urlStream.collect { entry ->
                if (!activeTracking.get()) return@collect
                entry ?: return@collect

                // ── FIX: discard stale StateFlow replay ──────────────────────────────
                // StateFlow always replays its last value to new collectors. If Chrome
                // emitted a URL before this tracking session began, its timestamp will
                // be older than trackingStartTime and must be ignored.
                if (entry.timestamp < trackingStartTime) {
                    Log.d(TAG, "  SKIP stale replay  ts=${entry.timestamp} < start=${trackingStartTime}  url=${entry.url}")
                    return@collect
                }

                var shouldResetTimer = false
                mutex.withLock {
                    shouldResetTimer = processUrlEvent(entry)
                }
                if (shouldResetTimer) resetSettleTimer()
            }
        }

        resetSettleTimer()
        Log.d(TAG, "  tracking started — settle timeout ${SETTLE_TIMEOUT_MS}ms")
    }

    /**
     * Force-stop tracking and emit whatever chain was collected so far.
     * Safe to call from any thread; only the first concurrent caller proceeds
     * (AtomicBoolean CAS prevents duplicate emissions).
     */
    fun stopTracking() {
        // ── FIX: double-stop guard ────────────────────────────────────────────────
        // compareAndSet returns true only for the ONE caller that transitions
        // activeTracking from true→false. Any concurrent/duplicate call returns false
        // and is silently discarded.
        if (!activeTracking.compareAndSet(true, false)) {
            Log.d(TAG, "stopTracking() — already stopped or duplicate call, ignored")
            return
        }

        Log.d(TAG, "══════════════════════════════════════════")
        Log.d(TAG, "stopTracking() — finalising chain")

        settleJob?.cancel().also { settleJob = null }
        trackingJob?.cancel().also { trackingJob = null }
        _isTracking.value = false

        scope.launch {
            mutex.withLock {
                finalizeAndEmit()
            }
        }
    }

    private fun resetSettleTimer() {
        settleJob?.cancel()
        settleJob = scope.launch {
            Log.v(TAG, "  settle timer armed for ${SETTLE_TIMEOUT_MS}ms")
            delay(SETTLE_TIMEOUT_MS)
            Log.d(TAG, "  settle timer fired")
            stopTracking()
        }
    }

    /**
     * Evaluate one incoming URL event against the current chain tail.
     * Must be called while holding [mutex].
     * @return true if the settle timer should be reset (new entry was accepted)
     */
    private fun processUrlEvent(entry: UrlEntry): Boolean {
        val last = entries.lastOrNull() ?: return false

        if (entry.url == last.url) {
            Log.v(TAG, "  SKIP duplicate     : ${entry.url}")
            return false
        }

        val timeDiff = entry.timestamp - last.timestamp
        if (timeDiff < MIN_REDIRECT_INTERVAL_MS) {
            Log.d(TAG, "  SKIP debounce (${timeDiff}ms < ${MIN_REDIRECT_INTERVAL_MS}ms): ${entry.url}")
            return false
        }

        entries.add(entry)
        val redirectIndex = entries.size - 1
        Log.i(TAG, "  ✔ redirect #$redirectIndex  (+${timeDiff}ms)")
        Log.i(TAG, "       from: ${last.url}")
        Log.i(TAG, "         to: ${entry.url}")
        Log.i(TAG, "    chain[${entries.size}]: ${entries.map { it.url }}")
        return true
    }

    /**
     * Build and emit the final [RedirectChain]. Must be called while holding [mutex].
     */
    private fun finalizeAndEmit() {
        val snapshot = entries.toList()
        entries.clear()

        if (snapshot.isEmpty()) {
            Log.w(TAG, "finalizeAndEmit: entries empty — nothing to emit")
            return
        }

        val chain = RedirectChain(
            entries = snapshot,
            startUrl = snapshot.first().url,
            finalUrl = snapshot.last().url,
            durationMs = snapshot.last().timestamp - snapshot.first().timestamp
        )

        Log.i(TAG, "══════════ CHAIN FINALISED ════════════")
        Log.i(TAG, "  total entries  : ${chain.entries.size}")
        Log.i(TAG, "  redirectCount  : ${chain.redirectCount}")
        Log.i(TAG, "  durationMs     : ${chain.durationMs}")
        Log.i(TAG, "  startUrl       : ${chain.startUrl}")
        Log.i(TAG, "  finalUrl       : ${chain.finalUrl}")
        chain.entries.forEachIndexed { i, e ->
            val label = if (i == 0) "[seed]" else "[ →$i ]" 
            Log.i(TAG, "  $label ${e.url}")
        }
        Log.i(TAG, "══════════════════════════════════════")

        _currentChain.value = chain
    }

    fun clearChain() {
        Log.d(TAG, "clearChain()")
        _currentChain.value = null
    }
}
