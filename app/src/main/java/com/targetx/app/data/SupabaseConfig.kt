package com.targetx.app.data

/** Supabase project settings, injected from BuildConfig (local.properties or environment variables). */
data class SupabaseConfig(
    val url: String,
    val anonKey: String,
) {
    val isConfigured: Boolean get() = url.isNotBlank() && anonKey.isNotBlank()

    /** Lets the client be constructed (and the UI report the problem) when config is missing. */
    val urlOrPlaceholder: String get() = url.ifBlank { PLACEHOLDER_URL }
    val anonKeyOrPlaceholder: String get() = anonKey.ifBlank { PLACEHOLDER_KEY }

    private companion object {
        const val PLACEHOLDER_URL = "https://unconfigured.supabase.co"
        const val PLACEHOLDER_KEY = "unconfigured"
    }
}
