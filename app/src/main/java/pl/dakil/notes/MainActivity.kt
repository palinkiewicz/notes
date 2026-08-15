package pl.dakil.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import pl.dakil.notes.ui.theme.NotesTheme

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = AppContainer(applicationContext)

        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            NotesTheme(darkTheme = dark) {
                // The canvas needs to know the theme directly: page colours come from the document,
                // not from the M3 scheme, so it picks the light or dark paper itself.
                NotesApp(container = container, darkTheme = dark)
            }
        }
    }
}
