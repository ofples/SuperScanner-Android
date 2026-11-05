package tools.superscanner.nativelink

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress

internal data class NativeWifiRoute(
    val network: Network,
    val localAddress: Inet4Address,
    val prefixLength: Int,
) {
    fun contains(address: Inet4Address): Boolean {
        if (!isPrivateIpv4(address)) return false
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        val value = ipv4Int(address)
        val networkAddress = ipv4Int(localAddress) and mask
        return (value and mask) == networkAddress && address != localAddress &&
            value != networkAddress && value != (networkAddress or mask.inv())
    }

    fun broadcastAddress(): Inet4Address {
        val mask = -1 shl (32 - prefixLength)
        val value = ipv4Int(localAddress) or mask.inv()
        return InetAddress.getByAddress(
            byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(),
                (value ushr 8).toByte(), value.toByte()),
        ) as Inet4Address
    }
}

internal class NativeWifi(private val manager: ConnectivityManager) {
    constructor(context: Context) : this(
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager,
    )

    fun select(): NativeWifiRoute? {
        val active = manager.activeNetwork
        @Suppress("DEPRECATION")
        val networks = manager.allNetworks.sortedBy { if (it == active) 0 else 1 }
        for (network in networks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            val links = manager.getLinkProperties(network)?.linkAddresses.orEmpty()
            for (link in links) {
                val ip = link.address as? Inet4Address ?: continue
                if (link.prefixLength !in 8..30 || !isPrivateIpv4(ip)) continue
                return NativeWifiRoute(network, ip, link.prefixLength)
            }
        }
        return null
    }

    fun isCurrent(route: NativeWifiRoute): Boolean {
        val capabilities = manager.getNetworkCapabilities(route.network) ?: return false
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return false
        return manager.getLinkProperties(route.network)?.linkAddresses?.any {
            it.address == route.localAddress && it.prefixLength == route.prefixLength
        } == true
    }

    fun watch(route: NativeWifiRoute, onLost: () -> Unit): Closeable {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network == route.network) onLost()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network == route.network &&
                    (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))) onLost()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (network == route.network && linkProperties.linkAddresses.none {
                    it.address == route.localAddress && it.prefixLength == route.prefixLength
                }) onLost()
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        manager.registerNetworkCallback(request, callback)
        return Closeable { manager.unregisterNetworkCallback(callback) }
    }
}

internal fun numericPrivateIpv4(value: String): Inet4Address? {
    val parts = value.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { char -> char !in '0'..'9' } }) return null
    val octets = parts.map { it.toIntOrNull() ?: return null }
    if (octets.any { it !in 0..255 }) return null
    val address = InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray()) as Inet4Address
    return address.takeIf(::isPrivateIpv4)
}

internal fun isPrivateIpv4(address: Inet4Address): Boolean {
    val bytes = address.address.map { it.toInt() and 255 }
    return bytes[0] == 10 || bytes[0] == 172 && bytes[1] in 16..31 ||
        bytes[0] == 192 && bytes[1] == 168
}

private fun ipv4Int(address: Inet4Address): Int = address.address.fold(0) { acc, byte ->
    (acc shl 8) or (byte.toInt() and 255)
}
