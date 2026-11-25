/*

 This is the source code of exteraGram for Android.

 We do not and cannot prevent the use of our code,
 but be respectful and credit the original author.

 Copyright @exteraGram, 2022-2026

 Licensed under the GNU GPL v. 3 or later, with the additional terms
 in ADDITIONAL_TERMS.md at the root of the repository.

*/

package com.exteragram.messenger.xray

import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import libXray.LibXray
import org.telegram.messenger.FileLog
import org.telegram.messenger.Utilities
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin, synchronous wrapper around the libXray gobind API.
 *
 * libXray's `invoke` is blocking and must never run on the UI thread; every
 * method here is documented as "call from a worker thread".
 */
object LibXrayBridge {

    private const val API_VERSION = 3

    private val available: Boolean by lazy {
        try {
            LibXray.touch()
            LibXray.invoke(invokeRequest("xrayVersion", JsonObject()))
            true
        } catch (e: Throwable) {
            FileLog.e("Xray: libXray is unavailable", e)
            false
        }
    }

    fun isAvailable(): Boolean = available

    private fun invokeRequest(method: String, payload: JsonObject): String {
        val request = JsonObject()
        request.addProperty("apiVersion", API_VERSION)
        request.addProperty("method", method)
        request.add("payload", payload)
        return request.toString()
    }

    /**
     * Uniform response envelope: {"success":bool,"data":...,"error":"..."}
     */
    private fun invoke(method: String, payload: JsonObject = JsonObject()): JsonObject {
        val raw = LibXray.invoke(invokeRequest(method, payload))
        val response = JsonParser.parseString(raw).asJsonObject
        val success = response.get("success")?.asBoolean ?: false
        if (!success) {
            val error = response.get("error")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            throw XrayException(if (error.isEmpty()) "libXray $method failed" else error)
        }
        return response.getAsJsonObject("data") ?: JsonObject()
    }

    /** Returns a free TCP port on localhost. */
    fun getFreePort(exclude: Collection<Int> = emptyList()): Int {
        val payload = JsonObject()
        payload.addProperty("count", 1)
        val excludeArray = JsonArray()
        exclude.forEach(excludeArray::add)
        payload.add("excludePorts", excludeArray)
        val data = invoke("getFreePorts", payload)
        val ports = data.getAsJsonArray("ports")
        if (ports == null || ports.size() == 0) throw XrayException("no free port")
        return ports[0].asInt
    }

    /** Parses share links / base64 subscriptions / Age-encrypted text into outbound JSON. */
    fun convertShareLinksToXrayJson(text: String): String {
        val payload = JsonObject()
        payload.addProperty("text", text)
        val data = invoke("convertShareLinksToXrayJson", payload)
        // The converter returns the raw {"outbounds":[...]} document as data itself
        // in some builds and wrapped as {"xrayJson": "..."} in others; support both.
        data.get("xrayJson")?.let { if (!it.isJsonNull) return it.asString }
        return data.toString()
    }

    fun convertXrayJsonToShareLinks(xrayJson: String): String {
        val payload = JsonObject()
        payload.addProperty("xrayJson", xrayJson)
        return invoke("convertXrayJsonToShareLinks", payload).get("links")?.asString.orEmpty()
    }

    fun testXray(xrayJson: String) {
        val payload = JsonObject()
        payload.addProperty("xrayJson", xrayJson)
        invoke("testXray", payload)
    }

    fun runXray(xrayJson: String) {
        val payload = JsonObject()
        payload.addProperty("xrayJson", xrayJson)
        invoke("runXray", payload)
    }

    fun stopXray() {
        invoke("stopXray")
    }

    fun isRunning(): Boolean = try {
        invoke("getXrayState").get("running")?.asBoolean ?: false
    } catch (e: Throwable) {
        false
    }

    fun version(): String = try {
        invoke("xrayVersion").get("version")?.asString.orEmpty()
    } catch (e: Throwable) {
        ""
    }

    fun pingBatch(configs: List<Pair<String, String>>, timeout: Int = 3000, url: String = "https://www.google.com/generate_204", warm: Boolean = false): List<PingResult> {
        val payload = JsonObject()
        payload.addProperty("timeout", timeout)
        payload.addProperty("url", url)
        payload.addProperty("warm", warm)
        val items = JsonArray()
        configs.forEach { (xrayJson, tag) ->
            val item = JsonObject()
            item.addProperty("xrayJson", xrayJson)
            item.addProperty("outboundTag", tag)
            items.add(item)
        }
        payload.add("configs", items)
        val data = invoke("pingBatch", payload)
        val results = data.getAsJsonArray("results") ?: JsonArray()
        return results.map { element ->
            val obj = element.asJsonObject
            PingResult(
                success = obj.get("success")?.asBoolean ?: false,
                delay = obj.get("delay")?.asLong ?: 0L,
                error = obj.get("error")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            )
        }
    }

    data class PingResult(val success: Boolean, val delay: Long, val error: String)

    class XrayException(message: String) : Exception(message)
}

/**
 * Assembles a runnable Xray config from a stored outbound-only profile.
 *
 * The resulting document has:
 *  - one SOCKS5 inbound on 127.0.0.1:[port] (Telegram talks to this one)
 *  - one optional DNS inbound? no — DNS is left to the system
 *  - the profile's outbounds, tagged "proxy"
 *  - a "direct" and a "block" outbound for routing sanity
 *  - routing that sends 127.0.0.1/private ranges direct and everything else to "proxy"
 */
object XrayConfigBuilder {

    private const val INBOUND_TAG = "socks-in"
    private const val PROXY_TAG = "proxy"
    private const val DIRECT_TAG = "direct"
    private const val BLOCK_TAG = "block"

    fun build(profileXrayJson: String, socksPort: Int): String {
        val profile = JsonParser.parseString(profileXrayJson).asJsonObject
        val outbounds = JsonArray()

        val profileOutbounds = profile.getAsJsonArray("outbounds")
            ?: throw IllegalArgumentException("profile has no outbounds")

        val all = profileOutbounds.map { it.asJsonObject }
        val proxyOutbound = all.firstOrNull { outbound ->
            val protocol = outbound.get("protocol")?.asString.orEmpty().lowercase()
            protocol != "freedom" && protocol != "blackhole" && protocol != "dns"
        } ?: throw IllegalArgumentException("profile has no proxy outbound")
        proxyOutbound.addProperty("tag", PROXY_TAG)
        outbounds.add(proxyOutbound)

        // Keep any extra outbounds from the profile (e.g. chained configs) but re-tag.
        all.filter { it !== proxyOutbound }.forEachIndexed { index, extra ->
            if (!extra.has("tag")) extra.addProperty("tag", "proxy-$index")
            outbounds.add(extra)
        }

        val direct = JsonObject()
        direct.addProperty("protocol", "freedom")
        direct.addProperty("tag", DIRECT_TAG)
        outbounds.add(direct)

        val block = JsonObject()
        block.addProperty("protocol", "blackhole")
        block.addProperty("tag", BLOCK_TAG)
        outbounds.add(block)

        val rules = JsonArray()
        rules.add(ipRule(listOf("geoip:private"), DIRECT_TAG))

        val routing = JsonObject()
        routing.addProperty("domainStrategy", "AsIs")
        routing.add("rules", rules)

        val inbound = JsonObject()
        inbound.addProperty("listen", "127.0.0.1")
        inbound.addProperty("port", socksPort)
        inbound.addProperty("protocol", "socks")
        inbound.addProperty("tag", INBOUND_TAG)
        val settings = JsonObject()
        settings.addProperty("auth", "noauth")
        settings.addProperty("udp", true)
        settings.addProperty("userLevel", 0)
        inbound.add("settings", settings)

        val inbounds = JsonArray()
        inbounds.add(inbound)

        val log = JsonObject()
        log.addProperty("loglevel", "warning")

        val root = JsonObject()
        root.add("log", log)
        root.add("inbounds", inbounds)
        root.add("outbounds", outbounds)
        root.add("routing", routing)

        return root.toString()
    }

    private fun ipRule(ips: List<String>, outboundTag: String): JsonObject {
        val rule = JsonObject()
        val ipArray = JsonArray()
        ips.forEach(ipArray::add)
        rule.add("ip", ipArray)
        rule.addProperty("outboundTag", outboundTag)
        rule.addProperty("type", "field")
        return rule
    }

    /** Tag of the outbound that carries traffic, used by pingBatch. */
    const val OUTBOUND_TAG = PROXY_TAG
}

/**
 * Owns the Xray process lifecycle and wires it to Telegram's proxy settings.
 */
object XrayController {

    const val DEFAULT_SOCKS_PORT = 10808

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    @Volatile private var socksPort: Int = 0
    @Volatile private var activeProfileId: String = ""

    private val lock = Object()

    @Volatile private var cachedVersion: String? = null

    fun isRunning(): Boolean = running.get()

    fun getSocksPort(): Int = socksPort

    fun getActiveProfileId(): String = activeProfileId

    /**
     * Starts Xray for [profile] and applies the local SOCKS5 as Telegram proxy.
     * Runs the blocking native calls on a background thread; [callback] runs on UI.
     */
    fun start(profile: XrayProfile, callback: ((Boolean, String) -> Unit)? = null) {
        if (!busy.compareAndSet(false, true)) {
            callback?.let { mainHandler.post { it(false, "busy") } }
            return
        }
        Utilities.globalQueue.postRunnable {
            var error: String? = null
            try {
                synchronized(lock) {
                    if (running.get()) stopInternal()

                    if (!LibXrayBridge.isAvailable()) {
                        throw IllegalStateException("libXray is not available for this ABI")
                    }

                    val port = try {
                        LibXrayBridge.getFreePort(listOf(DEFAULT_SOCKS_PORT))
                    } catch (e: Throwable) {
                        DEFAULT_SOCKS_PORT
                    }

                    val config = XrayConfigBuilder.build(profile.xrayJson, port)
                    LibXrayBridge.testXray(config)
                    LibXrayBridge.runXray(config)

                    if (!LibXrayBridge.isRunning()) {
                        throw IllegalStateException("Xray did not start")
                    }

                    socksPort = port
                    activeProfileId = profile.id
                    running.set(true)
                    mainHandler.post { XrayProxyAdapter.apply(port) }
                }
            } catch (e: Throwable) {
                FileLog.e("Xray: failed to start", e)
                error = e.message ?: "unknown error"
                try {
                    LibXrayBridge.stopXray()
                } catch (ignored: Throwable) {
                }
                running.set(false)
                mainHandler.post { XrayProxyAdapter.clear() }
            } finally {
                busy.set(false)
                callback?.let { cb ->
                    mainHandler.post { cb(error == null, error ?: "") }
                }
            }
        }
    }

    fun stop(callback: ((Boolean) -> Unit)? = null) {
        if (!busy.compareAndSet(false, true)) {
            callback?.let { mainHandler.post { it(false) } }
            return
        }
        Utilities.globalQueue.postRunnable {
            try {
                synchronized(lock) { stopInternal() }
            } catch (e: Throwable) {
                FileLog.e("Xray: failed to stop", e)
            } finally {
                busy.set(false)
                callback?.let { cb -> mainHandler.post { cb(true) } }
            }
        }
    }

    private fun stopInternal() {
        try {
            LibXrayBridge.stopXray()
        } catch (e: Throwable) {
            FileLog.e("Xray: stop error", e)
        }
        running.set(false)
        socksPort = 0
        activeProfileId = ""
        mainHandler.post { XrayProxyAdapter.clear() }
    }

    /**
     * Called on app start: if a profile is selected and auto-start is enabled,
     * bring the tunnel back up.
     */
    fun autoStartIfNeeded() {
        val selectedId = XrayStorage.getSelectedProfileId()
        if (selectedId.isEmpty()) {
            // A previous run may have left a localhost proxy pointing at a dead port.
            Utilities.globalQueue.postRunnable { XrayProxyAdapter.clearIfStaleLocal() }
            return
        }
        val profile = XrayStorage.findProfile(selectedId) ?: return
        if (!profile.isValid) return
        start(profile)
    }

    /**
     * Ping a profile by building a temporary config on a random port.
     * [callback] receives the delay in ms or -1 on failure.
     */
    fun ping(profile: XrayProfile, callback: (Long) -> Unit) {
        Utilities.globalQueue.postRunnable {
            var delay = -1L
            try {
                val port = LibXrayBridge.getFreePort()
                val config = XrayConfigBuilder.build(profile.xrayJson, port)
                val results = LibXrayBridge.pingBatch(
                    listOf(config to XrayConfigBuilder.OUTBOUND_TAG)
                )
                val result = results.firstOrNull()
                if (result != null && result.success) delay = result.delay
            } catch (e: Throwable) {
                FileLog.e("Xray: ping failed", e)
            }
            mainHandler.post { callback(delay) }
        }
    }

    fun version(): String = cachedVersion ?: ""

    /** Load the native version string off the UI thread. */
    fun preloadVersion() {
        if (cachedVersion != null) return
        Utilities.globalQueue.postRunnable {
            try {
                if (LibXrayBridge.isAvailable()) {
                    cachedVersion = LibXrayBridge.version()
                }
            } catch (e: Throwable) {
                FileLog.e("Xray: version query failed", e)
            }
        }
    }
}
