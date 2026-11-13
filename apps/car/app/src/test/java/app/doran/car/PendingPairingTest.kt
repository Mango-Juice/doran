package app.doran.car

import app.doran.car.domain.PendingPairing
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPairingTest {
    private val device = "11111111-1111-4111-8111-111111111111"
    private val owner = "22222222-2222-4222-8222-222222222222"
    private val other = "33333333-3333-4333-8333-333333333333"
    private val pairing = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val now = 1_800_000_000_000L
    private val expiry = now + 300_000

    private fun ticket(ownerId: String? = owner): PendingPairing =
        requireNotNull(PendingPairing.create(device, ownerId, pairing, "012345", expiry))

    private fun reconcile(
        cachedTicket: PendingPairing = ticket(),
        id: String = pairing,
        status: String = "waiting",
        serverExpiry: Long = expiry,
        time: Long = now,
        deviceId: String = device,
        ownerId: String? = owner
    ) = cachedTicket.reconcileServer(id, status, serverExpiry, time, deviceId, ownerId)

    @Test fun jsonRoundTripPreservesLeadingZeroAndNullableOwnerScope() {
        for (ownerId in listOf(owner, null)) {
            val original = ticket(ownerId)
            val restored = requireNotNull(PendingPairing.fromJson(JSONObject(original.toJson().toString())))
            assertEquals(device, restored.deviceId)
            assertEquals(ownerId, restored.ownerId)
            assertEquals(pairing, restored.pairingId)
            assertEquals("012345", restored.code)
            assertEquals(expiry, restored.expiresAtMillis)
            assertTrue(restored.matches(device, ownerId))
        }
    }

    @Test fun corruptCacheDoesNotCoerceMissingOrWrongTypeFields() {
        for (field in listOf("schema_version", "device_id", "owner_id", "pairing_id", "code", "expires_at_millis")) {
            val json = ticket().toJson().apply { remove(field) }
            assertNull(field, PendingPairing.fromJson(json))
        }
        for ((field, value) in listOf(
            "schema_version" to 2,
            "schema_version" to "1",
            "owner_id" to 12,
            "owner_id" to "null",
            "device_id" to "1-1-1-1-1",
            "pairing_id" to "not-a-uuid",
            "code" to 123456,
            "code" to "12345",
            "code" to "１２３４５６",
            "expires_at_millis" to expiry.toString(),
            "expires_at_millis" to expiry.toDouble(),
            "expires_at_millis" to 0L
        )) {
            assertNull(field, PendingPairing.fromJson(ticket().toJson().put(field, value)))
        }
    }

    @Test fun expiryUsesStrictServerBoundaryWithoutAssumingSynchronizedClocks() {
        val ticket = ticket()
        assertTrue(ticket.isValidAt(expiry - 1))
        assertFalse(ticket.isValidAt(expiry))
        assertFalse(ticket.isValidAt(expiry + 1))
        assertFalse(ticket.isValidAt(-1))
        // Server-issued TTL remains authoritative when the local clock is 200ms behind.
        assertTrue(ticket.isValidAt(now - 200))
    }

    @Test fun identityRequiresExactDeviceAndNullableOwnerScope() {
        assertFalse(ticket().matches(other, owner))
        assertFalse(ticket().matches(device, other))
        assertFalse(ticket().matches(device, null))
        assertFalse(ticket(null).matches(device, owner))
        assertFalse(ticket(null).matches(device, "invalid"))
        assertTrue(ticket(null).matches(device, null))
        val uppercase = requireNotNull(PendingPairing.create(device, owner, pairing.uppercase(), "012345", expiry))
        assertEquals(pairing, uppercase.pairingId)
    }

    @Test fun revalidationOnlyAcceptsSameLiveWaitingOrClaimedChallenge() {
        assertNotNull(reconcile(status = "waiting"))
        assertNotNull(reconcile(status = "claimed"))
        for (status in listOf("paired", "expired", "unknown", "", "WAITING")) {
            assertNull(status, reconcile(status = status))
        }
        assertNull(reconcile(id = other))
        assertNull(reconcile(deviceId = other))
        assertNull(reconcile(ownerId = other))
        assertNull(reconcile(ownerId = null))
        assertNull(reconcile(cachedTicket = ticket(null), ownerId = owner))
        assertNotNull(reconcile(cachedTicket = ticket(null), ownerId = null))
    }

    @Test fun serverCanShortenExpiryButCannotExtendOrReviveCachedCode() {
        assertEquals(expiry - 1000, requireNotNull(reconcile(serverExpiry = expiry - 1000)).expiresAtMillis)
        assertEquals(expiry, requireNotNull(reconcile(serverExpiry = expiry + 1000)).expiresAtMillis)
        assertNull(reconcile(serverExpiry = now))
        assertNull(reconcile(serverExpiry = now - 1))
        assertNull(reconcile(time = expiry, serverExpiry = expiry + 1000))
    }
}
