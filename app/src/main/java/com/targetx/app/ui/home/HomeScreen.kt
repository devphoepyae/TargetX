package com.targetx.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.targetx.app.R
import com.targetx.app.domain.model.AuthUser

/** Placeholder main flow; replaced by the receipt dashboard in the next step. */
@Composable
fun HomeScreen(user: AuthUser?, viewModel: HomeViewModel = hiltViewModel()) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.home_welcome), style = MaterialTheme.typography.headlineMedium)
        user?.email?.let {
            Text(
                text = stringResource(R.string.home_signed_in_as, it),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("home_email"),
            )
        }
        Text(
            text = stringResource(R.string.home_placeholder),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = viewModel::onSignOut, modifier = Modifier.testTag("button_sign_out")) {
            Text(stringResource(R.string.home_sign_out))
        }
    }
}
