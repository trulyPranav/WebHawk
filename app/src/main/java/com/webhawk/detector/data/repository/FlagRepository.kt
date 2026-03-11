package com.webhawk.detector.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.webhawk.detector.data.model.AppUser
import com.webhawk.detector.data.model.FlaggedUrl
import com.webhawk.detector.data.model.UserFlag
import com.webhawk.detector.engine.FeatureExtractor
import kotlinx.coroutines.tasks.await
import java.net.URI

/**
 * FlagRepository manages global flagged URL data in Firestore.
 * Trust-weighted scoring ensures high-trust users have more impact.
 */
class FlagRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    companion object {
        private const val COLLECTION_FLAGGED = "flagged_urls"
        private const val COLLECTION_USER_FLAGS = "user_flags"
        private const val COLLECTION_USERS = "users"
    }

    /**
     * Check if a URL is already flagged in the global DB.
     * Returns [FlaggedUrl] if found, null otherwise.
     */
    suspend fun checkUrl(url: String): Result<FlaggedUrl?> {
        return try {
            val normalized = normalizeForLookup(url)
            val query = db.collection(COLLECTION_FLAGGED)
                .whereEqualTo("normalizedUrl", normalized)
                .limit(1)
                .get()
                .await()

            val doc = query.documents.firstOrNull()
            Result.success(doc?.toObject(FlaggedUrl::class.java))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Compute a 0–10 reputation score from Firestore data.
     * Higher weighted score = higher reputation = more dangerous.
     */
    suspend fun getGlobalReputation(url: String): Double {
        val result = checkUrl(url)
        val flagged = result.getOrNull() ?: return 0.0

        // Normalize weightedScore (assumed scale ~0–50) into 0–10
        return (flagged.weightedScore / 5.0).coerceIn(0.0, 10.0)
    }

    /**
     * Flag a URL. Requires user to be logged in.
     * The user's trust score is used to weight the flag.
     */
    suspend fun flagUrl(
        url: String,
        riskScore: Double,
        chain: List<String>
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(Exception("Must be logged in to flag"))

        return try {
            val userDoc = db.collection(COLLECTION_USERS).document(uid).get().await()
            val userProfile = userDoc.toObject(AppUser::class.java)
            val trustScore = userProfile?.trustScore ?: 1.0

            val normalized = normalizeForLookup(url)

            // Use a transaction to atomically update flag count & weighted score
            db.runTransaction { transaction ->
                val flaggedRef = db.collection(COLLECTION_FLAGGED)
                val query = flaggedRef.whereEqualTo("normalizedUrl", normalized)

                // We can't query inside a transaction directly — use a document ID derived from normalized URL
                val docId = normalizedToDocId(normalized)
                val docRef = db.collection(COLLECTION_FLAGGED).document(docId)
                val snapshot = transaction.get(docRef)

                if (snapshot.exists()) {
                    transaction.update(docRef, mapOf(
                        "flagCount" to FieldValue.increment(1),
                        "weightedScore" to FieldValue.increment(trustScore),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ))
                } else {
                    val newFlag = FlaggedUrl(
                        id = docId,
                        url = url,
                        normalizedUrl = normalized,
                        flagCount = 1,
                        weightedScore = trustScore
                    )
                    transaction.set(docRef, newFlag)
                }
            }.await()

            // Record individual user flag
            val userFlag = UserFlag(
                userId = uid,
                urlId = normalizedToDocId(normalized),
                url = url,
                riskScore = riskScore,
                chain = chain
            )
            db.collection(COLLECTION_USER_FLAGS).add(userFlag).await()

            // Increment user's flag count
            db.collection(COLLECTION_USERS).document(uid)
                .update("flagCount", FieldValue.increment(1))
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Admin: validate/confirm a flagged URL. Boosts the validated flag
     * and increments the flagger's trust score.
     */
    suspend fun validateFlag(flaggedUrlId: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(Exception("Not authenticated"))
        return try {
            val adminDoc = db.collection(COLLECTION_USERS).document(uid).get().await()
            val isAdmin = adminDoc.getBoolean("isAdmin") ?: false
            if (!isAdmin) return Result.failure(Exception("Insufficient permissions"))

            db.collection(COLLECTION_FLAGGED).document(flaggedUrlId)
                .update(mapOf(
                    "validatedFlag" to true,
                    "weightedScore" to FieldValue.increment(5.0),
                    "updatedAt" to FieldValue.serverTimestamp()
                ))
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Admin: fetch all unvalidated flagged URLs for review.
     */
    suspend fun getPendingFlags(): Result<List<FlaggedUrl>> {
        return try {
            val snap = db.collection(COLLECTION_FLAGGED)
                .whereEqualTo("validatedFlag", false)
                .orderBy("weightedScore", Query.Direction.DESCENDING)
                .limit(50)
                .get()
                .await()
            val list = snap.toObjects(FlaggedUrl::class.java)
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Convert normalized URL to a safe Firestore document ID
    private fun normalizedToDocId(normalized: String): String {
        return normalized
            .replace("https://", "")
            .replace("http://", "")
            .replace("/", "_")
            .replace(".", "-")
            .take(100)
    }

    private fun normalizeForLookup(url: String): String {
        return try {
            val uri = URI(url.trim().lowercase())
            val host = uri.host?.removePrefix("www.") ?: ""
            val path = uri.path?.trimEnd('/') ?: ""
            "https://$host$path"
        } catch (e: Exception) {
            url.trim().lowercase()
        }
    }
}
