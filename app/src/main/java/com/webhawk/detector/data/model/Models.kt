package com.webhawk.detector.data.model

import android.os.Parcelable
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.parcelize.Parcelize
import java.util.Date

@Parcelize
data class UrlEntry(
    val url: String = "",
    val timestamp: Long = 0L
) : Parcelable

@Parcelize
data class RedirectChain(
    val entries: List<UrlEntry> = emptyList(),
    val startUrl: String = "",
    val finalUrl: String = "",
    val durationMs: Long = 0L
) : Parcelable {
    val redirectCount: Int get() = (entries.size - 1).coerceAtLeast(0)
}

@Parcelize
data class RiskFeatures(
    val redirectCount: Int,
    val uniqueDomains: Int,
    val hasShortener: Boolean,
    val hasSuspiciousTld: Boolean,
    val avgIntervalSeconds: Double,
    val chain: RedirectChain
) : Parcelable

@Parcelize
data class RiskResult(
    val localScore: Double,
    val globalReputation: Double,
    val finalRisk: Double,
    val riskLevel: RiskLevel,
    val features: RiskFeatures,
    val explanation: List<String>
) : Parcelable {
    enum class RiskLevel { SAFE, LOW, MEDIUM, HIGH, CRITICAL }
}

data class FlaggedUrl(
    @DocumentId val id: String = "",
    val url: String = "",
    val normalizedUrl: String = "",
    val flagCount: Int = 0,
    val validatedFlag: Boolean = false,
    val weightedScore: Double = 0.0,
    @ServerTimestamp val createdAt: Date? = null,
    @ServerTimestamp val updatedAt: Date? = null
)

data class UserFlag(
    @DocumentId val id: String = "",
    val userId: String = "",
    val urlId: String = "",
    val url: String = "",
    val riskScore: Double = 0.0,
    val chain: List<String> = emptyList(),
    @ServerTimestamp val flaggedAt: Date? = null
)

data class AppUser(
    @DocumentId val id: String = "",
    val uid: String = "",
    val email: String = "",
    val displayName: String = "",
    val trustScore: Double = 1.0,
    val flagCount: Int = 0,
    val validatedFlagCount: Int = 0,
    val isAdmin: Boolean = false,
    @ServerTimestamp val createdAt: Date? = null
)
