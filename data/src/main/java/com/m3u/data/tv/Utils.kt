package com.m3u.data.tv

import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import kotlin.random.Random

internal object Utils {
    fun findPort(): Int = ServerSocket(0).use { it.localPort }

    fun createPin(): Int = Random.nextInt(from = 100000, until = 999999)

    fun getLocalHostAddress(): String? {
        val candidates = NetworkInterface
            .getNetworkInterfaces()
            ?.asSequence()
            .orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { networkInterface ->
                val name = networkInterface.name.orEmpty().lowercase()
                networkInterface.inetAddresses
                    .asSequence()
                    .filter { it.isLocalAddress() }
                    .map { address -> name to address.hostAddress }
            }
            .toList()
        return candidates
            .firstOrNull { (name, _) ->
                name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")
            }
            ?.second
            ?: candidates.firstOrNull()?.second
    }

    private fun InetAddress.isLocalAddress(): Boolean = runCatching {
        isSiteLocalAddress && !isLoopbackAddress && hostAddress?.contains(':') == false
    }.getOrDefault(false)
}
