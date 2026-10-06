package dev.dshmobile.app

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dshmobile.app.ui.MobileApp
import dev.dshmobile.app.ui.MobileTheme
import dev.dshmobile.app.ui.MobileViewModel

class MainActivity : ComponentActivity() {
    private var notificationIntent by androidx.compose.runtime.mutableStateOf<android.content.Intent?>(null)
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); setIntent(intent); notificationIntent = intent }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationIntent = intent
        enableEdgeToEdge()
        setContent {
            MobileTheme {
                val model: MobileViewModel = viewModel()
                androidx.compose.runtime.LaunchedEffect(notificationIntent) {
                    model.openNotificationChat(notificationIntent?.getStringExtra("notificationDevice"), notificationIntent?.getStringExtra("notificationChat"))
                }
                MobileApp(model)
            }
        }
    }
}
