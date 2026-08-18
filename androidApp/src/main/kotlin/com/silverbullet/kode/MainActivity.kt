package com.silverbullet.kode

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.silverbullet.kode.platform.AndroidMicPermission
import org.koin.android.ext.android.get

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must precede super.onCreate's RESUMED transition: launchers register
        // against this activity, and the mic permission bridge needs a live one.
        get<AndroidMicPermission>().attach(this)
        // Fully transparent scrims on both bars, so the app's own background
        // draws all the way to the edges. The default `enableEdgeToEdge()`
        // applies a translucent white scrim on the status bar, which is what
        // made the bars read as white slabs over a dark theme.
        //
        // `auto` still picks the icon contrast: dark icons on light
        // backgrounds and vice versa, following the system dark-mode setting —
        // the same input `KodeTheme` uses to choose Wave or Lotus.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        setContent {
            App()
        }
    }
}
