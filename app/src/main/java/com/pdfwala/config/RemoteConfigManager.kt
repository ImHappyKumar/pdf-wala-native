package com.pdfwala.config

import android.content.Context
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import org.json.JSONObject

class RemoteConfigManager(context: Context) {

    private val remoteConfig = FirebaseRemoteConfig.getInstance()

    companion object {
        private const val UPDATE_CONFIG = "update_config"
        private const val APP_LINKS = "app_links"
        private const val ADS_CONFIG = "ads_config"

        private const val MIN_FETCH_INTERVAL_SECONDS = 12 * 60 * 60L

        // Local fallback links
        const val DEFAULT_SUPPORT_EMAIL = "happybytelabs@gmail.com"

        const val DEFAULT_PRIVACY_POLICY =
            "https://pdfwala.web.app/privacy-policy"

        const val DEFAULT_TERMS =
            "https://pdfwala.web.app/terms-of-use"

        const val DEFAULT_WEBSITE =
            "https://pdfwala.web.app/"

        const val DEFAULT_PLAY_STORE =
            "https://play.google.com/store/apps/details?id=com.pdfwala"
    }

    init {
        configure()
    }

    private fun configure() {

        val settings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(
                MIN_FETCH_INTERVAL_SECONDS
            )
            .build()

        remoteConfig.setConfigSettingsAsync(settings)

        remoteConfig.setDefaultsAsync(
            mapOf(

                UPDATE_CONFIG to """
                    {
                        "enable": false,
                        "version_target": 0,
                        "update_type": "immediate",
                        "message": "A new version of PDF Wala is available.",
                        "cooldown_hours": 24
                    }
                """.trimIndent(),

                APP_LINKS to """
                    {
                        "support_email": "$DEFAULT_SUPPORT_EMAIL",
                        "privacy_policy": "$DEFAULT_PRIVACY_POLICY",
                        "terms": "$DEFAULT_TERMS",
                        "website": "$DEFAULT_WEBSITE",
                        "play_store": "$DEFAULT_PLAY_STORE"
                    }
                """.trimIndent(),

                ADS_CONFIG to """
                    {
                        "enable": false,
                        "banner": false,
                        "interstitial": false,
                        "native": false,
                        "max_interstitial_per_session": 3
                    }
                """.trimIndent()
            )
        )
    }

    /**
     * Fetch and activate the latest Remote Config.
     */
    fun fetchAndActivate(
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                onComplete?.invoke(task.isSuccessful)
            }
    }

    /**
     * Get update configuration JSON.
     */
    fun getUpdateConfig(): String {
        return remoteConfig.getString(UPDATE_CONFIG)
    }

    /**
     * Get app links configuration JSON.
     */
    fun getAppLinks(): String {
        return remoteConfig.getString(APP_LINKS)
    }

    /**
     * Get a specific app link.
     * Uses fallback if key is missing, blank or JSON is invalid.
     */
    fun getAppLink(
        key: String,
        fallback: String
    ): String {
        return try {
            JSONObject(getAppLinks())
                .optString(key)
                .takeIf { it.isNotBlank() }
                ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }

    /**
     * Get ads configuration JSON.
     */
    fun getAdsConfig(): String {
        return remoteConfig.getString(ADS_CONFIG)
    }
}