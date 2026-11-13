package app.doran.car

import android.car.drivingstate.CarUxRestrictions
import app.doran.car.domain.restrictCarText
import app.doran.car.vehicle.DrivingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingPolicyTest {
    @Test fun platformStringLimitAlsoAppliesToStatusWithoutBrokenUnicode() {
        val limited = DrivingState(
            known = true,
            driving = false,
            flags = CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH,
            maxStringLength = 3
        )
        assertEquals(3, limited.textLimit)
        assertEquals("듣고…", restrictCarText("듣고 있어요", limited.textLimit))
        assertEquals("A…", restrictCarText("A🚗B", limited.textLimit))
        assertEquals("", restrictCarText("듣고 있어요", 0))
        assertNull(DrivingState(known = true, driving = false, maxStringLength = 3).textLimit)
        assertEquals("듣고 있어요", restrictCarText("듣고 있어요", null))
    }

    @Test fun unknownAndDrivingNeverExposeSetupOrTranscript() {
        assertFalse(DrivingState().canSetup)
        assertFalse(DrivingState().canShowTranscript)
        assertFalse(DrivingState(known = true, driving = true).canSetup)
        assertFalse(DrivingState(known = true, driving = true).canShowTranscript)
    }

    @Test fun noSetupFlagIsHonoredEvenWhenDoIsNotRequired() {
        val state = DrivingState(known = true, driving = false, flags = CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP)
        assertFalse(state.canSetup)
        assertTrue(state.canShowTranscript)
    }

    @Test fun textRestrictionsHideConversationEvenWhenParked() {
        for (flag in listOf(
            CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT,
            CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH,
            CarUxRestrictions.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION,
            CarUxRestrictions.UX_RESTRICTIONS_NO_TEXT_MESSAGE
        )) {
            val state = DrivingState(known = true, driving = false, flags = flag)
            assertFalse(state.canShowTranscript)
            assertFalse(state.canSetup)
        }
        assertTrue(DrivingState(known = true, driving = false).canShowTranscript)
        assertTrue(DrivingState(known = true, driving = false).canSetup)
    }
}
