package com.webhawk.detector.engine

import com.webhawk.detector.data.model.RiskFeatures
import com.webhawk.detector.data.model.RiskResult
import com.webhawk.detector.data.model.RiskResult.RiskLevel
import kotlin.math.min

/**
 * RiskEngine applies the weighted formula to compute local_score,
 * then blends with a global_reputation value from Firebase.
 *
 * Formula (as specified):
 *   local_score  = 2.0 * redirect_count
 *                + 1.5 * unique_domains
 *                + 1.2 * has_shortener   (1 or 0)
 *                + 1.0 * suspicious_tld  (1 or 0)
 *                - 0.5 * avg_interval_seconds
 *
 *   final_risk   = local_score * 0.7 + global_reputation * 0.3
 *
 * global_reputation is normalized to 0–10 range (10 = very bad).
 */
object RiskEngine {

    // Scores above these thresholds map to each level
    private const val LOW_THRESHOLD    = 2.0
    private const val MEDIUM_THRESHOLD = 5.0
    private const val HIGH_THRESHOLD   = 8.0
    private const val CRITICAL_THRESHOLD = 12.0

    fun computeLocalScore(features: RiskFeatures): Double {
        val score = (2.0 * features.redirectCount) +
                    (1.5 * features.uniqueDomains) +
                    (1.2 * if (features.hasShortener) 1 else 0) +
                    (1.0 * if (features.hasSuspiciousTld) 1 else 0) -
                    (0.5 * features.avgIntervalSeconds)
        return score.coerceAtLeast(0.0)
    }

    /**
     * @param features        extracted from the redirect chain
     * @param globalReputation 0.0–10.0 weighted flag score from Firebase (0 = clean, 10 = very bad)
     */
    fun evaluate(features: RiskFeatures, globalReputation: Double): RiskResult {
        val localScore = computeLocalScore(features)
        val clampedGlobal = globalReputation.coerceIn(0.0, 10.0)
        val finalRisk = (localScore * 0.7) + (clampedGlobal * 0.3)

        val riskLevel = when {
            finalRisk < LOW_THRESHOLD    -> RiskLevel.SAFE
            finalRisk < MEDIUM_THRESHOLD -> RiskLevel.LOW
            finalRisk < HIGH_THRESHOLD   -> RiskLevel.MEDIUM
            finalRisk < CRITICAL_THRESHOLD -> RiskLevel.HIGH
            else                         -> RiskLevel.CRITICAL
        }

        val explanation = buildExplanation(features, localScore, clampedGlobal, finalRisk)

        return RiskResult(
            localScore = localScore,
            globalReputation = clampedGlobal,
            finalRisk = finalRisk,
            riskLevel = riskLevel,
            features = features,
            explanation = explanation
        )
    }

    private fun buildExplanation(
        f: RiskFeatures,
        local: Double,
        global: Double,
        final: Double
    ): List<String> {
        val lines = mutableListOf<String>()
        if (f.redirectCount > 0) {
            lines.add("${f.redirectCount} redirect(s) detected (+${String.format("%.1f", 2.0 * f.redirectCount)})")
        }
        if (f.uniqueDomains > 1) {
            lines.add("${f.uniqueDomains} unique domains crossed (+${String.format("%.1f", 1.5 * f.uniqueDomains)})")
        }
        if (f.hasShortener) {
            lines.add("URL shortener detected (+1.2)")
        }
        if (f.hasSuspiciousTld) {
            lines.add("Suspicious top-level domain (+1.0)")
        }
        if (f.avgIntervalSeconds > 0) {
            lines.add("Average redirect interval: ${String.format("%.2f", f.avgIntervalSeconds)}s (-${String.format("%.2f", 0.5 * f.avgIntervalSeconds)})")
        }
        if (global > 0) {
            lines.add("Global community flags: ${String.format("%.1f", global)}/10")
        }
        lines.add("Local score: ${String.format("%.2f", local)} · Global: ${String.format("%.2f", global)} · Final: ${String.format("%.2f", final)}")
        return lines
    }
}
