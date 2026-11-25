/*

 This is the source code of exteraGram for Android.

 We do not and cannot prevent the use of our code,
 but be respectful and credit the original author.

 Copyright @exteraGram, 2022-2026

 Licensed under the GNU GPL v. 3 or later, with the additional terms
 in ADDITIONAL_TERMS.md at the root of the repository.

*/

package com.exteragram.messenger.xray

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog

/**
 * Persists Xray profiles and subscriptions in a dedicated SharedPreferences file.
 *
 * Kept separate from mainconfig so a corrupt profile list can never break the
 * Telegram settings store, and so the whole store can be exported as one blob.
 */
object XrayStorage {

    private const val PREFS_NAME = "xray_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_SUBSCRIPTIONS = "subscriptions"
    private const val KEY_SELECTED_PROFILE = "selected_profile"

    private val gson = Gson()
    private val profileListType = object : TypeToken<MutableList<XrayProfile>>() {}.type
    private val subscriptionListType = object : TypeToken<MutableList<XraySubscription>>() {}.type

    private fun prefs(): SharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0)

    @Synchronized
    fun getProfiles(): MutableList<XrayProfile> = try {
        val raw = prefs().getString(KEY_PROFILES, null) ?: return mutableListOf()
        gson.fromJson<MutableList<XrayProfile>>(raw, profileListType) ?: mutableListOf()
    } catch (e: Throwable) {
        FileLog.e(e)
        mutableListOf()
    }

    @Synchronized
    fun saveProfiles(profiles: List<XrayProfile>) {
        prefs().edit().putString(KEY_PROFILES, gson.toJson(profiles, profileListType)).apply()
    }

    @Synchronized
    fun getSubscriptions(): MutableList<XraySubscription> = try {
        val raw = prefs().getString(KEY_SUBSCRIPTIONS, null) ?: return mutableListOf()
        gson.fromJson<MutableList<XraySubscription>>(raw, subscriptionListType) ?: mutableListOf()
    } catch (e: Throwable) {
        FileLog.e(e)
        mutableListOf()
    }

    @Synchronized
    fun saveSubscriptions(subscriptions: List<XraySubscription>) {
        prefs().edit().putString(KEY_SUBSCRIPTIONS, gson.toJson(subscriptions, subscriptionListType)).apply()
    }

    @Synchronized
    fun getSelectedProfileId(): String = prefs().getString(KEY_SELECTED_PROFILE, "") ?: ""

    @Synchronized
    fun setSelectedProfileId(id: String) {
        prefs().edit().putString(KEY_SELECTED_PROFILE, id).apply()
    }

    @Synchronized
    fun findProfile(id: String): XrayProfile? = getProfiles().firstOrNull { it.id == id }

    @Synchronized
    fun upsertProfile(profile: XrayProfile) {
        val profiles = getProfiles()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            profiles[index] = profile
        } else {
            profiles.add(0, profile)
        }
        saveProfiles(profiles)
    }

    @Synchronized
    fun deleteProfile(id: String) {
        val profiles = getProfiles()
        profiles.removeAll { it.id == id }
        saveProfiles(profiles)
        if (getSelectedProfileId() == id) {
            setSelectedProfileId("")
        }
    }

    @Synchronized
    fun clearAll() {
        prefs().edit().clear().apply()
    }
}
