package tools.supernote.scan

import android.content.Context

/** Version the acknowledgment so material changes to the transfer can be disclosed again. */
internal class RecognitionDisclosure(context: Context, preferencesName: String = "recognition_disclosure") {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun acknowledged() = preferences.getInt("acknowledged_version", 0) >= CURRENT_VERSION
    fun acknowledge() = preferences.edit().putInt("acknowledged_version", CURRENT_VERSION).apply()
    fun planWelcomeSeen() = preferences.getBoolean("plan_welcome_seen", false)
    fun dismissPlanWelcome() = preferences.edit().putBoolean("plan_welcome_seen", true).apply()

    private companion object {
        const val CURRENT_VERSION = 1
    }
}
