package top.guozk.pipilot.sessions

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import java.util.UUID

class SharedPreferencesClientIdentityStore(
    private val context: Context,
) : ClientIdentityStore {
    private val prefs = context.getSharedPreferences("client_identity_prefs", Context.MODE_PRIVATE)

    @SuppressLint("HardwareIds")
    override fun getClientId(): String {
        // 1. Try to get from SharedPreferences cache
        val cachedId = prefs.getString("client_id", null)
        if (cachedId != null) {
            return cachedId
        }

        // 2. Try to get ANDROID_ID
        val androidId =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID,
            )

        val finalId =
            if (!androidId.isNullOrBlank()) {
                androidId
            } else {
                // 3. Fallback to generated UUID
                UUID.randomUUID().toString()
            }

        prefs.edit { putString("client_id", finalId) }
        return finalId
    }
}
