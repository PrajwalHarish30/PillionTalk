package com.prajwal.pilliontalk.wifidirect

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Which seat you're in. The rider's phone hosts the Wi-Fi Direct group; the pillion's phone joins it. */
enum class Role { RIDER, PILLION }

/**
 * The Wi-Fi Direct link between the two phones.
 *
 * Wi-Fi Direct in one paragraph: one phone becomes the "group owner", which behaves like a
 * small Wi-Fi router (it has a network name and password, and hands out IP addresses).
 * The other phone joins it like any Wi-Fi network. No router, no internet involved.
 * The group owner always gets the IP address 192.168.49.1.
 *
 * Android's Wi-Fi Direct API is callback-based (like old-style Dart code with .then()).
 * Every function here wraps those callbacks into `suspend` functions, so callers can write
 * straight-line code, just like `await` in Dart.
 */
@SuppressLint("MissingPermission") // The UI asks for the permission before calling any of this.
class WifiDirectLink(context: Context) {

    private val manager: WifiP2pManager? =
        context.applicationContext.getSystemService(WifiP2pManager::class.java)

    // A Channel is our connection to the phone's Wi-Fi Direct system service.
    // Callbacks are delivered on the main thread (Looper.getMainLooper()).
    private val channel: WifiP2pManager.Channel? =
        manager?.initialize(context.applicationContext, Looper.getMainLooper(), null)

    /** Rider: create the group, preferring 5 GHz, and return its details once it's up. */
    suspend fun host(): LinkInfo {
        val (manager, channel) = requireP2p()
        removeExistingGroup(manager, channel)

        // Ask for 5 GHz. If this phone can't do it, fall back to letting Android pick the band.
        var fallbackReason: String? = null
        try {
            awaitAction("createGroup (5 GHz)") { manager.createGroup(channel, hostConfig(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ), it) }
        } catch (e: WifiDirectException) {
            // Common causes: the Wi-Fi chip has no 5 GHz Wi-Fi Direct support, or the phone is
            // connected to a 2.4 GHz router. Most phones have one Wi-Fi radio, so the group must
            // share the router's channel. (On the bike there's no router, so this goes away.)
            fallbackReason = "${e.message}. Is the phone connected to a 2.4 GHz Wi-Fi network?"
            awaitAction("createGroup (auto band)") { manager.createGroup(channel, hostConfig(WifiP2pConfig.GROUP_OWNER_BAND_AUTO), it) }
        }

        // "Success" only means the request was accepted. The group takes a moment to come up,
        // so poll for its details for up to ~5 seconds.
        repeat(20) {
            val group = requestGroup(manager, channel)
            if (group != null && group.frequency > 0) {
                return group.toLinkInfo(Role.RIDER, requestConnectionInfo(manager, channel), fallbackReason)
            }
            delay(250)
        }
        throw IllegalStateException("The ride network was created but never came up. Try again.")
    }

    /** Pillion: join the rider's group by its network name and password. */
    suspend fun join(): LinkInfo {
        val (manager, channel) = requireP2p()
        removeExistingGroup(manager, channel)

        // With a network name and password, Android scans for that network and joins it
        // directly: no "accept connection?" popup on the rider's phone.
        val config = WifiP2pConfig.Builder()
            .setNetworkName(NETWORK_NAME)
            .setPassphrase(PASSPHRASE)
            .build()
        awaitAction("connect") { manager.connect(channel, config, it) }

        // Scanning and joining take a few seconds. Wait up to ~30 seconds.
        repeat(60) {
            val info = requestConnectionInfo(manager, channel)
            if (info.groupFormed && !info.isGroupOwner) {
                val group = requestGroup(manager, channel)
                if (group != null) return group.toLinkInfo(Role.PILLION, info, fallbackReason = null)
            }
            delay(500)
        }
        manager.cancelConnect(channel, null)
        throw IllegalStateException("Couldn't find your rider's phone. Is it hosting a ride nearby?")
    }

    /**
     * Who is on the other end right now, or null if the group is gone.
     * Rider: the phones that joined. Pillion: the rider's phone.
     */
    suspend fun peers(role: Role): List<String>? {
        val (manager, channel) = requireP2p()
        val group = requestGroup(manager, channel) ?: return null
        val devices = if (role == Role.RIDER) group.clientList.toList() else listOfNotNull(group.owner)
        return devices.map { it.deviceName.ifBlank { it.deviceAddress } }
    }

    /** Leave or tear down the group. Safe to call even if there's nothing to leave. */
    suspend fun leave() {
        val (manager, channel) = requireP2p()
        manager.cancelConnect(channel, null) // in case a join is still in progress
        removeExistingGroup(manager, channel)
    }

    /** Fire-and-forget version of [leave], for when we can't wait (e.g. the app is closing). */
    fun release() {
        val manager = manager ?: return
        val channel = channel ?: return
        manager.cancelConnect(channel, null)
        manager.removeGroup(channel, null)
    }

    // --- Helpers -------------------------------------------------------------------------------

    private fun requireP2p(): Pair<WifiP2pManager, WifiP2pManager.Channel> {
        val manager = manager ?: throw WifiDirectException("Wi-Fi Direct", WifiP2pManager.P2P_UNSUPPORTED)
        val channel = channel ?: throw WifiDirectException("Wi-Fi Direct", WifiP2pManager.P2P_UNSUPPORTED)
        return manager to channel
    }

    /** A group left over from an earlier run makes createGroup/connect fail with BUSY. */
    private suspend fun removeExistingGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel) {
        if (requestGroup(manager, channel) != null) {
            awaitAction("removeGroup") { manager.removeGroup(channel, it) }
        }
    }

    private fun hostConfig(band: Int): WifiP2pConfig =
        WifiP2pConfig.Builder()
            .setNetworkName(NETWORK_NAME)
            .setPassphrase(PASSPHRASE)
            .setGroupOperatingBand(band)
            .enablePersistentMode(false) // don't let Android remember this group between runs
            .build()

    private fun WifiP2pGroup.toLinkInfo(role: Role, info: WifiP2pInfo, fallbackReason: String?) = LinkInfo(
        role = role,
        networkName = networkName,
        frequencyMhz = frequency,
        hostIp = info.groupOwnerAddress?.hostAddress,
        // The rider's phone *is* the group owner, so its address is the owner address.
        myIp = ipv4Of(`interface`) ?: if (role == Role.RIDER) info.groupOwnerAddress?.hostAddress else null,
        interfaceName = `interface`,
        fallbackReason = fallbackReason,
    )

    /** This phone's IPv4 address on the Wi-Fi Direct interface (e.g. "p2p-wlan0-0"). */
    private fun ipv4Of(interfaceName: String?): String? = runCatching {
        NetworkInterface.getByName(interfaceName ?: return null)
            ?.inetAddresses?.toList()
            ?.firstOrNull { it is Inet4Address }
            ?.hostAddress
    }.getOrNull()

    // --- Callback → suspend adapters -------------------------------------------------------------

    /**
     * Runs a Wi-Fi Direct action and suspends until its ActionListener fires.
     * suspendCancellableCoroutine is the Kotlin equivalent of Dart's Completer:
     * we get `cont`, and complete it from inside the callback.
     */
    private suspend fun awaitAction(step: String, action: (WifiP2pManager.ActionListener) -> Unit) =
        suspendCancellableCoroutine { cont ->
            action(object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onFailure(reason: Int) {
                    if (cont.isActive) cont.resumeWithException(WifiDirectException(step, reason))
                }
            })
        }

    private suspend fun requestGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pGroup? =
        suspendCancellableCoroutine { cont ->
            manager.requestGroupInfo(channel) { group -> if (cont.isActive) cont.resume(group) }
        }

    private suspend fun requestConnectionInfo(manager: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pInfo =
        suspendCancellableCoroutine { cont ->
            manager.requestConnectionInfo(channel) { info -> if (cont.isActive) cont.resume(info) }
        }

    companion object {
        // Android requires Wi-Fi Direct network names to start with "DIRECT-" plus two characters.
        const val NETWORK_NAME = "DIRECT-PT-PillionTalk"

        // Temporary fixed password for the proof of concept. Phase 3 replaces it with a
        // random one per pair of phones, shared once via QR code.
        const val PASSPHRASE = "pillion-dev-123"
    }
}

/** Everything we know about the link, for showing on screen (and later for the audio code). */
data class LinkInfo(
    val role: Role,
    val networkName: String,
    val frequencyMhz: Int,
    /** The rider's phone (group owner). Always 192.168.49.1 on Android. */
    val hostIp: String?,
    /** This phone's own address on the link. */
    val myIp: String?,
    val interfaceName: String?,
    /** Why 5 GHz wasn't used, or null if it was (or if we're the pillion and didn't choose). */
    val fallbackReason: String?,
) {
    val band: Band
        get() = when (frequencyMhz) {
            in 2400..2500 -> Band.GHZ_2_4
            in 4900..5900 -> Band.GHZ_5
            in 5925..7125 -> Band.GHZ_6
            else -> Band.UNKNOWN
        }
}

enum class Band(val label: String) {
    GHZ_2_4("2.4 GHz"),
    GHZ_5("5 GHz"),
    GHZ_6("6 GHz"),
    UNKNOWN("Unknown band"),
}

/** A Wi-Fi Direct call failed. [reason] is one of WifiP2pManager's error codes. */
class WifiDirectException(step: String, val reason: Int) :
    Exception("$step failed: ${reasonName(reason)}")

private fun reasonName(reason: Int): String = when (reason) {
    WifiP2pManager.ERROR -> "internal error"
    WifiP2pManager.P2P_UNSUPPORTED -> "this device doesn't support Wi-Fi Direct"
    WifiP2pManager.BUSY -> "Wi-Fi Direct is busy"
    WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
    else -> "unknown reason $reason"
}
