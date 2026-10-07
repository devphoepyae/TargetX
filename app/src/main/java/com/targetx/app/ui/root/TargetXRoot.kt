package com.targetx.app.ui.root

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.targetx.app.domain.model.AuthState
import com.targetx.app.ui.auth.AuthScreen
import com.targetx.app.ui.home.HomeScreen

@Composable
fun TargetXRoot(viewModel: RootViewModel = hiltViewModel()) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize()) {
        Crossfade(targetState = authState::class, label = "root") { stateClass ->
            when (stateClass) {
                AuthState.Loading::class -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                AuthState.Authenticated::class ->
                    HomeScreen(user = (authState as? AuthState.Authenticated)?.user)
                else -> AuthScreen()
            }
        }
    }
}
