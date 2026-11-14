package app.doran.car

import app.doran.car.speech.SpeechFailure
import app.doran.car.speech.TtsResources
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsResourcesTest {
    @Test fun completionCannotQueueAStopForTheNextListener() {
        val service = PackageTtsService()
        val completed = service.client("completed")
        completed.resources.markSpeakDispatched()
        completed.resources.close(completed = true)

        val next = service.client("next")
        completed.resources.close()
        completed.resources.close(completed = true)
        service.drainStops()

        assertEquals(listOf("completed.attach", "completed.detach", "completed.release", "next.attach"), service.calls)
        assertEquals(0, next.stopCallbacks)
        assertEquals(0, service.pendingStops)
    }

    @Test fun cancellationKeepsTheOldListenerUntilStopAcknowledgementBeforeTheNextRegistration() = runBlocking {
        val service = PackageTtsService()
        val cancelled = service.client("cancelled")
        cancelled.resources.markSpeakDispatched()
        cancelled.resources.close()
        cancelled.resources.close()
        cancelled.resources.close(completed = true)
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            cancelled.resources.awaitReleased()
            service.client("next")
        }

        assertFalse(next.isCompleted)
        assertEquals(listOf("cancelled.attach", "cancelled.stop"), service.calls)
        assertEquals(1, service.pendingStops)
        service.drainStops()
        val successor = next.await()

        assertEquals(
            listOf("cancelled.attach", "cancelled.stop", "package.stop", "cancelled.detach", "cancelled.release", "next.attach"),
            service.calls
        )
        assertEquals(1, cancelled.stopCallbacks)
        assertEquals(0, successor.stopCallbacks)
        assertEquals(0, service.pendingStops)
    }

    @Test fun missingStopAcknowledgementFailsClosedAndCannotRegisterASuccessor() = runBlocking {
        val service = PackageTtsService()
        val cancelled = service.client("cancelled")
        cancelled.resources.markSpeakDispatched()
        cancelled.resources.close()
        var failure: Throwable? = null
        val next = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                cancelled.resources.awaitReleased()
                service.client("next")
            } catch (error: SpeechFailure) {
                failure = error
            }
        }

        assertFalse(next.isCompleted)
        cancelled.resources.stopTimedOut()
        next.join()
        service.drainStops()

        assertEquals("playback_stop_timeout", (failure as SpeechFailure).kind)
        assertFalse(cancelled.resources.isSettledSuccessfully)
        assertFalse(service.calls.contains("next.attach"))
        assertEquals(1, service.calls.count { it == "cancelled.detach" })
        assertEquals(1, service.calls.count { it == "cancelled.release" })
        assertFalse(cancelled.resources.acknowledgeStop())
    }

    @Test fun cancellationBeforeSpeakDispatchCannotQueueAnUnacknowledgeableStop() {
        val service = PackageTtsService()
        val preparing = service.client("preparing")
        preparing.resources.close()
        preparing.resources.close()

        assertEquals(listOf("preparing.attach", "preparing.detach", "preparing.release"), service.calls)
        assertTrue(preparing.resources.isSettledSuccessfully)
        assertEquals(0, service.pendingStops)
    }

    @Test fun acceptedExternalStopReleasesWithoutEnqueuingAnotherPackageStop() {
        val service = PackageTtsService()
        val stopped = service.client("stopped")
        stopped.resources.markSpeakDispatched()
        assertFalse(stopped.resources.acknowledgeStop())
        stopped.resources.close(completed = true)
        stopped.resources.close()
        val next = service.client("next")
        service.drainStops()

        assertEquals(listOf("stopped.attach", "stopped.detach", "stopped.release", "next.attach"), service.calls)
        assertEquals(0, next.stopCallbacks)
    }

    @Test fun cleanupFailuresReleaseOtherResourcesButKeepTheBarrierFailed() = runBlocking {
        for (failedStage in listOf("detach", "stop", "release")) {
            val calls = mutableListOf<String>()
            fun stage(name: String) {
                calls += name
                if (name == failedStage) error("Synthetic cleanup failure")
            }
            val resources: TtsResources = TtsResources(
                detachListener = { stage("detach") },
                stop = { stage("stop") },
                release = { stage("release") }
            )
            resources.markSpeakDispatched()
            resources.close()
            resources.acknowledgeStop()
            resources.close()
            resources.stopTimedOut()

            assertEquals(failedStage, listOf("stop", "detach", "release"), calls)
            assertFalse(resources.isSettledSuccessfully)
            val failure = runCatching { resources.awaitReleased() }.exceptionOrNull()
            assertTrue(failure is SpeechFailure)
        }
    }

    @Test fun completionStillReleasesWhenDetachingFailsWithoutSchedulingStop() {
        val calls = mutableListOf<String>()
        val resources = TtsResources(
            detachListener = {
                calls += "detach"
                error("Synthetic listener removal failure")
            },
            stop = { calls += "stop" },
            release = { calls += "release" }
        )
        resources.markSpeakDispatched()
        resources.close(completed = true)
        resources.close()

        assertEquals(listOf("detach", "release"), calls)
        assertFalse(resources.isSettledSuccessfully)
    }

    @Test fun reentrantCleanupCannotEscalateCompletionIntoStop() {
        val calls = mutableListOf<String>()
        lateinit var resources: TtsResources
        resources = TtsResources(
            detachListener = {
                calls += "detach"
                resources.close()
            },
            stop = { calls += "stop" },
            release = { calls += "release" }
        )
        resources.markSpeakDispatched()
        resources.close(completed = true)

        assertEquals(listOf("detach", "release"), calls)
        assertTrue(resources.isSettledSuccessfully)
    }

    /** A deterministic model of the observed package-scoped, deferred SDK stop boundary. */
    private class PackageTtsService {
        val calls = mutableListOf<String>()
        private val stops = ArrayDeque<() -> Unit>()
        private var listener: Client? = null
        val pendingStops: Int get() = stops.size

        fun client(name: String): Client = Client(name).also {
            listener = it
            calls += "$name.attach"
        }

        fun drainStops() {
            while (stops.isNotEmpty()) stops.removeFirst().invoke()
        }

        inner class Client(private val name: String) {
            var stopCallbacks = 0
                private set

            val resources: TtsResources = TtsResources(
                detachListener = {
                    calls += "$name.detach"
                    if (listener === this) listener = null
                },
                stop = {
                    calls += "$name.stop"
                    stops.addLast {
                        calls += "package.stop"
                        listener?.let {
                            it.stopCallbacks++
                            it.resources.acknowledgeStop()
                        }
                    }
                },
                release = { calls += "$name.release" }
            )
        }
    }
}
