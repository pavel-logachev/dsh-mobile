package dev.dshmobile.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dshmobile.app.ui.MobileApp
import dev.dshmobile.app.ui.MobileTheme
import dev.dshmobile.app.ui.MobileViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MobileTheme {
                val model: MobileViewModel = viewModel()
                MobileApp(model)
            }
        }
    }
}
