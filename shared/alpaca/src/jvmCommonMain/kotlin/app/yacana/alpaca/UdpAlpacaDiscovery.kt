// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * [AlpacaDiscovery] on Java UDP sockets, for Android and the JVM.
 *
 * It sends the discovery message twice, half of `listen` apart, because UDP can lose packets:
 * - over IPv4, to 255.255.255.255 and to the broadcast address of every interface;
 * - over IPv6, to the Alpaca multicast group on every interface;
 * - to the loopback addresses, for an Alpaca server on this same device.
 *
 * Answers from IPv6 link-local addresses are dropped: an HTTP URL cannot carry their interface.
 *
 * @param bind called on the socket before it sends. On Android it binds the socket to the
 *   equipment's WiFi network, which usually has no internet (ARCHITECTURE.md). The caller also holds
 *   a `MulticastLock` while discovering, or the WiFi chip may drop the answers.
 * @param port the discovery port; tests change it.
 */
class UdpAlpacaDiscovery(private val bind: (DatagramSocket) -> Unit = {}, private val port: Int = AlpacaDiscoveryProtocol.PORT) :
    AlpacaDiscovery {
    override suspend fun discover(listen: Duration): List<DiscoveryResponse> = withContext(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = RECEIVE_TIMEOUT_MILLIS
            bind(socket)
            val targets = targets()
            val responses = mutableSetOf<DiscoveryResponse>()
            val buffer = ByteArray(BUFFER_SIZE)
            val start = TimeSource.Monotonic.markNow()
            var sends = 0
            while (start.elapsedNow() < listen) {
                ensureActive()
                if (sends == 0 || (sends == 1 && start.elapsedNow() >= listen / 2)) {
                    send(socket, targets)
                    sends++
                }
                receive(socket, buffer)?.let(responses::add)
            }
            responses.toList()
        }
    }

    private fun send(socket: DatagramSocket, targets: List<InetAddress>) {
        val message = AlpacaDiscoveryProtocol.MESSAGE.encodeToByteArray()
        for (target in targets) {
            try {
                socket.send(DatagramPacket(message, message.size, target, port))
            } catch (_: IOException) {
                // This target is not reachable from this network, such as IPv6 on an IPv4-only WiFi.
            }
        }
    }

    private fun receive(socket: DatagramSocket, buffer: ByteArray): DiscoveryResponse? {
        val packet = DatagramPacket(buffer, buffer.size)
        val received =
            try {
                socket.receive(packet)
                true
            } catch (_: SocketTimeoutException) {
                false
            } catch (_: SocketException) {
                // Windows reports an ICMP "port unreachable" from a loopback target as an error here.
                false
            }
        val alpacaPort =
            if (received) {
                AlpacaDiscoveryProtocol.parseResponse(
                    packet.data.decodeToString(packet.offset, packet.offset + packet.length),
                )
            } else {
                null
            }
        val address = packet.address
        return when {
            alpacaPort == null -> null
            address is Inet6Address && address.isLinkLocalAddress -> null
            else -> DiscoveryResponse(address.hostAddress.substringBefore('%'), alpacaPort)
        }
    }

    private fun targets(): List<InetAddress> {
        val group = InetAddress.getByName(AlpacaDiscoveryProtocol.IPV6_GROUP).address
        val interfaces =
            try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
            } catch (_: SocketException) {
                emptyList()
            }
        return buildList {
            add(InetAddress.getByName("255.255.255.255"))
            add(InetAddress.getByName("127.0.0.1"))
            add(InetAddress.getByName("::1"))
            for (networkInterface in interfaces) {
                networkInterface.interfaceAddresses.mapNotNullTo(this) { it.broadcast }
                if (networkInterface.supportsMulticast() && networkInterface.inetAddresses.toList().any { it is Inet6Address }) {
                    add(Inet6Address.getByAddress(null, group, networkInterface))
                }
            }
        }.distinct()
    }

    private companion object {
        const val RECEIVE_TIMEOUT_MILLIS = 100
        const val BUFFER_SIZE = 1024
    }
}
