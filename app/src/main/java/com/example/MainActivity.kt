package com.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.data.auth.AuthManager
import com.example.data.model.UserProfile
import com.example.data.repository.CrmRepository
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.ClientDashboard
import com.example.ui.screens.SuperAdminDashboard
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.Slate900
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val authManager = AuthManager.getInstance(applicationContext)
        val crmRepository = CrmRepository(applicationContext)

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppRoot(
                        authManager = authManager,
                        crmRepository = crmRepository,
                        activity = this,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun AppRoot(
    authManager: AuthManager,
    crmRepository: CrmRepository,
    activity: ComponentActivity,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }
    var userProfile by remember { mutableStateOf<UserProfile?>(null) }
    var isProfileLoading by remember { mutableStateOf(false) }
    var sessionNotice by remember { mutableStateOf<String?>(null) }

    // Listen to Firebase Auth state changes
    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { fbAuth ->
            currentUser = fbAuth.currentUser
            if (fbAuth.currentUser == null) {
                userProfile = null
            }
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose {
            Firebase.auth.removeAuthStateListener(listener)
        }
    }

    // When currentUser changes, fetch and verify role profile
    LaunchedEffect(currentUser) {
        val user = currentUser
        if (user != null) {
            isProfileLoading = true
            try {
                userProfile = authManager.syncAndFetchUserProfile(user)
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to fetch profile: ${e.message}")
            } finally {
                isProfileLoading = false
            }
        } else {
            userProfile = null
        }
    }

    fun handleGoogleSignIn() {
        val credentialManager = CredentialManager.create(activity)
        val webClientIdRes = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
        val serverClientId = if (webClientIdRes != 0) activity.getString(webClientIdRes) else ""

        if (serverClientId.isEmpty()) {
            Log.w("MainActivity", "default_web_client_id not found in resources; Google Sign In requires web client id.")
            return
        }

        val googleSignInOption = GetSignInWithGoogleOption.Builder(serverClientId = serverClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(googleSignInOption).build()

        coroutineScope.launch {
            try {
                val result = credentialManager.getCredential(activity, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    val loginRes = authManager.signInWithGoogleCredential(authCredential)
                    loginRes.onSuccess { profile ->
                        userProfile = profile
                    }
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w("MainActivity", "Google Sign-In was cancelled or dismissed: ${e.message}")
            } catch (e: Exception) {
                Log.e("MainActivity", "Google Sign-In failed", e)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Slate900)
    ) {
        when {
            isProfileLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PrimaryBlue, strokeWidth = 3.dp)
                }
            }
            currentUser == null || userProfile == null -> {
                AuthScreen(
                    authManager = authManager,
                    onAuthSuccess = { profile ->
                        userProfile = profile
                    },
                    onGoogleSignInClick = {
                        handleGoogleSignIn()
                    }
                )
            }
            userProfile!!.isSuperAdmin -> {
                SuperAdminDashboard(
                    userProfile = userProfile!!,
                    authManager = authManager,
                    crmRepository = crmRepository,
                    onSignOutRequested = { notice ->
                        sessionNotice = notice
                        userProfile = null
                    }
                )
            }
            else -> {
                ClientDashboard(
                    userProfile = userProfile!!,
                    authManager = authManager,
                    crmRepository = crmRepository,
                    onSignOutRequested = {
                        userProfile = null
                    }
                )
            }
        }
    }
}
