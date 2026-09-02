package top.wsdx233.love2droid

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class Love2DroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val settings = SettingsStore(this)
        AppCompatDelegate.setDefaultNightMode(settings.appThemeMode.toAppCompatNightMode())
    }
}
