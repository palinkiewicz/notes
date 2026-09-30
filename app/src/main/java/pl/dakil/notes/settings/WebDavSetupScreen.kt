package pl.dakil.notes.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import pl.dakil.notes.R
import pl.dakil.notes.data.AppSettings
import pl.dakil.notes.ui.components.flatTopAppBarColors
import pl.dakil.notes.ui.icons.NotesIcons
import java.util.Locale

/**
 * Address, username, password — and the connection is proved before it counts as set up.
 *
 * A form that accepts a typo and only fails hours later inside a background job has told the user
 * nothing, so [BackupSyncViewModel.connectWebDav] lists the collection before saving anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavSetupScreen(
    current: AppSettings,
    viewModel: BackupSyncViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    var url by rememberSaveable { mutableStateOf(current.webDavUrl) }
    var user by rememberSaveable { mutableStateOf(current.webDavUser) }
    var password by rememberSaveable { mutableStateOf("") }

    // `Locale.ROOT`: a Turkish device folds the `I` of "HTTPS" to a dotless `ı`, so a device-locale
    // comparison would call an encrypted address insecure on exactly the phones least able to say why.
    val insecure = url.trim().lowercase(Locale.ROOT).startsWith("http://")

    OutcomeDialog(
        message = state.webDavProbe ?: state.message,
        title = stringResource(R.string.settings_webdav_title),
        onDismiss = viewModel::acknowledge,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_webdav_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(NotesIcons.Back, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = flatTopAppBarColors(),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.settings_webdav_url)) },
                placeholder = { Text(stringResource(R.string.settings_webdav_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                isError = insecure,
                supportingText = if (insecure) {
                    { Text(stringResource(R.string.settings_webdav_insecure)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text(stringResource(R.string.settings_webdav_user)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.settings_webdav_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            Button(
                onClick = { viewModel.connectWebDav(url, user, password) },
                enabled = url.isNotBlank() && user.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_webdav_connect)) }
        }
    }
}
