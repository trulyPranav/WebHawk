package com.webhawk.detector.engine

import com.webhawk.detector.data.model.RedirectChain
import com.webhawk.detector.data.model.RiskFeatures
import com.webhawk.detector.data.model.RiskResult
import com.webhawk.detector.data.model.UrlEntry
import java.net.URI

/**
 * FeatureExtractor derives all quantitative features from a RedirectChain
 * needed by the RiskEngine.
 */
object FeatureExtractor {

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
        val domains = entries.mapNotNull { extractDomain(it.url) }
        val uniqueDomains = domains.toSet().size

        val hasShortener = domains.any { domain ->
            URL_SHORTENERS.any { shortener -> domain == shortener || domain.endsWith(".$shortener") }
        }

        val hasSuspiciousTld = entries.any { entry ->
            SUSPICIOUS_TLDS.any { tld -> entry.url.lowercase().contains(tld) }
        }

        val avgIntervalSeconds = if (entries.size > 1) {
            val intervals = entries.zipWithNext { a, b -> (b.timestamp - a.timestamp) / 1000.0 }
            intervals.average()
        } else {
            0.0
        }

        return RiskFeatures(
            redirectCount = chain.redirectCount,
            uniqueDomains = uniqueDomains,
            hasShortener = hasShortener,
            hasSuspiciousTld = hasSuspiciousTld,
            avgIntervalSeconds = avgIntervalSeconds,
            chain = chain
        )
    }

    fun extractDomain(url: String): String? {
        return try {
            val host = URI(url).host ?: return null
            host.removePrefix("www.")
        } catch (e: Exception) {
            null
        }
    }
}
