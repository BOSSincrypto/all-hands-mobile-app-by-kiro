package dev.openhands.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dev.openhands.mobile.security.GateResult
import dev.openhands.mobile.security.authenticateForCipher
import dev.openhands.mobile.service.TaskLaunchService
import dev.openhands.mobile.ui.auth.AuthUiState
import dev.openhands.mobile.ui.auth.AuthViewModel
import dev.openhands.mobile.ui.auth.AwaitingApprovalScreen
import dev.openhands.mobile.ui.auth.CipherRequest
import dev.openhands.mobile.ui.auth.LockScreen
import dev.openhands.mobile.ui.auth.SignInScreen
import dev.openhands.mobile.ui.chat.ChatScreen
import dev.openhands.mobile.ui.conversations.ConversationsScreen
import dev.openhands.mobile.ui.files.FilesScreen
import dev.openhands.mobile.ui.newtask.NewTaskScreen
import dev.openhands.mobile.ui.settings.SettingsScreen
import dev.openhands.mobile.ui.terminal.TerminalScreen
import dev.openhands.mobile.ui.theme.OpenHandsTheme
import kotlinx.coroutines.launch

private const val ROUTE_CONVERSATIONS = "conversations"
private const val ROUTE_NEW_TASK = "new-task"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_CHAT = "chat/{conversationId}"
private const val ROUTE_FILES = "files/{conversationId}"
private const val ROUTE_TERMINAL = "terminal/{conversationId}"

private const val CANVAS_URL = "https://app.all-hands.dev/canvas"
private const val WEB_SETTINGS_URL = "https://app.all-hands.dev/settings"

/**
 * Single activity host.
 *
 * Extends [FragmentActivity] because BiometricPrompt needs a fragment host, and the whole
 * auth model depends on the prompt returning an unlocked [javax.crypto.Cipher].
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    /**
     * Launch progress and the "task started" result are delivered as notifications, so the
     * permission is requested up front. Denial is not fatal: the app still works, the user
     * just has to come back to the app to see progress.
     */
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Result is advisory; nothing to do either way. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep conversation contents out of the recents thumbnail and block screenshots:
        // agent output can contain repository code and command output.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        requestNotificationPermissionIfNeeded()
        observeCipherRequests()
        observeLifecycleForLock()

        setContent {
            OpenHandsTheme {
                val state by authViewModel.state.collectAsStateWithLifecycle()
                when (val current = state) {
                    AuthUiState.Loading -> Unit

                    is AuthUiState.SignInRequired -> SignInScreen(
                        error = current.error,
                        onSignIn = authViewModel::startSignIn,
                    )

                    is AuthUiState.AwaitingApproval -> AwaitingApprovalScreen(
                        userCode = current.authorization.userCode,
                        opened = current.opened,
                        onOpenBrowser = {
                            openUrl(current.authorization.verificationUriComplete)
                            authViewModel.markBrowserOpened()
                        },
                        onCancel = authViewModel::cancelSignIn,
                    )

                    is AuthUiState.Locked -> LockScreen(
                        error = current.error,
                        onUnlock = authViewModel::requestUnlock,
                        onSignOut = authViewModel::signOut,
                    )

                    AuthUiState.Unlocked -> AppNavigation(
                        deepLinkConversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID),
                        onSignOut = authViewModel::signOut,
                        onOpenUrl = ::openUrl,
                    )
                }
            }
        }
    }

    /**
     * Bridges ViewModel cipher requests to the system biometric prompt.
     *
     * The ViewModel cannot show a prompt itself (it has no Activity), so it publishes a
     * request and this collects it.
     */
    private fun observeCipherRequests() {
        lifecycleScope.launch {
            authViewModel.cipherRequest.collect { request ->
                if (request == null) return@collect
                handleCipherRequest(request)
            }
        }
    }

    private suspend fun handleCipherRequest(request: CipherRequest) {
        val result = runCatching {
            authenticateForCipher(
                cipher = request.cipher,
                title = getString(R.string.biometric_title),
                subtitle = getString(
                    if (request.purpose == CipherRequest.Purpose.ENCRYPT) {
                        R.string.biometric_subtitle_save
                    } else {
                        R.string.biometric_subtitle_unlock
                    },
                ),
                cancelLabel = getString(R.string.action_cancel),
            )
        }.getOrElse { error ->
            if (error is KeyPermanentlyInvalidatedException) {
                GateResult.KeyInvalidated
            } else {
                GateResult.Failed(error.message ?: "Authentication failed")
            }
        }

        when (result) {
            is GateResult.Success -> authViewModel.onCipherUnlocked(request, result.cipher)
            GateResult.Cancelled -> authViewModel.onCipherCancelled()
            GateResult.KeyInvalidated -> authViewModel.onKeyInvalidated()
            is GateResult.Failed -> authViewModel.onGateFailed(result.message)
        }
    }

    /** Locks the in-memory session whenever the app leaves the foreground. */
    private fun observeLifecycleForLock() {
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) authViewModel.lock()
            },
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        // Notifications are granted implicitly below API 33, where the permission does not exist.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val permission = Manifest.permission.POST_NOTIFICATIONS
        val granted = ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(permission)
    }

    private fun openUrl(url: String) {
        runCatching {
            CustomTabsIntent.Builder().setShowTitle(true).build()
                .launchUrl(this, url.toUri())
        }.onFailure {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}

@Composable
private fun AppNavigation(
    deepLinkConversationId: String?,
    onSignOut: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(deepLinkConversationId) {
        deepLinkConversationId?.let { navController.navigate("chat/$it") }
    }

    NavHost(navController = navController, startDestination = ROUTE_CONVERSATIONS) {
        composable(ROUTE_CONVERSATIONS) {
            ConversationsScreen(
                onOpenConversation = { navController.navigate("chat/$it") },
                onNewTask = { navController.navigate(ROUTE_NEW_TASK) },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
            )
        }

        composable(ROUTE_NEW_TASK) {
            NewTaskScreen(
                onBack = navController::popBackStack,
                onLaunched = { startTaskId, title ->
                    // Hand the wait to a foreground service so leaving the app is safe.
                    TaskLaunchService.launch(context, startTaskId, title)
                    navController.popBackStack()
                },
            )
        }

        composable(
            route = ROUTE_CHAT,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("conversationId").orEmpty()
            ChatScreen(
                onBack = navController::popBackStack,
                onOpenFiles = { navController.navigate("files/$id") },
                onOpenTerminal = { navController.navigate("terminal/$id") },
            )
        }

        composable(
            route = ROUTE_FILES,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
        ) {
            FilesScreen(onBack = navController::popBackStack)
        }

        composable(
            route = ROUTE_TERMINAL,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
        ) {
            TerminalScreen(onBack = navController::popBackStack)
        }

        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                onBack = navController::popBackStack,
                onSignOut = onSignOut,
                onOpenCanvas = { onOpenUrl(CANVAS_URL) },
                onOpenWebSettings = { onOpenUrl(WEB_SETTINGS_URL) },
            )
        }
    }
}
