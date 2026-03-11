package com.webhawk.detector.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.webhawk.detector.data.model.AppUser
import kotlinx.coroutines.tasks.await

/**
 * AuthRepository handles Firebase Auth and Firestore user profile management.
 */
class AuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    val currentUser: FirebaseUser? get() = auth.currentUser
    val isLoggedIn: Boolean get() = auth.currentUser != null

    suspend fun login(email: String, password: String): Result<AppUser> {
        return try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val uid = result.user?.uid ?: return Result.failure(Exception("Authentication failed"))
            val user = getOrCreateUserProfile(uid, email, result.user?.displayName ?: "")
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun register(email: String, password: String, displayName: String): Result<AppUser> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val uid = result.user?.uid ?: return Result.failure(Exception("Registration failed"))
            val user = AppUser(
                uid = uid,
                email = email,
                displayName = displayName,
                trustScore = 1.0
            )
            db.collection("users").document(uid).set(user).await()
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCurrentUserProfile(): Result<AppUser> {
        val uid = auth.currentUser?.uid ?: return Result.failure(Exception("Not logged in"))
        return try {
            val doc = db.collection("users").document(uid).get().await()
            val user = doc.toObject(AppUser::class.java)
                ?: return Result.failure(Exception("Profile not found"))
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun logout() {
        auth.signOut()
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> {
        return try {
            auth.sendPasswordResetEmail(email).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun getOrCreateUserProfile(
        uid: String,
        email: String,
        displayName: String
    ): AppUser {
        val doc = db.collection("users").document(uid).get().await()
        return if (doc.exists()) {
            doc.toObject(AppUser::class.java) ?: AppUser(uid = uid, email = email)
        } else {
            val user = AppUser(uid = uid, email = email, displayName = displayName)
            db.collection("users").document(uid).set(user).await()
            user
        }
    }
}
