package com.webhawk.detector.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.webhawk.detector.data.model.UrlEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WebHawkAccessibilityService monitors Chromium-based browsers for URL changes.
 * It extracts URLs from the address bar and emits them to a shared state flow.
 * This is the core sensor of the detection pipeline.
 */
class WebHawkAccessibilityService : AccessibilityService() {

    companion object {
        private val _urlStream = MutableStateFlow<UrlEntry?>(null)
        val urlStream: StateFlow<UrlEntry?> = _urlStream.asStateFlow()

        private val _serviceRunning = MutableStateFlow(false)
        val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

        // Known address-bar view IDs in Chromium browsers
        private val ADDRESS_BAR_IDS = setOf(
            "com.android.chrome:id/url_bar",
            "org.chromium.chrome:id/url_bar",
            "com.chrome.beta:id/url_bar",
            "com.chrome.dev:id/url_bar",
            "com.chrome.canary:id/url_bar",
            "com.microsoft.emmx:id/url_bar",
            "com.brave.browser:id/url_bar",
            "com.opera.browser:id/url_bar",
            "com.opera.mini.native:id/url_bar"
        )
    }

    private var lastEmittedUrl: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info
        _serviceRunning.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val root = rootInActiveWindow ?: return

        try {
            val url = extractUrlFromTree(root)
            if (url != null && url != lastEmittedUrl && isValidUrl(url)) {
                lastEmittedUrl = url
                _urlStream.value = UrlEntry(
                    url = url,
                    timestamp = System.currentTimeMillis()
                )
            }
        } finally {
            root.recycle()
        }
    }

    private fun extractUrlFromTree(root: AccessibilityNodeInfo): String? {
        // Try known address-bar resource IDs first (fast path)
        for (id in ADDRESS_BAR_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val text = nodes[0].text?.toString()
                nodes.forEach { it.recycle() }
                if (!text.isNullOrBlank()) return normalizeUrl(text)
            }
        }

        // Fallback: heuristic BFS search for URL-like text in editable fields
        return findUrlHeuristic(root)
    }

    private fun findUrlHeuristic(node: AccessibilityNodeInfo): String? {
        if (node.isEditable) {
            val text = node.text?.toString()
            if (!text.isNullOrBlank() && looksLikeUrl(text)) {
                return normalizeUrl(text)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findUrlHeuristic(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun looksLikeUrl(text: String): Boolean {
        return text.startsWith("http://") ||
               text.startsWith("https://") ||
               text.contains(".") && !text.contains(" ") && text.length > 4
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("www.") -> "https://$trimmed"
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> trimmed
        }
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            val u = java.net.URL(url)
            u.host.isNotBlank()
        } catch (e: Exception) {
            false
        }
    }

    override fun onInterrupt() {
        _serviceRunning.value = false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _serviceRunning.value = false
        lastEmittedUrl = ""
        return super.onUnbind(intent)
    }
}
