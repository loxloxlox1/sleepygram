/*

 This is the source code of exteraGram for Android.

 We do not and cannot prevent the use of our code,
 but be respectful and credit the original author.

 Copyright @exteraGram, 2022-2026

 Licensed under the GNU GPL v. 3 or later, with the additional terms
 in ADDITIONAL_TERMS.md at the root of the repository.

*/

package com.exteragram.messenger.xray

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import com.exteragram.messenger.preferences.BasePreferencesActivity
import com.exteragram.messenger.utils.addIf
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.util.ArrayList

/**
 * Xray tunnel settings: enable/disable, manage profiles, import from clipboard
 * or subscription URL, and check latency.
 */
class XrayPreferencesActivity : BasePreferencesActivity() {

    enum class XrayItem {
        ENABLED,
        STATUS,
        PROFILES_HEADER,
        IMPORT_CLIPBOARD,
        IMPORT_SUBSCRIPTION,
        ACTIVE_PROFILE,
        CHECK_ALL,
        INFO;

        val id: Int = ordinal + 1
    }

    private var profiles: MutableList<XrayProfile> = mutableListOf()
    private var selectedId: String = ""

    override fun getTitle(): String = getString(R.string.XraySettings)

    override fun onFragmentCreate(): Boolean {
        reload()
        return super.onFragmentCreate()
    }

    override fun onResume() {
        super.onResume()
        reload()
        if (fragmentView != null) listView.adapter?.update(true)
    }

    private fun reload() {
        profiles = XrayStorage.getProfiles()
        selectedId = XrayStorage.getSelectedProfileId()
    }

    private val selectedProfile: XrayProfile?
        get() = profiles.firstOrNull { it.id == selectedId }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(getString(R.string.XrayConnection)))

        items.add(
            uCheck(
                XrayItem.ENABLED,
                getString(R.string.XrayEnable),
                XrayController.isRunning(),
                subtext = if (XrayController.isRunning())
                    LocaleController.formatString(R.string.XrayRunningOnPort, XrayController.getSocksPort())
                else getString(R.string.XrayDisabled),
                linkAlias = "xrayEnable"
            )
        )

        val active = selectedProfile
        if (active != null) {
            items.add(
                uButton(
                    XrayItem.ACTIVE_PROFILE,
                    getString(R.string.XrayActiveProfile),
                    value = buildString {
                        append(active.name.ifEmpty { active.remark.ifEmpty { getString(R.string.XrayUnnamedProfile) } })
                        if (active.lastDelayMs > 0) append(" · ${active.lastDelayMs} ms")
                    },
                    linkAlias = "xrayActiveProfile"
                )
            )
        }
        items.add(UItem.asShadow(getString(R.string.XrayConnectionInfo)))

        items.add(UItem.asHeader(getString(R.string.XrayProfiles)))
        if (profiles.isEmpty()) {
            items.add(uButton(XrayItem.IMPORT_CLIPBOARD, getString(R.string.XrayImportFromClipboard), R.drawable.msg_copy, linkAlias = "xrayImportClipboard"))
            items.add(uButton(XrayItem.IMPORT_SUBSCRIPTION, getString(R.string.XrayAddSubscription), R.drawable.msg_link, linkAlias = "xrayAddSubscription"))
            items.add(UItem.asShadow(getString(R.string.XrayNoProfiles)))
        } else {
            profiles.forEachIndexed { index, profile ->
                val isSelected = profile.id == selectedId
                items.add(
                    uButton(
                        PROFILE_ITEM_BASE + index,
                        profile.name.ifEmpty { profile.remark.ifEmpty { getString(R.string.XrayUnnamedProfile) } },
                        if (isSelected) R.drawable.msg_check_s else 0,
                        value = if (profile.lastDelayMs > 0) "${profile.lastDelayMs} ms" else null,
                        subtext = if (isSelected) getString(R.string.XrayProfileSelected) else profile.remark.takeIf { it.isNotEmpty() },
                        showDivider = index != profiles.lastIndex,
                        searchable = false
                    )
                )
            }
            items.add(uButton(XrayItem.CHECK_ALL, getString(R.string.XrayCheckAll), R.drawable.msg_retry, linkAlias = "xrayCheckAll"))
            items.add(uButton(XrayItem.IMPORT_CLIPBOARD, getString(R.string.XrayImportFromClipboard), R.drawable.msg_copy, linkAlias = "xrayImportClipboard"))
            items.add(uButton(XrayItem.IMPORT_SUBSCRIPTION, getString(R.string.XrayAddSubscription), R.drawable.msg_link, linkAlias = "xrayAddSubscription"))
            items.add(UItem.asShadow(LocaleController.formatString(R.string.XrayProfilesInfo, XrayController.version().ifEmpty { "—" })))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id >= PROFILE_ITEM_BASE) {
            val index = item.id - PROFILE_ITEM_BASE
            profiles.getOrNull(index)?.let { showProfileOptions(it) }
            return
        }
        if (item.id <= 0 || item.id > XrayItem.entries.size) return
        when (XrayItem.entries[item.id - 1]) {
            XrayItem.ENABLED -> toggleXray(item)
            XrayItem.ACTIVE_PROFILE -> showProfilePicker()
            XrayItem.IMPORT_CLIPBOARD -> importFromClipboard()
            XrayItem.IMPORT_SUBSCRIPTION -> showSubscriptionDialog()
            XrayItem.CHECK_ALL -> checkAllProfiles()
            else -> {}
        }
    }

    private fun toggleXray(item: UItem) {
        // The checkbox state is persisted so a cold start restores the tunnel.
        val prefs = org.telegram.messenger.MessagesController.getGlobalMainSettings()
        prefs.edit().putBoolean("xray_autostart", !XrayController.isRunning()).apply()
        if (XrayController.isRunning()) {
            XrayController.stop { success ->
                if (success) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.XrayStopped)).show()
                    listView.adapter?.update(true)
                }
            }
            return
        }
        val profile = selectedProfile ?: profiles.firstOrNull()
        if (profile == null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.XrayNoProfiles)).show()
            return
        }
        if (profile.id != selectedId) {
            selectedId = profile.id
            XrayStorage.setSelectedProfileId(profile.id)
        }
        BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.XrayStarting)).show()
        XrayController.start(profile) { success, error ->
            if (!success) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.XrayStartFailed, error)).show()
            } else {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.XrayStarted)).show()
            }
            if (fragmentView != null) listView.adapter?.update(true)
        }
    }

    private fun showProfilePicker() {
        if (profiles.isEmpty()) return
        val names = profiles.map { it.name.ifEmpty { it.remark.ifEmpty { getString(R.string.XrayUnnamedProfile) } } }.toTypedArray()
        val current = profiles.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        AlertDialog.Builder(context, resourceProvider)
            .setTitle(getString(R.string.XraySelectProfile))
            .setItems(names) { _, which ->
                val profile = profiles.getOrNull(which) ?: return@setItems
                selectedId = profile.id
                XrayStorage.setSelectedProfileId(profile.id)
                if (XrayController.isRunning()) {
                    XrayController.start(profile) { _, _ -> listView.adapter?.update(true) }
                } else {
                    listView.adapter?.update(true)
                }
            }
            .setNegativeButton(getString(R.string.Cancel), null)
            .show()
    }

    private fun showProfileOptions(profile: XrayProfile) {
        val options = arrayOf(
            getString(R.string.XrayUseProfile),
            getString(R.string.XrayCheck),
            getString(R.string.XrayRename),
            getString(R.string.XrayDelete)
        )
        AlertDialog.Builder(context, resourceProvider)
            .setTitle(profile.name.ifEmpty { profile.remark.ifEmpty { getString(R.string.XrayUnnamedProfile) } })
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        selectedId = profile.id
                        XrayStorage.setSelectedProfileId(profile.id)
                        if (XrayController.isRunning()) {
                            XrayController.start(profile) { _, _ -> listView.adapter?.update(true) }
                        } else listView.adapter?.update(true)
                    }
                    1 -> checkProfile(profile)
                    2 -> showRenameDialog(profile)
                    3 -> confirmDelete(profile)
                }
            }
            .setNegativeButton(getString(R.string.Cancel), null)
            .show()
    }

    private fun showRenameDialog(profile: XrayProfile) {
        val editText = EditTextBoldCursor(context).apply {
            lineYFix = true
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
            setText(profile.name)
            setHintText(getString(R.string.XrayProfileName))
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider))
            setHintColor(Theme.getColor(Theme.key_groupcreate_hintText, resourceProvider))
            isFocusable = true
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            background = null
            setLineColors(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourceProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourceProvider),
                Theme.getColor(Theme.key_text_RedRegular, resourceProvider)
            )
            setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourceProvider))
            setPadding(0, AndroidUtilities.dp(6f), 0, AndroidUtilities.dp(6f))
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24f, 0f, 24f, 10f))
        }
        AlertDialog.Builder(context, resourceProvider)
            .setTitle(getString(R.string.XrayRename))
            .setView(container)
            .setPositiveButton(getString(R.string.Done)) { _, _ ->
                profile.name = editText.text?.toString()?.trim().orEmpty()
                XrayStorage.upsertProfile(profile)
                reload()
                listView.adapter?.update(true)
            }
            .setNegativeButton(getString(R.string.Cancel), null)
            .show()
    }

    private fun confirmDelete(profile: XrayProfile) {
        AlertDialog.Builder(context, resourceProvider)
            .setTitle(getString(R.string.XrayDelete))
            .setMessage(LocaleController.formatString(R.string.XrayDeleteConfirm, profile.name.ifEmpty { profile.remark }))
            .setPositiveButton(getString(R.string.Delete)) { _, _ ->
                if (XrayController.getActiveProfileId() == profile.id) {
                    XrayController.stop()
                }
                XrayStorage.deleteProfile(profile.id)
                reload()
                listView.adapter?.update(true)
            }
            .setNegativeButton(getString(R.string.Cancel), null)
            .show()
    }

    private fun checkProfile(profile: XrayProfile) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.XrayChecking)).show()
        XrayController.ping(profile) { delay ->
            profile.lastDelayMs = if (delay > 0) delay else 0
            profile.lastCheckTime = System.currentTimeMillis()
            XrayStorage.upsertProfile(profile)
            reload()
            if (fragmentView != null) listView.adapter?.update(true)
            val text = if (delay > 0) LocaleController.formatString(R.string.XrayPingResult, delay) else getString(R.string.XrayPingFailed)
            BulletinFactory.of(this).createSimpleBulletin(if (delay > 0) R.raw.contact_check else R.raw.error, text).show()
        }
    }

    private fun checkAllProfiles() {
        if (profiles.isEmpty()) return
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.XrayChecking)).show()
        Utilities.globalQueue.postRunnable {
            profiles.forEach { profile ->
                var delay = -1L
                try {
                    val port = LibXrayBridge.getFreePort()
                    val config = XrayConfigBuilder.build(profile.xrayJson, port)
                    val results = LibXrayBridge.pingBatch(listOf(config to XrayConfigBuilder.OUTBOUND_TAG))
                    val result = results.firstOrNull()
                    if (result != null && result.success) delay = result.delay
                } catch (e: Throwable) {
                    // ignore per-profile failures
                }
                profile.lastDelayMs = if (delay > 0) delay else 0
                profile.lastCheckTime = System.currentTimeMillis()
                XrayStorage.upsertProfile(profile)
            }
            AndroidUtilities.runOnUIThread {
                reload()
                if (fragmentView != null) listView.adapter?.update(true)
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.XrayCheckDone)).show()
            }
        }
    }

    private fun importFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.XrayClipboardEmpty)).show()
            return
        }
        importText(text)
    }

    private fun importText(text: String) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.XrayImporting)).show()
        Utilities.globalQueue.postRunnable {
            try {
                val xrayJson = LibXrayBridge.convertShareLinksToXrayJson(text)
                val count = countOutbounds(xrayJson)
                if (count == 0) throw IllegalStateException("no outbounds")
                val baseName = guessName(text)
                val imported = mutableListOf<XrayProfile>()
                for (i in 0 until count) {
                    val single = extractOutbound(xrayJson, i)
                    imported.add(
                        XrayProfile(
                            id = java.util.UUID.randomUUID().toString(),
                            name = if (count == 1) baseName else "$baseName #${i + 1}",
                            xrayJson = single,
                            remark = ""
                        )
                    )
                }
                imported.forEach { XrayStorage.upsertProfile(it) }
                if (XrayStorage.getSelectedProfileId().isEmpty() && imported.isNotEmpty()) {
                    XrayStorage.setSelectedProfileId(imported.first().id)
                }
                AndroidUtilities.runOnUIThread {
                    reload()
                    if (fragmentView != null) listView.adapter?.update(true)
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.formatString(R.string.XrayImported, imported.size)).show()
                }
            } catch (e: Throwable) {
                AndroidUtilities.runOnUIThread {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.XrayImportFailed, e.message ?: "")).show()
                }
            }
        }
    }

    private fun showSubscriptionDialog() {
        val editText = EditTextBoldCursor(context).apply {
            lineYFix = true
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
            setHintText("https://example.com/sub")
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider))
            setHintColor(Theme.getColor(Theme.key_groupcreate_hintText, resourceProvider))
            isFocusable = true
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = null
            setLineColors(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourceProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourceProvider),
                Theme.getColor(Theme.key_text_RedRegular, resourceProvider)
            )
            setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourceProvider))
            setPadding(0, AndroidUtilities.dp(6f), 0, AndroidUtilities.dp(6f))
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24f, 0f, 24f, 10f))
        }
        AlertDialog.Builder(context, resourceProvider)
            .setTitle(getString(R.string.XrayAddSubscription))
            .setView(container)
            .setPositiveButton(getString(R.string.Add)) { _, _ ->
                val url = editText.text?.toString()?.trim().orEmpty()
                if (url.isNotEmpty()) fetchSubscription(url)
            }
            .setNegativeButton(getString(R.string.Cancel), null)
            .show()
    }

    private fun fetchSubscription(url: String) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.XrayDownloading)).show()
        Utilities.globalQueue.postRunnable {
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "sleepygram-xray")
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                val xrayJson = LibXrayBridge.convertShareLinksToXrayJson(text)
                val count = countOutbounds(xrayJson)
                if (count == 0) throw IllegalStateException("no outbounds")

                val subscriptions = XrayStorage.getSubscriptions()
                val previous = subscriptions.firstOrNull { it.url == url }
                val subscriptionId = previous?.id ?: java.util.UUID.randomUUID().toString()
                val subscriptionName = previous?.name?.takeIf { it.isNotEmpty() }
                    ?: java.net.URI(url).host ?: getString(R.string.XraySubscription)
                val ids = mutableListOf<String>()
                val existing = XrayStorage.getProfiles().filter { it.subscriptionId == subscriptionId }
                for (i in 0 until count) {
                    val single = extractOutbound(xrayJson, i)
                    val name = "${subscriptionName} #${i + 1}"
                    val existingProfile = existing.firstOrNull { it.name == name }
                    val profile = XrayProfile(
                        id = existingProfile?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        xrayJson = single,
                        subscriptionId = subscriptionId
                    )
                    ids.add(profile.id)
                    XrayStorage.upsertProfile(profile)
                }
                // Drop profiles that vanished from the refreshed subscription.
                XrayStorage.getProfiles()
                    .filter { it.subscriptionId == subscriptionId && it.id !in ids }
                    .forEach { XrayStorage.deleteProfile(it.id) }
                subscriptions.removeAll { it.id == subscriptionId }
                subscriptions.add(0, XraySubscription(subscriptionId, url, subscriptionName, System.currentTimeMillis(), ids))
                XrayStorage.saveSubscriptions(subscriptions)
                if (XrayStorage.getSelectedProfileId().isEmpty() && ids.isNotEmpty()) {
                    XrayStorage.setSelectedProfileId(ids.first())
                }
                AndroidUtilities.runOnUIThread {
                    reload()
                    if (fragmentView != null) listView.adapter?.update(true)
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.formatString(R.string.XrayImported, count)).show()
                }
            } catch (e: Throwable) {
                AndroidUtilities.runOnUIThread {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.XrayImportFailed, e.message ?: "")).show()
                }
            }
        }
    }

    private fun countOutbounds(xrayJson: String): Int = try {
        com.google.gson.JsonParser.parseString(xrayJson).asJsonObject.getAsJsonArray("outbounds")?.size() ?: 0
    } catch (e: Throwable) {
        0
    }

    private fun extractOutbound(xrayJson: String, index: Int): String {
        val root = com.google.gson.JsonParser.parseString(xrayJson).asJsonObject
        val outbounds = root.getAsJsonArray("outbounds") ?: throw IllegalArgumentException("no outbounds")
        val single = com.google.gson.JsonObject()
        val array = com.google.gson.JsonArray()
        array.add(outbounds[index])
        single.add("outbounds", array)
        return single.toString()
    }

    private fun guessName(text: String): String {
        val firstLine = text.lineSequence().firstOrNull { it.contains("://") } ?: return getString(R.string.XrayImport)
        val fragment = firstLine.substringAfter('#', "").trim()
        return fragment.ifEmpty { getString(R.string.XrayImport) }
    }

    companion object {
        private const val PROFILE_ITEM_BASE = 1000
    }
}
