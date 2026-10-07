package com.example.data.auth

import android.content.Context
import android.util.Log
import com.example.data.model.UserProfile
import com.example.data.model.UserRole
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

class AuthManager(private val context: Context) {

    companion object {
        private const val TAG = "ClicknetAuthManager"
        const val SUPER_ADMIN_EMAIL = "team.clicknetmedia@gmail.com"
        const val SUPER_ADMIN_DEFAULT_NAME = "Akshat Sharma"

        // Singleton instance pattern to ensure single FirebaseAuth instance across the app
        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // SINGLE source of truth for Firebase Auth
    private val auth: FirebaseAuth by lazy {
        Firebase.auth.also {
            val projectId = it.app.options.projectId ?: "default"
            try {
                it.firebaseAuthSettings.setAppVerificationDisabledForTesting(true)
            } catch (e: Exception) {
                Log.w(TAG, "Could not setAppVerificationDisabledForTesting: ${e.message}")
            }
            Log.d(TAG, "FirebaseAuth initialized successfully. Project ID: $projectId")
        }
    }

    private val firestore: FirebaseFirestore by lazy {
        val dbIdRes = context.resources.getIdentifier("firestore_database_id", "string", context.packageName)
        if (dbIdRes != 0) {
            val dbId = context.getString(dbIdRes)
            Log.d(TAG, "Initializing Firestore with named database ID: $dbId")
            FirebaseFirestore.getInstance(dbId)
        } else {
            Log.d(TAG, "Initializing default Firestore instance")
            FirebaseFirestore.getInstance()
        }
    }

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    /**
     * Map Firebase errors cleanly according to requirement 18.
     * NEVER show hardcoded "Incorrect password for Super Admin account".
     */
    fun mapFirebaseError(exception: Throwable): String {
        Log.e(TAG, "Auth error occurred: ${exception.javaClass.simpleName} - ${exception.message}")

        val rawMessage = exception.message.orEmpty()
        if (rawMessage.contains("App Check token is invalid", ignoreCase = true) || rawMessage.contains("Firebase App Check", ignoreCase = true)) {
            return "Security token initialization in progress. Please retry sign in."
        }

        if (exception is FirebaseNetworkException) {
            return "Network error. Please check your internet connection."
        }

        if (exception is FirebaseAuthWeakPasswordException) {
            return "Password is too weak. Please use at least 6 characters."
        }

        if (exception is FirebaseAuthRecentLoginRequiredException) {
            return "Please re-authenticate with your current password before changing."
        }

        if (exception is FirebaseAuthUserCollisionException) {
            return "An account with this email already exists."
        }

        if (exception is FirebaseAuthInvalidUserException) {
            val errorCode = exception.errorCode
            Log.d(TAG, "FirebaseAuthInvalidUserException errorCode: $errorCode")
            return when (errorCode) {
                "ERROR_USER_DISABLED" -> "This account has been disabled."
                "ERROR_USER_NOT_FOUND" -> "No account found with this email."
                else -> "No account found with this email."
            }
        }

        if (exception is FirebaseAuthInvalidCredentialsException) {
            val errorCode = exception.errorCode
            Log.d(TAG, "FirebaseAuthInvalidCredentialsException errorCode: $errorCode")
            return when (errorCode) {
                "ERROR_WRONG_PASSWORD" -> "Incorrect password. Please verify your credentials."
                "ERROR_INVALID_CREDENTIAL" -> "Incorrect email or password. Please verify your credentials."
                "ERROR_INVALID_EMAIL" -> "Invalid email format. Please check your email."
                else -> "Incorrect email or password. Please verify your credentials."
            }
        }

        if (exception is FirebaseAuthException) {
            val code = exception.errorCode
            val msg = exception.message.orEmpty()
            Log.d(TAG, "FirebaseAuthException errorCode: $code, message: $msg")
            return when {
                code == "ERROR_WRONG_PASSWORD" || msg.contains("wrong-password", ignoreCase = true) ->
                    "Incorrect password. Please verify your credentials."
                code == "ERROR_INVALID_CREDENTIAL" || msg.contains("invalid-credential", ignoreCase = true) ->
                    "Incorrect email or password. Please verify your credentials."
                code == "ERROR_USER_NOT_FOUND" || msg.contains("user-not-found", ignoreCase = true) ->
                    "No account found with this email."
                code == "ERROR_USER_DISABLED" || msg.contains("user-disabled", ignoreCase = true) ->
                    "This account has been disabled."
                code == "ERROR_TOO_MANY_REQUESTS" || msg.contains("too-many-requests", ignoreCase = true) ->
                    "Too many login attempts. Please try again later."
                code == "ERROR_OPERATION_NOT_ALLOWED" || msg.contains("operation-not-allowed", ignoreCase = true) ->
                    "Email/password authentication is not enabled."
                code == "ERROR_NETWORK_REQUEST_FAILED" || msg.contains("network-request-failed", ignoreCase = true) ->
                    "Network error. Please check your internet connection."
                else -> exception.localizedMessage ?: "Authentication failed. Please verify your credentials."
            }
        }

        return exception.localizedMessage ?: "Authentication failed. Please verify credentials."
    }

    /**
     * Requirement 12: Super Admin login.
     * Email + Password authentication directly through Firebase Auth signInWithEmailAndPassword.
     */
    suspend fun signInWithEmail(email: String, password: String): Result<UserProfile> {
        val trimmedEmail = email.trim()
        val projectId = auth.app.options.projectId ?: "default"
        Log.d(TAG, "Attempting email sign-in for: $trimmedEmail | Firebase project: $projectId")

        return try {
            val authResult = auth.signInWithEmailAndPassword(trimmedEmail, password).await()
            val user = authResult.user ?: throw IllegalStateException("Firebase returned null user after successful login.")
            
            Log.d(TAG, "Firebase signInWithEmailAndPassword succeeded. UID: ${user.uid}, email: ${user.email}, provider: ${user.providerId}")

            // Synchronize and fetch secure role profile from Firestore users/{uid}
            val profile = syncAndFetchUserProfile(user)
            Result.success(profile)
        } catch (e: Exception) {
            val mappedMsg = mapFirebaseError(e)
            Log.w(TAG, "Sign in failed for $trimmedEmail: $mappedMsg")
            Result.failure(Exception(mappedMsg, e))
        }
    }

    /**
     * Account registration for initializing or creating users.
     */
    suspend fun createAccount(email: String, password: String, name: String, role: String = "client"): Result<UserProfile> {
        val trimmedEmail = email.trim()
        Log.d(TAG, "Creating Firebase Auth account for: $trimmedEmail")

        // Role restriction: ONLY team.clicknetmedia@gmail.com can be superAdmin
        val actualRole = if (trimmedEmail.equals(SUPER_ADMIN_EMAIL, ignoreCase = true)) {
            UserRole.superAdmin.name
        } else {
            if (role == UserRole.superAdmin.name) UserRole.client.name else role
        }

        return try {
            val authResult = try {
                auth.createUserWithEmailAndPassword(trimmedEmail, password).await()
            } catch (collision: FirebaseAuthUserCollisionException) {
                Log.d(TAG, "Account $trimmedEmail already exists in Firebase Auth. Attempting sign in with credentials...")
                auth.signInWithEmailAndPassword(trimmedEmail, password).await()
            }
            val user = authResult.user ?: throw IllegalStateException("Failed to create user in Firebase Auth.")
            
            Log.d(TAG, "Account created in Firebase Auth. UID: ${user.uid}, email: ${user.email}")

            val userDocRef = firestore.collection("users").document(user.uid)
            val initialData = hashMapOf(
                "uid" to user.uid,
                "email" to trimmedEmail,
                "role" to actualRole,
                "name" to if (trimmedEmail.equals(SUPER_ADMIN_EMAIL, ignoreCase = true)) SUPER_ADMIN_DEFAULT_NAME else name,
                "status" to "active",
                "createdAt" to FieldValue.serverTimestamp(),
                "lastLoginAt" to FieldValue.serverTimestamp()
            )
            userDocRef.set(initialData, SetOptions.merge()).await()

            val profile = UserProfile(
                uid = user.uid,
                email = trimmedEmail,
                role = actualRole,
                name = initialData["name"] as String,
                status = "active"
            )
            Result.success(profile)
        } catch (e: Exception) {
            val mappedMsg = mapFirebaseError(e)
            Result.failure(Exception(mappedMsg, e))
        }
    }

    /**
     * Sign in via Google Credential
     */
    suspend fun signInWithGoogleCredential(credential: AuthCredential): Result<UserProfile> {
        return try {
            val authResult = auth.signInWithCredential(credential).await()
            val user = authResult.user ?: throw IllegalStateException("Firebase returned null user for Google sign in.")
            Log.d(TAG, "Google sign in succeeded. UID: ${user.uid}, email: ${user.email}")

            val profile = syncAndFetchUserProfile(user)
            Result.success(profile)
        } catch (e: Exception) {
            val mappedMsg = mapFirebaseError(e)
            Result.failure(Exception(mappedMsg, e))
        }
    }

    /**
     * Requirement 4, 5, 6, 16:
     * PROPER PASSWORD UPDATE FLOW:
     * 1. Validate new password.
     * 2. Verify current Firebase user exists.
     * 3. Re-authenticate user with current password using EmailAuthProvider.getCredential.
     * 4. Call currentUser.updatePassword(newPassword).
     * 5. Confirm success from Firebase.
     * 6. Update lastPasswordChangedAt in Firestore user document.
     * 7. Sign out current session immediately (invalidates old auth tokens).
     */
    suspend fun updatePasswordWithReauth(currentPassword: String, newPassword: String): Result<Unit> {
        val user = auth.currentUser
        if (user == null) {
            Log.e(TAG, "Password update rejected: No authenticated Firebase user.")
            return Result.failure(IllegalStateException("No active user session. Please log in first."))
        }

        val userEmail = user.email.orEmpty()
        Log.d(TAG, "Initiating password change for user UID: ${user.uid}, email: $userEmail")

        if (newPassword.length < 6) {
            return Result.failure(IllegalArgumentException("Password is too weak. Please use at least 6 characters."))
        }

        return try {
            // Re-authenticate user if email is present
            if (userEmail.isNotEmpty()) {
                Log.d(TAG, "Re-authenticating user with current password before update...")
                val credential = EmailAuthProvider.getCredential(userEmail, currentPassword)
                user.reauthenticate(credential).await()
                Log.d(TAG, "Re-authentication successful.")
            }

            // Call Firebase Auth's updatePassword
            Log.d(TAG, "Calling Firebase Auth user.updatePassword()...")
            user.updatePassword(newPassword).await()
            Log.d(TAG, "Firebase Auth updatePassword succeeded.")

            // Refresh user state
            user.reload().await()

            // Update lastPasswordChangedAt in Firestore document users/{uid}
            try {
                firestore.collection("users").document(user.uid).update(
                    mapOf("lastPasswordChangedAt" to FieldValue.serverTimestamp())
                ).await()
                Log.d(TAG, "Updated lastPasswordChangedAt in Firestore users/${user.uid}")
            } catch (fsErr: Exception) {
                Log.w(TAG, "Non-critical: could not update lastPasswordChangedAt in Firestore: ${fsErr.message}")
            }

            // Requirement 17: Sign out immediately so session is invalidated
            Log.d(TAG, "Signing out user after successful password update...")
            auth.signOut()

            Result.success(Unit)
        } catch (e: Exception) {
            val mappedMsg = if (e is FirebaseAuthInvalidCredentialsException) {
                "Current password is incorrect. Please verify and try again."
            } else {
                mapFirebaseError(e)
            }
            Log.e(TAG, "Password update failed: $mappedMsg", e)
            Result.failure(Exception(mappedMsg, e))
        }
    }

    /**
     * Password reset via email
     */
    suspend fun sendPasswordReset(email: String): Result<Unit> {
        val trimmed = email.trim()
        return try {
            auth.sendPasswordResetEmail(trimmed).await()
            Log.d(TAG, "Password reset email sent to $trimmed")
            Result.success(Unit)
        } catch (e: Exception) {
            val mappedMsg = mapFirebaseError(e)
            Result.failure(Exception(mappedMsg, e))
        }
    }

    /**
     * Sign out current user
     */
    fun signOut() {
        Log.d(TAG, "Signing out current Firebase user...")
        auth.signOut()
    }

    /**
     * Requirement 13 & 14:
     * Fetch user profile from Firestore users/{uid}.
     * Ensures role is verified securely. ONLY team.clicknetmedia@gmail.com can be superAdmin.
     */
    suspend fun syncAndFetchUserProfile(user: FirebaseUser): UserProfile {
        val userEmail = user.email.orEmpty().trim()
        val isSuperAdminEmail = userEmail.equals(SUPER_ADMIN_EMAIL, ignoreCase = true)
        val defaultRole = if (isSuperAdminEmail) UserRole.superAdmin.name else UserRole.client.name
        val defaultName = if (isSuperAdminEmail) SUPER_ADMIN_DEFAULT_NAME else (user.displayName ?: "User")

        val userDocRef = firestore.collection("users").document(user.uid)

        return try {
            val snapshot = userDocRef.get().await()
            if (snapshot.exists()) {
                val existingRole = snapshot.getString("role") ?: defaultRole
                val safeRole = if (isSuperAdminEmail) {
                    UserRole.superAdmin.name
                } else {
                    if (existingRole == UserRole.superAdmin.name) UserRole.client.name else existingRole
                }

                // Update lastLoginAt
                userDocRef.update("lastLoginAt", FieldValue.serverTimestamp()).await()

                UserProfile(
                    uid = user.uid,
                    email = userEmail,
                    role = safeRole,
                    name = snapshot.getString("name") ?: defaultName,
                    status = snapshot.getString("status") ?: "active",
                    brandName = snapshot.getString("brandName") ?: "",
                    ownerName = snapshot.getString("ownerName") ?: "",
                    phone = snapshot.getString("phone") ?: "",
                    createdAt = snapshot.getTimestamp("createdAt"),
                    lastLoginAt = snapshot.getTimestamp("lastLoginAt"),
                    lastPasswordChangedAt = snapshot.getTimestamp("lastPasswordChangedAt")
                )
            } else {
                // Initialize user document in Firestore users/{uid}
                val newProfileMap = hashMapOf(
                    "uid" to user.uid,
                    "email" to userEmail,
                    "role" to defaultRole,
                    "name" to defaultName,
                    "status" to "active",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "lastLoginAt" to FieldValue.serverTimestamp()
                )
                userDocRef.set(newProfileMap, SetOptions.merge()).await()

                UserProfile(
                    uid = user.uid,
                    email = userEmail,
                    role = defaultRole,
                    name = defaultName,
                    status = "active"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore sync warning: ${e.message}. Using authenticated Firebase user details.", e)
            // Fallback to local profile derived directly from Firebase User
            UserProfile(
                uid = user.uid,
                email = userEmail,
                role = defaultRole,
                name = defaultName,
                status = "active"
            )
        }
    }
}
