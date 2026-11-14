package app.doran.car

import app.doran.car.speech.TtsResources
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsResourcesTest {
    @Test fun completionCannotQueueAStopForTheNextListener() {
        val service = PackageTtsService()
        val completed = service.client("completed")
        completed.resources.close(completed = true)

        val next = service.client("next")
        // A delayed finally/cancel cleanup must not add stop after accepted onDone cleanup.
        completed.resources.close()
        completed.resources.close(completed = true)
        service.drainStops()

        assertEquals(
            listOf("completed.attach", "completed.detach", "completed.release", "next.attach"),
            service.calls
        )
        assertEquals(0, next.stopCallbacks)
        assertEquals(0, service.pendingStops)
    }

    @Test fun cancellationRetainsOnePackageStopDespiteRepeatedCleanup() {
        val service = PackageTtsService()
        val cancelled = service.client("cancelled")
        cancelled.resources.close()
        cancelled.resources.close()
        cancelled.resources.close(completed = true)

        assertEquals(
            listOf("cancelled.attach", "cancelled.detach", "cancelled.stop", "cancelled.release"),
            service.calls
        )
        assertEquals(1, service.pendingStops)

        // The fake deliberately models the SDK's package scope, not an invented utterance ID.
        // This also shows why completed cleanup must never enqueue this same operation.
        val next = service.client("next")
        service.drainStops()
        service.drainStops()
        assertEquals(1, next.stopCallbacks)
        assertEquals(1, service.calls.count { it == "package.stop" })
    }

    @Test fun cleanupFailuresDoNotPreventLaterStagesOrCauseARetry() {
        for (failedStage in listOf("detach", "stop", "release")) {
            val calls = mutableListOf<String>()
            fun stage(name: String) {
                calls += name
                if (name == failedStage) error("Synthetic cleanup failure")
            }
            val resources = TtsResources(
                detachListener = { stage("detach") },
                stop = { stage("stop") },
                release = { stage("release") }
            )

            resources.close()
            resources.close()

            assertEquals(failedStage, listOf("detach", "stop", "release"), calls)
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

        resources.close(completed = true)
        resources.close()

        assertEquals(listOf("detach", "release"), calls)
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

        resources.close(completed = true)

        assertEquals(listOf("detach", "release"), calls)
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

            val resources = TtsResources(
                detachListener = {
                    calls += "$name.detach"
                    if (listener === this) listener = null
                },
                stop = {
                    calls += "$name.stop"
                    stops.addLast {
                        calls += "package.stop"
                        listener?.let { it.stopCallbacks++ }
                    }
                },
                release = { calls += "$name.release" }
            )
        }
    }
}
