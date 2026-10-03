package ru.ryadom.shared.call

import kotlin.test.Test
import kotlin.test.assertEquals

class CallStatusTest {
    @Test
    fun connectionProblemsComeFirst() {
        assertEquals(CallStatus.CONNECTING, CallState(connection = CallConnection.CONNECTING, peer = PeerPresence.PRESENT).status)
        assertEquals(CallStatus.RECONNECTING, CallState(connection = CallConnection.RECONNECTING, peer = PeerPresence.PRESENT).status)
        assertEquals(CallStatus.DISCONNECTED, CallState(connection = CallConnection.DISCONNECTED, peer = PeerPresence.PRESENT).status)
    }

    @Test
    fun connectedCallDependsOnPeer() {
        assertEquals(CallStatus.WAITING_FOR_PEER, CallState(connection = CallConnection.CONNECTED).status)
        assertEquals(CallStatus.ACTIVE, CallState(connection = CallConnection.CONNECTED, peer = PeerPresence.PRESENT).status)
        assertEquals(CallStatus.PEER_LEFT, CallState(connection = CallConnection.CONNECTED, peer = PeerPresence.LEFT).status)
    }
}
