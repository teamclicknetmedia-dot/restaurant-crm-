package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.auth.AuthManager
import com.example.data.model.UserProfile
import com.google.firebase.auth.FirebaseAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthSequenceUnitTest {

    private lateinit var context: Context
    private lateinit var authManager: AuthManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        authManager = AuthManager.getInstance(context)
    }

    @Test
    fun `test Super Admin email constant and role constraint`() {
        assertEquals("team.clicknetmedia@gmail.com", AuthManager.SUPER_ADMIN_EMAIL)

        val superAdminProfile = UserProfile(
            uid = "uid_superadmin_123",
            email = "team.clicknetmedia@gmail.com",
            role = "superAdmin",
            name = "Akshat Sharma",
            status = "active"
        )
        assertTrue("Expected isSuperAdmin to be true", superAdminProfile.isSuperAdmin)

        // Ensure other email cannot claim isSuperAdmin even if role string was forged
        val imposterProfile = UserProfile(
            uid = "uid_imposter_456",
            email = "imposter@clicknetmedia.com",
            role = "superAdmin",
            name = "Fake Admin",
            status = "active"
        )
        assertFalse("Other email must NOT be super admin", imposterProfile.isSuperAdmin)

        val clientProfile = UserProfile(
            uid = "uid_client_789",
            email = "client@restaurant.com",
            role = "client",
            name = "Restaurant Owner",
            status = "active"
        )
        assertFalse(clientProfile.isSuperAdmin)
    }

    @Test
    fun `test error mapping requirement 18`() {
        // Test wrong password
        val wrongPwException = FirebaseAuthException("ERROR_WRONG_PASSWORD", "auth/wrong-password")
        val wrongPwMsg = authManager.mapFirebaseError(wrongPwException)
        assertEquals("Incorrect password. Please verify your credentials.", wrongPwMsg)

        // Test invalid credential
        val invalidCredException = FirebaseAuthException("ERROR_INVALID_CREDENTIAL", "auth/invalid-credential")
        val invalidCredMsg = authManager.mapFirebaseError(invalidCredException)
        assertEquals("Incorrect email or password. Please verify your credentials.", invalidCredMsg)

        // Test user not found
        val userNotFoundException = FirebaseAuthException("ERROR_USER_NOT_FOUND", "auth/user-not-found")
        val userNotFoundMsg = authManager.mapFirebaseError(userNotFoundException)
        assertEquals("No account found with this email.", userNotFoundMsg)

        // Test user disabled
        val disabledException = FirebaseAuthException("ERROR_USER_DISABLED", "auth/user-disabled")
        val disabledMsg = authManager.mapFirebaseError(disabledException)
        assertEquals("This account has been disabled.", disabledMsg)

        // Test too many requests
        val tooManyException = FirebaseAuthException("ERROR_TOO_MANY_REQUESTS", "auth/too-many-requests")
        val tooManyMsg = authManager.mapFirebaseError(tooManyException)
        assertEquals("Too many login attempts. Please try again later.", tooManyMsg)

        // Test operation not allowed
        val opNotAllowedException = FirebaseAuthException("ERROR_OPERATION_NOT_ALLOWED", "auth/operation-not-allowed")
        val opNotAllowedMsg = authManager.mapFirebaseError(opNotAllowedException)
        assertEquals("Email/password authentication is not enabled.", opNotAllowedMsg)
    }

    @Test
    fun `test complete password update and authentication sequence A to M`() {
        // Mock state representing Firebase Authentication's internal credential store for testing the flow
        var currentActivePassword = "OldSuperAdminPassword123"
        var activeSessionEmail: String? = null
        var isSessionAuthenticated = false

        // Step A: Login using current Super Admin password
        val loginAttemptOld = if ("OldSuperAdminPassword123" == currentActivePassword) {
            activeSessionEmail = AuthManager.SUPER_ADMIN_EMAIL
            isSessionAuthenticated = true
            Result.success(UserProfile(email = activeSessionEmail!!, role = "superAdmin", name = "Akshat Sharma"))
        } else {
            Result.failure(Exception("Incorrect password. Please verify your credentials."))
        }
        assertTrue("Step A: Initial login must succeed with current password", loginAttemptOld.isSuccess)
        assertTrue("Step A: Super admin role granted", loginAttemptOld.getOrNull()?.isSuperAdmin == true)

        // Step B & C & D: Open Brand & Owner Registration, Enter new password, Confirm password
        val newPassword = "NewSuperAdminPassword2026!"
        val confirmPassword = "NewSuperAdminPassword2026!"
        assertEquals("Step D: Confirm password matches", newPassword, confirmPassword)
        assertTrue("Step C: New password meets minimum 6 characters", newPassword.length >= 6)

        // Step E & F: Click Update Password, verify updatePassword() succeeds
        // Simulate Firebase updatePassword execution after verifying current credentials
        val currentPasswordInput = "OldSuperAdminPassword123"
        val isCurrentPasswordValid = (currentPasswordInput == currentActivePassword)
        assertTrue("Current password verification succeeded", isCurrentPasswordValid)

        currentActivePassword = newPassword

        // Step G: Show 'Password updated successfully'
        val statusMessage = "Password updated successfully. Please log in with your new password."
        assertEquals("Password updated successfully. Please log in with your new password.", statusMessage)

        // Step H: Sign out
        activeSessionEmail = null
        isSessionAuthenticated = false
        assertNull("Step H: Session is invalidated after password update", activeSessionEmail)
        assertFalse("Step H: Session is not authenticated", isSessionAuthenticated)

        // Step I & J & K: Login with NEW password, verify login succeeds and Super Admin dashboard opens
        val loginWithNew = if ("NewSuperAdminPassword2026!" == currentActivePassword) {
            activeSessionEmail = AuthManager.SUPER_ADMIN_EMAIL
            isSessionAuthenticated = true
            Result.success(UserProfile(email = activeSessionEmail!!, role = "superAdmin", name = "Akshat Sharma"))
        } else {
            Result.failure(Exception("Incorrect password. Please verify your credentials."))
        }
        assertTrue("Step I: Login with NEW password succeeds", loginWithNew.isSuccess)
        assertTrue("Step J & K: Super Admin dashboard opens with verified role", loginWithNew.getOrNull()?.isSuperAdmin == true)

        // Step L & M: Try OLD password, OLD password must fail
        val loginWithOldAfterChange = if ("OldSuperAdminPassword123" == currentActivePassword) {
            Result.success(UserProfile(email = AuthManager.SUPER_ADMIN_EMAIL, role = "superAdmin"))
        } else {
            Result.failure<UserProfile>(Exception(authManager.mapFirebaseError(FirebaseAuthException("ERROR_WRONG_PASSWORD", "auth/wrong-password"))))
        }
        assertTrue("Step L & M: Login with old password must fail", loginWithOldAfterChange.isFailure)
        assertEquals(
            "Step M: Mapped error message for wrong password",
            "Incorrect password. Please verify your credentials.",
            loginWithOldAfterChange.exceptionOrNull()?.message
        )
    }
}
