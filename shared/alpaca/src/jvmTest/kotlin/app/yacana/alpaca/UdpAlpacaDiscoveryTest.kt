// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.alpaca

import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class UdpAlpacaDiscoveryTest {
    @Test
    fun findsAServerOnThisMachine() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
            var asked = 0
            val responder =
                thread {
                    val buffer = ByteArray(64)
                    try {
                        while (true) {
                            val packet = DatagramPacket(buffer, buffer.size)
                            server.receive(packet)
                            if (packet.data.decodeToString(0, packet.length) == AlpacaDiscoveryProtocol.MESSAGE) {
                                asked++
                                val answer = """{"AlpacaPort": 4567}""".encodeToByteArray()
                                server.send(DatagramPacket(answer, answer.size, packet.socketAddress))
                            }
                        }
                    } catch (_: SocketException) {
                        // The test closed the socket.
                    }
                }

            var bound = false
            val responses = runBlocking { UdpAlpacaDiscovery(bind = { bound = true }, port = server.localPort).discover(400.milliseconds) }
            server.close()
            responder.join()

            assertTrue(bound, "the platform must get the socket to bind it")
            assertContains(responses, DiscoveryResponse("127.0.0.1", 4567))
            assertTrue(asked >= 2, "the message goes out twice in case UDP loses one, got $asked")
        }
    }
}
