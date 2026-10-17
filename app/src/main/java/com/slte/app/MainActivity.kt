package com.slte.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slte.app.data.local.LocaleStore
import com.slte.app.data.local.ThemePreference
import com.slte.app.ui.navigation.SlteApp
import com.slte.app.ui.theme.SlteTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var themePreference: ThemePreference

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let { LocaleStore.wrapBase(it) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark by themePreference.dark.collectAsStateWithLifecycle()
            SlteTheme(darkTheme = dark) {
                SlteApp()
            }
        }
    }
}
