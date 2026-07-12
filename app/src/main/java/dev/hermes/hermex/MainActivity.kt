package dev.hermes.hermex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import dev.hermes.hermex.ui.HermexApp

/**
 * The single activity hosting the entire Compose UI.
 *
 * Uses a dark Material 3 color scheme as the default (and only) theme.
 * Light theme is intentionally omitted for now per the product direction.
 *
 * enableEdgeToEdge() allows content to draw behind system bars, and
 * individual screens use WindowInsets padding (statusBarsPadding,
 * navigationBarsPadding) to avoid overlapping with system UI.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable edge-to-edge: content draws behind status bar and
        // navigation bar. We use dark system bar styles so the status
        // bar icons are white (visible on our dark theme).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            HermexTheme {
                HermexApp()
            }
        }
    }
}

@Composable
private fun HermexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content
    )
}
