package app.doran.car

import app.doran.car.domain.GenerationGate
import app.doran.car.domain.SessionOperation
import app.doran.car.domain.VoiceCommands
import org.junit.Assert.*
import org.junit.Test

class ConversationPolicyTest {
    @Test fun cancellationRejectsOldResultEvenAfterANewRequestStarts() {
        val gate = GenerationGate()
        val first = gate.advance()
        gate.advance()
        val second = gate.advance()
        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(second))
    }

    @Test fun quotingEndCommandDoesNotEndCurrentConversation() {
        assertTrue(VoiceCommands.isFinish("대화 끝내줘."))
        assertFalse(VoiceCommands.isFinish("어제 친구가 대화 끝내줘라고 말했어"))
        assertFalse(VoiceCommands.isFinish("그만두고 싶은 생각이 들었어요"))
    }

    @Test fun staleFinishCannotClearAnotherOwnerOrSession() {
        val operation = SessionOperation("owner-a", "session-a", 3)
        assertTrue(operation.matches("owner-a", "session-a", 3))
        assertFalse(operation.matches("owner-b", "session-a", 3))
        assertFalse(operation.matches("owner-a", "session-b", 3))
        assertFalse(operation.matches("owner-a", "session-a", 4))
    }
}
