package tools.superscanner.clipboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket

/** Tests may inject an isolated loopback implementation; production always uses connected Wi-Fi. */
internal interface BridgeNetwork {
    fun bindingAddress(): Inet4Address
    fun permits(host: String): Boolean
    fun socket(host: String): Socket
}

internal class WifiBridgeNetwork(context: Context) : BridgeNetwork {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private fun connected(): List<Triple<Network, Inet4Address, Int>> {
        val active = connectivity.activeNetwork
        return connectivity.allNetworks.asSequence()
            .sortedByDescending { it == active }
            .filter { network ->
                val caps = connectivity.getNetworkCapabilities(network)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            .flatMap { network ->
                connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().asSequence().mapNotNull { link ->
                    val address = link.address as? Inet4Address ?: return@mapNotNull null
                    if (!IPv4.isPrivate(address.hostAddress.orEmpty()) || link.prefixLength !in 1..32) return@mapNotNull null
                    Triple(network, address, link.prefixLength)
                }
            }.toList()
    }

    override fun bindingAddress(): Inet4Address = connected().firstOrNull()?.second
        ?: throw BridgeException("Connect to private Wi-Fi")

    override fun permits(host: String): Boolean = try {
        IPv4.isPrivate(host) && connected().any { (_, address, prefix) ->
            IPv4.sameSubnet(host, address.hostAddress.orEmpty(), prefix) && host != address.hostAddress
        }
    } catch (_: Exception) { false }

    override fun socket(host: String): Socket {
        val network = connected().firstOrNull { (_, address, prefix) ->
            IPv4.sameSubnet(host, address.hostAddress.orEmpty(), prefix) && host != address.hostAddress
        }?.first ?: throw BridgeException("Helper is outside connected private Wi-Fi")
        return network.socketFactory.createSocket()
    }
}

internal object LoopbackBridgeNetwork : BridgeNetwork {
    override fun bindingAddress(): Inet4Address = InetAddress.getByName("127.0.0.1") as Inet4Address
    override fun permits(host: String): Boolean = host == "127.0.0.1"
    override fun socket(host: String): Socket = Socket()
}
