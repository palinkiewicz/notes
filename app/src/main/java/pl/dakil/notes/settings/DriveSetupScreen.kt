package pl.dakil.notes.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import pl.dakil.notes.R
import pl.dakil.notes.data.AppSettings
import pl.dakil.notes.ui.designsystem.AppLinearProgress
import pl.dakil.notes.ui.theme.Spacing

/**
 * The bring-your-own-credentials wizard.
 *
 * This is the one genuinely fiddly moment in the whole feature, and the introduction says why it
 * exists rather than hiding it: `drive.file` is a sensitive scope, Google caps an unverified
 * project at a hundred users **for the lifetime of the project**, and a key shipped inside the app
 * would therefore stop working for everyone after the hundredth person and could never be reset.
 * The user's own key has no cap and no gatekeeper.
 */
@Composable
fun DriveSetupScreen(
    current: AppSettings,
    viewModel: BackupSyncViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var clientId by rememberSaveable { mutableStateOf(current.driveClientId) }
    var clientSecret by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf(current.driveFolder) }

    OutcomeDialog(
        message = state.message,
        title = stringResource(R.string.settings_drive_title),
        onDismiss = viewModel::acknowledge,
    )

    SettingsSubscreen(
        title = stringResource(R.string.settings_drive_title),
        onNavigateBack = onNavigateBack,
        spacing = Spacing.m,
    ) {
        when (state.driveStep) {
            DriveStep.INTRODUCTION -> {
                Text(
                    stringResource(R.string.settings_drive_intro_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.settings_drive_intro_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SettingsGroup {
                    listOf(
                        R.string.settings_drive_step_1,
                        R.string.settings_drive_step_2,
                        R.string.settings_drive_step_3,
                        R.string.settings_drive_step_4,
                        R.string.settings_drive_step_5,
                    ).forEach { step -> SettingRow(title = stringResource(step)) }
                }
                FilledTonalButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/"))
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.settings_drive_open_console)) }

                Button(
                    onClick = { viewModel.driveStep(DriveStep.CREDENTIALS) },
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.settings_drive_continue)) }
            }

            DriveStep.CREDENTIALS -> {
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text(stringResource(R.string.settings_drive_client_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = clientSecret,
                    onValueChange = { clientSecret = it },
                    label = { Text(stringResource(R.string.settings_drive_client_secret)) },
                    singleLine = true,
                    // Masked as a courtesy, not as protection: a Desktop client's secret is
                    // documented by Google as not actually secret, and rclone ships one in the
                    // clear for the same reason.
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text(stringResource(R.string.settings_drive_folder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        viewModel.saveDriveCredentials(clientId, clientSecret)
                        viewModel.beginDriveAuthorization { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    },
                    enabled = clientId.isNotBlank() && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.settings_drive_authorize)) }
            }

            DriveStep.AUTHORIZING -> {
                Text(
                    stringResource(R.string.settings_drive_authorizing),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.settings_drive_authorizing_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                AppLinearProgress(Modifier.fillMaxWidth())
                state.pendingAuthorizationUrl?.let { url ->
                    FilledTonalButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(R.string.settings_drive_authorize)) }
                }
                FilledTonalButton(
                    onClick = viewModel::cancelDriveAuthorization,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(R.string.settings_drive_cancel)) }
            }

            DriveStep.CONNECTED -> {
                Text(
                    stringResource(R.string.settings_drive_connected),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onNavigateBack, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_close))
                }
            }
        }
    }
}
