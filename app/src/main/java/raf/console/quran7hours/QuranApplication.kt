package raf.console.quran7hours

import android.app.Application

/**
 * Starts warming the bundled 604-page QPC pack before MainActivity/Compose is
 * created. The work is asynchronous and never blocks Application.onCreate().
 */
class QuranApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Tajweed is the default mode. If the saved preference is plain text,
        // Quran7HoursApp starts the V2 family preloader immediately afterwards.
        startQpcStartupPreload(this, tajweed = true)
    }
}
