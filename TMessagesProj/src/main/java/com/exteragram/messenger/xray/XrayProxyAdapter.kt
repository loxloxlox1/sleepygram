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
import com.exteragram.messenger.proxy.ProxyController
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SharedConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.utils.proxy.ProxySettings

/**
 * Bridges the local Xray SOCKS5 inbound into Telegram's proxy stack.
 *
 * Telegram treats it as an ordinary SOCKS5 proxy at 127.0.0.1:[port], so all
 * MTProto/HTTP traffic goes through Xray without touching the network core.
 */
object XrayProxyAdapter {

    private const val LOCAL_ADDRESS = "127.0.0.1"
    private const val LOCAL_NAME = "sleepygram Xray"

    /** Port that *this* process applied; 0 means "no Xray endpoint of ours". */
    @Volatile private var appliedPort: Int = 0

    /** Applies the local SOCKS5 endpoint as the active Telegram proxy. */
    fun apply(port: Int) {
        appliedPort = port
        try {
            val settings = ProxySettings.builder()
                .setType(ProxySettings.Type.SOCKS5)
                .setAddress(LOCAL_ADDRESS)
                .setPort(port)
                .build()

            val info = SharedConfig.ProxyInfo(settings)
            info.available = true
            info.ping = 0

            val controller = ProxyController.getInstance()
            val stored = controller.saveProxy(info, null, LOCAL_NAME)
            controller.setCurrentProxy(stored)

            val preferences: SharedPreferences = MessagesController.getGlobalMainSettings()
            val editor = preferences.edit()
            settings.toSharedPreferences(editor)
            editor.putBoolean("proxy_enabled", true)
            editor.apply()

            ConnectionsManager.setProxySettings(true, settings)
            NotificationCenter.getGlobalInstance()
                .postNotificationNameOnUIThread(NotificationCenter.proxySettingsChanged)
            FileLog.d("Xray: applied local SOCKS5 proxy on port $port")
        } catch (e: Throwable) {
            FileLog.e("Xray: failed to apply proxy settings", e)
        }
    }

    /** Disables the proxy and removes the local entry from the proxy list. */
    fun clear() {
        try {
            val controller = ProxyController.getInstance()
            val current = controller.getCurrentProxy()
            if (current != null && isLocalProxy(current)) {
                controller.deleteProxy(current)
            }
            val preferences = MessagesController.getGlobalMainSettings()
            if (preferences.getBoolean("proxy_enabled", false)) {
                val editor = preferences.edit()
                ProxySettings.EMPTY.toSharedPreferences(editor)
                editor.putBoolean("proxy_enabled", false)
                editor.apply()
                ConnectionsManager.setProxySettings(false, null)
                NotificationCenter.getGlobalInstance()
                    .postNotificationNameOnUIThread(NotificationCenter.proxySettingsChanged)
            }
        } catch (e: Throwable) {
            FileLog.e("Xray: failed to clear proxy settings", e)
        } finally {
            appliedPort = 0
        }
    }

    /**
     * True if [info] is the endpoint this process installed. Compares against the
     * remembered port rather than [XrayController.getSocksPort], because the
     * controller resets that field before the main-thread cleanup runs.
     */
    fun isLocalProxy(info: SharedConfig.ProxyInfo?): Boolean {
        if (info == null) return false
        val settings = info.settings
        return settings.type == ProxySettings.Type.SOCKS5 &&
            settings.address == LOCAL_ADDRESS &&
            appliedPort > 0 &&
            settings.port == appliedPort
    }

    /**
     * Clears a proxy entry that points at 127.0.0.1 but is no longer backed by a
     * running Xray instance (e.g. after a crash or an update).
     */
    fun clearIfStaleLocal() {
        try {
            if (XrayController.isRunning()) return
            val controller = ProxyController.getInstance()
            val current = controller.getCurrentProxy() ?: return
            val settings = current.settings
            val isLocal = settings.type == ProxySettings.Type.SOCKS5 && settings.address == LOCAL_ADDRESS
            if (!isLocal) return
            controller.deleteProxy(current)
            val preferences = MessagesController.getGlobalMainSettings()
            val editor = preferences.edit()
            ProxySettings.EMPTY.toSharedPreferences(editor)
            editor.putBoolean("proxy_enabled", false)
            editor.apply()
            ConnectionsManager.setProxySettings(false, null)
            NotificationCenter.getGlobalInstance()
                .postNotificationNameOnUIThread(NotificationCenter.proxySettingsChanged)
        } catch (e: Throwable) {
            FileLog.e("Xray: stale proxy cleanup failed", e)
        }
    }
}
