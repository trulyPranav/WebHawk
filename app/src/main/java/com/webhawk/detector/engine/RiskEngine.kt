package com.webhawk.detector.engine

import android.util.Log
import com.webhawk.detector.data.model.RiskFeatures
import com.webhawk.detector.data.model.RiskResult
import com.webhawk.detector.data.model.RiskResult.RiskLevel

/**
 * RiskEngine applies the weighted formula to compute local_score,
 * then blends with a global_reputation value from Firebase.
 *
 * Formula:
 *   local_score = 2.0 * redirect_count
 *              + 1.5 * unique_domains       (domains crossed AFTER the seed)
 *              + 1.2 * has_shortener        (1 or 0)
 *              + 1.0 * suspicious_tld       (1 or 0)
 *              - 0.5 * avg_interval_seconds
 *
 *   final_risk  = local_score * 0.7 + global_reputation * 0.3
 *
 * global_reputation is normalised to 0–10 (10 = very bad).
 */
object RiskEngine {

    private const val TAG = "WebHawk.Risk"

    private const val W_REDIRECT   = 2.0
    private const val W_DOMAINS    = 1.5
    private const val W_SHORTENER  = 1.2
    private const val W_TLD        = 1.0
    private const val W_INTERVAL   = 0.5   // subtracted
    private const val BLEND_LOCAL  = 0.7
    private const val BLEND_GLOBAL = 0.3

    private const val LOW_THRESHOLD      = 2.0
    private const val MEDIUM_THRESHOLD   = 5.0
    private const val HIGH_THRESHOLD     = 8.0
    private const val CRITICAL_THRESHOLD = 12.0

    fun computeLocalScore(features: RiskFeatures): Double {
        val score = (W_REDIRECT  * features.redirectCount) +
                    (W_DOMAINS   * features.uniqueDomains) +
                    (W_SHORTENER * if (features.hasShortener) 1 else 0) +
                    (W_TLD       * if (features.hasSuspiciousTld) 1 else 0) -
                    (W_INTERVAL  * features.avgIntervalSeconds)
        return score.coerceAtLeast(0.0)
    }

    /**
     * @param features         extracted from the redirect chain
     * @param globalReputation 0.0–10.0 weighted flag score from Firebase
     */
    fun evaluate(features: RiskFeatures, globalReputation: Double): RiskResult {
        val localScore     = computeLocalScore(features)
        val clampedGlobal  = globalReputation.coerceIn(0.0, 10.0)
        val finalRisk      = (localScore * BLEND_LOCAL) + (clampedGlobal * BLEND_GLOBAL)

        val riskLevel = when {
            finalRisk < LOW_THRESHOLD      -> RiskLevel.SAFE
            finalRisk < MEDIUM_THRESHOLD   -> RiskLevel.LOW
            finalRisk < HIGH_THRESHOLD     -> RiskLevel.MEDIUM
            finalRisk < CRITICAL_THRESHOLD -> RiskLevel.HIGH
            else                           -> RiskLevel.CRITICAL
        }

        Log.i(TAG, "══════════ RISK SCORE ══════════════")
        Log.i(TAG, "  redirect_count        : ${features.redirectCount}   × $W_REDIRECT  = ${W_REDIRECT * features.redirectCount}")
        Log.i(TAG, "  unique_domains        : ${features.uniqueDomains}   × $W_DOMAINS  = ${W_DOMAINS * features.uniqueDomains}")
        Log.i(TAG, "  has_shortener         : ${features.hasShortener}  × $W_SHORTENER = ${W_SHORTENER * if (features.hasShortener) 1 else 0}")
        Log.i(TAG, "  suspicious_tld        : ${features.hasSuspiciousTld}  × $W_TLD     = ${W_TLD * if (features.hasSuspiciousTld) 1 else 0}")
        Log.i(TAG, "  avg_interval (s)      : ${String.format("%.3f", features.avgIntervalSeconds)}  × -$W_INTERVAL = ${-(W_INTERVAL * features.avgIntervalSeconds)}")
        Log.i(TAG, "  ────────────────────────────")
        Log.i(TAG, "  local_score   (raw)   : ${String.format("%.4f", computeLocalScore(features))} (clamped ≥0)")
        Log.i(TAG, "  local_score   (final) : ${String.format("%.4f", localScore)} × $BLEND_LOCAL = ${String.format("%.4f", localScore * BLEND_LOCAL)}")
        Log.i(TAG, "  global_reputation     : ${String.format("%.4f", clampedGlobal)} × $BLEND_GLOBAL = ${String.format("%.4f", clampedGlobal * BLEND_GLOBAL)}")
        Log.i(TAG, "  final_risk            : ${String.format("%.4f", finalRisk)}")
        Log.i(TAG, "  risk_level            : $riskLevel")
        Log.i(TAG, "════════════════════════════════════")

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
            lines.add("${f.redirectCount} redirect(s) detected (+${String.format("%.1f", W_REDIRECT * f.redirectCount)})")
        }
        if (f.uniqueDomains > 0) {
            lines.add("${f.uniqueDomains} cross-domain hop(s) (+${String.format("%.1f", W_DOMAINS * f.uniqueDomains)})")
        }
        if (f.hasShortener) {
            lines.add("URL shortener in chain (+${W_SHORTENER})")
        }
        if (f.hasSuspiciousTld) {
            lines.add("Suspicious top-level domain (+${W_TLD})")
        }
        if (f.avgIntervalSeconds > 0) {
            lines.add("Avg interval ${String.format("%.2f", f.avgIntervalSeconds)}s (-${String.format("%.2f", W_INTERVAL * f.avgIntervalSeconds)})")
        }
        if (global > 0) {
            lines.add("Community flag score: ${String.format("%.1f", global)}/10")
        }
        if (lines.isEmpty()) {
            lines.add("No suspicious indicators detected")
        }
        lines.add("Local ${String.format("%.2f", local)} × 0.7  +  Global ${String.format("%.2f", global)} × 0.3  =  ${String.format("%.2f", final)}")
        return lines
    }
}
