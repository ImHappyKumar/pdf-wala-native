package com.pdfwala.config

import android.content.Context
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings

class RemoteConfigManager(
    context: Context
) {

    private val remoteConfig =
        FirebaseRemoteConfig.getInstance()

    companion object {

        private const val UPDATE_CONFIG = "update_config"
        private const val APP_LINKS = "app_links"
        private const val ADS_CONFIG = "ads_config"

        /*
         * Production:
         * Firebase will not fetch from the server
         * more frequently than every 12 hours.
         */
        private const val MIN_FETCH_INTERVAL_SECONDS =
            12 * 60 * 60L
    }

    init {
        configure()
    }

    /**
     * Configure Firebase Remote Config.
     */
    private fun configure() {

        val settings =
            FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(
                    MIN_FETCH_INTERVAL_SECONDS
                )
                .build()

        remoteConfig.setConfigSettingsAsync(settings)

        /*
         * Safe local defaults.
         *
         * These values are used if Firebase has never
         * successfully provided a configuration.
         */
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
                        "support_email": "happybytelabs@gmail.com",
                        "privacy_policy": "https://happybyte.web.app/privacy/pdf-wala",
                        "terms": "https://happybyte.web.app/terms/pdf-wala",
                        "website": "https://pdfwala.web.app/"
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
     *
     * Firebase's minimum fetch interval controls how often
     * the backend is contacted.
     *
     * If fetching fails, already activated values remain available.
     */
    fun fetchAndActivate(
        onComplete: ((Boolean) -> Unit)? = null
    ) {

        remoteConfig
            .fetchAndActivate()
            .addOnCompleteListener { task ->

                onComplete?.invoke(
                    task.isSuccessful
                )
            }
    }

    /**
     * Get the update configuration JSON.
     */
    fun getUpdateConfig(): String {
        return remoteConfig.getString(
            UPDATE_CONFIG
        )
    }

    /**
     * Get app links configuration JSON.
     */
    fun getAppLinks(): String {
        return remoteConfig.getString(
            APP_LINKS
        )
    }

    /**
     * Get ads configuration JSON.
     */
    fun getAdsConfig(): String {
        return remoteConfig.getString(
            ADS_CONFIG
        )
    }
}