package app.doran.car

import app.doran.car.domain.recoverSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecoveryTest {
    private val session = "11111111-1111-4111-8111-111111111111"
    private val finishRequest = "22222222-2222-4222-8222-222222222222"

    @Test fun pausedSessionRemainsAvailableForExplicitResume() {
        val restored = recoverSession(session, false, null)
        assertEquals(session, restored.sessionId)
        assertFalse(restored.finishWanted)
        assertFalse(restored.clearSessionKeys)
    }

    @Test fun pendingFinishRetainsSessionAndIntentForRetryAfterRestart() {
        val restored = recoverSession(session, true, finishRequest)
        assertEquals(session, restored.sessionId)
        assertTrue(restored.finishWanted)
        assertFalse(restored.clearSessionKeys)
    }

    @Test fun interruptedLegacyCleanupCannotLeaveStartBlockedByMissingSession() {
        val restored = recoverSession(null, true, finishRequest)
        assertNull(restored.sessionId)
        assertFalse(restored.finishWanted)
        assertTrue(restored.clearSessionKeys)
    }

    @Test fun orphanedFinishRequestIsRemovedEvenWhenFlagWasAlreadyCleared() {
        for (request in listOf(finishRequest, "", " ")) {
            val restored = recoverSession(null, false, request)
            assertNull(restored.sessionId)
            assertFalse(restored.finishWanted)
            assertTrue(restored.clearSessionKeys)
        }
    }

    @Test fun blankSessionIdentityIsClearedWithoutClaimingAnActiveConversation() {
        for (sessionId in listOf("", " ", "\n")) {
            val restored = recoverSession(sessionId, false, null)
            assertNull(restored.sessionId)
            assertFalse(restored.finishWanted)
            assertTrue(restored.clearSessionKeys)
        }
    }

    @Test fun freshInstallNeedsNoMetadataMutation() {
        val restored = recoverSession(null, false, null)
        assertNull(restored.sessionId)
        assertFalse(restored.finishWanted)
        assertFalse(restored.clearSessionKeys)
    }
}
