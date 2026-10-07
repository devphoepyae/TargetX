package com.targetx.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.targetx.app.R
import com.targetx.app.domain.model.AuthError
import com.targetx.app.domain.validation.EmailError
import com.targetx.app.domain.validation.PasswordError
import com.targetx.app.ui.theme.TargetXTheme

@Composable
fun AuthScreen(viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AuthContent(state = state, onEvent = viewModel::onEvent)
}

@Composable
fun AuthContent(state: AuthUiState, onEvent: (AuthUiEvent) -> Unit) {
    val focusManager = LocalFocusManager.current
    val isSignUp = state.mode == AuthMode.SIGN_UP

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.auth_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.isConfigured) {
                MessageCard(text = stringResource(R.string.auth_not_configured), isError = true)
            }
            state.confirmationSentTo?.let {
                MessageCard(text = stringResource(R.string.auth_confirmation_sent, it), isError = false)
            }
            state.error?.let { MessageCard(text = it.message(), isError = true) }

            TabRow(selectedTabIndex = state.mode.ordinal) {
                Tab(
                    selected = !isSignUp,
                    onClick = { onEvent(AuthUiEvent.ModeChanged(AuthMode.LOGIN)) },
                    text = { Text(stringResource(R.string.auth_tab_login)) },
                    modifier = Modifier.testTag("tab_login"),
                )
                Tab(
                    selected = isSignUp,
                    onClick = { onEvent(AuthUiEvent.ModeChanged(AuthMode.SIGN_UP)) },
                    text = { Text(stringResource(R.string.auth_tab_sign_up)) },
                    modifier = Modifier.testTag("tab_sign_up"),
                )
            }

            OutlinedTextField(
                value = state.email,
                onValueChange = { onEvent(AuthUiEvent.EmailChanged(it)) },
                label = { Text(stringResource(R.string.auth_email)) },
                singleLine = true,
                isError = state.emailError != null,
                supportingText = state.emailError?.let { { Text(it.message()) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth().testTag("field_email"),
            )

            PasswordField(
                value = state.password,
                label = stringResource(R.string.auth_password),
                visible = state.passwordVisible,
                error = state.passwordError,
                enabled = !state.isLoading,
                imeAction = if (isSignUp) ImeAction.Next else ImeAction.Done,
                onDone = { focusManager.clearFocus(); onEvent(AuthUiEvent.Submit) },
                onValueChange = { onEvent(AuthUiEvent.PasswordChanged(it)) },
                onToggleVisibility = { onEvent(AuthUiEvent.TogglePasswordVisibility) },
                modifier = Modifier.testTag("field_password"),
            )

            if (isSignUp) {
                PasswordField(
                    value = state.confirmPassword,
                    label = stringResource(R.string.auth_confirm_password),
                    visible = state.passwordVisible,
                    error = state.confirmPasswordError,
                    enabled = !state.isLoading,
                    imeAction = ImeAction.Done,
                    onDone = { focusManager.clearFocus(); onEvent(AuthUiEvent.Submit) },
                    onValueChange = { onEvent(AuthUiEvent.ConfirmPasswordChanged(it)) },
                    onToggleVisibility = { onEvent(AuthUiEvent.TogglePasswordVisibility) },
                    modifier = Modifier.testTag("field_confirm_password"),
                )
            }

            Button(
                onClick = { focusManager.clearFocus(); onEvent(AuthUiEvent.Submit) },
                enabled = !state.isLoading && state.isConfigured,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("button_submit"),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(if (isSignUp) R.string.auth_submit_sign_up else R.string.auth_submit_login))
                }
            }
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    label: String,
    visible: Boolean,
    error: PasswordError?,
    enabled: Boolean,
    imeAction: ImeAction,
    onDone: () -> Unit,
    onValueChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it.message()) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (visible) R.string.auth_hide_password else R.string.auth_show_password),
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun MessageCard(text: String, isError: Boolean) {
    val colors = if (isError) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth().testTag("message_card")) {
        Text(text = text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmailError.message(): String = when (this) {
    EmailError.Blank -> stringResource(R.string.error_email_blank)
    EmailError.Invalid -> stringResource(R.string.error_email_invalid)
}

@Composable
private fun PasswordError.message(): String = when (this) {
    PasswordError.Blank -> stringResource(R.string.error_password_blank)
    is PasswordError.TooShort -> stringResource(R.string.error_password_too_short, minLength)
    PasswordError.Mismatch -> stringResource(R.string.error_password_mismatch)
}

@Composable
private fun AuthError.message(): String = when (this) {
    AuthError.NotConfigured -> stringResource(R.string.auth_not_configured)
    AuthError.InvalidCredentials -> stringResource(R.string.error_invalid_credentials)
    AuthError.EmailNotConfirmed -> stringResource(R.string.error_email_not_confirmed)
    AuthError.UserAlreadyExists -> stringResource(R.string.error_user_already_exists)
    AuthError.WeakPassword -> stringResource(R.string.error_weak_password)
    AuthError.RateLimited -> stringResource(R.string.error_rate_limited)
    AuthError.Network -> stringResource(R.string.error_network)
    is AuthError.Unknown -> stringResource(R.string.error_unknown, message)
}

@Preview(showBackground = true)
@Composable
private fun AuthContentLoginPreview() {
    TargetXTheme(dynamicColor = false) {
        AuthContent(state = AuthUiState(), onEvent = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AuthContentSignUpPreview() {
    TargetXTheme(dynamicColor = false) {
        AuthContent(
            state = AuthUiState(mode = AuthMode.SIGN_UP, email = "rider@example.com", error = AuthError.UserAlreadyExists),
            onEvent = {},
        )
    }
}
