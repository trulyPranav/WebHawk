package com.webhawk.detector.engine

import android.util.Log
import com.webhawk.detector.data.model.RedirectChain
import com.webhawk.detector.data.model.RiskFeatures
import java.net.URI

/**
 * FeatureExtractor derives all quantitative features from a RedirectChain
 * needed by the RiskEngine.
 */
object FeatureExtractor {

    private const val TAG = "WebHawk.Features"

    private val URL_SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "buff.ly",
        "short.link", "rb.gy", "cutt.ly", "is.gd", "v.gd", "tiny.cc",
        "bl.ink", "soo.gd", "clck.ru", "bc.vc", "s.id", "shorturl.at",
        "rebrand.ly", "tr.im"
    )

    private val SUSPICIOUS_TLDS = setOf(
        ".xyz", ".top", ".club", ".work", ".click", ".loan", ".win",
        ".download", ".racing", ".review", ".science", ".bid",
        ".stream", ".gq", ".cf", ".tk", ".ml", ".ga"
    )

    fun extract(chain: RedirectChain): RiskFeatures {
        val entries = chain.entries

        Log.d(TAG, "════════ FEATURE EXTRACTION ════════")
        Log.d(TAG, "  chain entries   : ${entries.size}")
        Log.d(TAG, "  redirectCount   : ${chain.redirectCount}")
        entries.forEachIndexed { i, e ->
            Log.d(TAG, "  entry[$i]: ${e.url}")
        }

        // ── redirect_count ──────────────────────────────────────────
        val redirectCount = chain.redirectCount

        // ── unique_domains ──────────────────────────────────────────
        // FIX: count only domains visited AFTER the seed URL, i.e. redirect hops.
        // Including the origin domain would inflate the score for zero-redirect URLs.
        val allDomains = entries.mapNotNull { extractDomain(it.url) }
        val originDomain = allDomains.firstOrNull()
        // unique_domains = distinct domains across the WHOLE chain minus origin
        // (measures how many different parties the user was sent through)
        val crossedDomains = allDomains.drop(1).toSet()
        val uniqueDomains = crossedDomains.size

        Log.d(TAG, "  originDomain    : $originDomain")
        Log.d(TAG, "  crossedDomains  : $crossedDomains")
        Log.d(TAG, "  uniqueDomains   : $uniqueDomains")

        // ── has_shortener ──────────────────────────────────────────────
        val shortenerMatches = allDomains.filter { domain ->
            URL_SHORTENERS.any { s -> domain == s || domain.endsWith(".$s") }
        }
        val hasShortener = shortenerMatches.isNotEmpty()
        Log.d(TAG, "  hasShortener    : $hasShortener  (matches=$shortenerMatches)")

        // ── suspicious_tld ────────────────────────────────────────────
        val tldMatches = entries
            .filter { entry -> SUSPICIOUS_TLDS.any { tld -> entry.url.lowercase().contains(tld) } }
            .map { it.url }
        val hasSuspiciousTld = tldMatches.isNotEmpty()
        Log.d(TAG, "  hasSuspiciousTld: $hasSuspiciousTld  (matches=$tldMatches)")

        // ── avg_interval_seconds ────────────────────────────────────
        val avgIntervalSeconds = if (entries.size > 1) {
            val intervals = entries.zipWithNext { a, b -> (b.timestamp - a.timestamp) / 1000.0 }
            Log.d(TAG, "  intervals (s)   : ${intervals.map { String.format("%.3f", it) }}")
            intervals.average()
        } else {
            0.0
        }
        Log.d(TAG, "  avgInterval (s) : ${String.format("%.3f", avgIntervalSeconds)}")

        val features = RiskFeatures(
            redirectCount = redirectCount,
            uniqueDomains = uniqueDomains,
            hasShortener = hasShortener,
            hasSuspiciousTld = hasSuspiciousTld,
            avgIntervalSeconds = avgIntervalSeconds,
            chain = chain
        )
        Log.d(TAG, "══════════════════════════════════════════")
        return features
    }

    fun extractDomain(url: String): String? {
        return try {
            val host = URI(url).host ?: return null
            host.removePrefix("www.")
        } catch (e: Exception) {
            Log.w(TAG, "extractDomain failed for: $url  —  ${e.message}")
            null
        }
    }
}
