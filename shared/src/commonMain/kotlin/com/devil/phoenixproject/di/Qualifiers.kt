package com.devil.phoenixproject.di

import org.koin.core.qualifier.named

/**
 * Koin qualifier for the encrypted/secure Settings instance.
 *
 * On Android this resolves to EncryptedSharedPreferences-backed Settings.
 * On iOS this resolves to KeychainSettings (iOS Keychain) for secure storage.
 *
 * Both platforms perform one-time migration from legacy storage (plain SharedPreferences
 * or NSUserDefaults) to secure storage on first access after upgrade.
 *
 * Used by PortalTokenStorage for JWT, refresh token, and user identity.
 */
val SecureSettingsQualifier = named("secureSettings")

/**
 * Koin qualifier for the build-time Supabase config (Android BuildConfig / iOS Info.plist).
 *
 * Platform modules publish it under this qualifier; the unqualified SupabaseConfig the
 * rest of the graph consumes is resolved by PortalServerRepository from the user's server
 * selection, which falls back to this bootstrap config.
 */
val BootstrapSupabaseConfigQualifier = named("bootstrapSupabaseConfig")
