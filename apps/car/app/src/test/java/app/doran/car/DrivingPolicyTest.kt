package app.doran.car

import android.car.drivingstate.CarUxRestrictions
import app.doran.car.vehicle.DrivingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingPolicyTest {
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

    @Test fun contentAndStringLimitsHideLongTextEvenWhenParked() {
        for (flag in listOf(CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT, CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH)) {
            val state = DrivingState(known = true, driving = false, flags = flag)
            assertFalse(state.canShowTranscript)
            assertFalse(state.canSetup)
        }
        assertTrue(DrivingState(known = true, driving = false).canShowTranscript)
        assertTrue(DrivingState(known = true, driving = false).canSetup)
    }
}
