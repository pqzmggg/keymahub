package dev.keymahub.poc

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object Net {
    /** Non-loopback IPv4 addresses of this device, for display. */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { "${it.hostAddress}" }
    }.getOrDefault(emptyList())

    /**
     * Design §6.4: accept only loopback, or a private/link-local peer inside the subnet of
     * one of our interfaces.
     */
    fun isLocalPeer(peer: InetAddress): Boolean {
        if (peer.isLoopbackAddress) return true
        if (!(peer.isSiteLocalAddress || peer.isLinkLocalAddress || isUniqueLocalV6(peer))) return false
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList().any { nif ->
                nif.interfaceAddresses.any { ia -> sameSubnet(ia.address, peer, ia.networkPrefixLength.toInt()) }
            }
        }.getOrDefault(false)
    }

    private fun isUniqueLocalV6(a: InetAddress) = a.address.size == 16 && (a.address[0].toInt() and 0xFE) == 0xFC

    private fun sameSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
        val x = a.address
        val y = b.address
        if (x.size != y.size || prefix <= 0) return false
        var bits = prefix
        for (i in x.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }
}
