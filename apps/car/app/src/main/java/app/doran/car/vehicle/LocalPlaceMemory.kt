package app.doran.car.vehicle

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

data class TripBaseline(val sampleCount: Int, val medianDurationSeconds: Double, val medianArrivalMinute: Int, val latestObservedAt: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("sample_count", sampleCount)
        .put("median_duration_seconds", medianDurationSeconds)
        .put("median_arrival_minute", medianArrivalMinute)
        .put("latest_observed_at", Instant.ofEpochMilli(latestObservedAt).toString())
        .put("source", "local_confirmed_arrivals")
}

/** User labels and coarse behavioral counts remain in the encrypted, owner-scoped local store. */
class LocalPlaceMemory(serialized: String? = null) {
    data class Observation(val tripId: String, val at: Long, val durationSeconds: Int)

    data class Cluster(
        val id: String,
        val center: VehicleCoordinate,
        val label: String?,
        val visits: Int,
        val lastArrivalAt: Long,
        val lastTripId: String,
        val observations: List<Observation> = emptyList(),
        val lastConfirmedArrivalAt: Long = lastArrivalAt
    )

    private val clusters = mutableListOf<Cluster>()
    private val patternResetAt = mutableMapOf<String, Long>()

    init {
        if (serialized != null) {
            runCatching {
                val envelope = if (serialized.trimStart().startsWith("{")) JSONObject(serialized) else null
                val array = envelope?.getJSONArray("clusters") ?: JSONArray(serialized)
                val resets = envelope?.optJSONObject("pattern_reset_at")
                for (label in ALLOWED_LABELS) resets?.optLong(label, 0)?.takeIf { it > 0 }?.let { patternResetAt[label] = it }
                for (index in 0 until minOf(array.length(), MAX_CLUSTERS)) {
                    val item = array.getJSONObject(index)
                    val point = VehicleCoordinate(item.getDouble("latitude"), item.getDouble("longitude"))
                    val label = item.optString("label").takeIf { it in ALLOWED_LABELS }
                    val count = item.getInt("visits")
                    val arrival = item.getLong("last_arrival_at")
                    val observations = mutableListOf<Observation>()
                    val storedObservations = item.optJSONArray("observations") ?: JSONArray()
                    for (sample in 0 until minOf(storedObservations.length(), MAX_OBSERVATIONS)) {
                        val observed = storedObservations.getJSONObject(sample)
                        val duration = observed.getInt("duration_seconds")
                        val at = observed.getLong("at")
                        if (duration in 1..MAX_TRIP_DURATION_SECONDS && at > 0) {
                            observations += Observation(observed.getString("trip_id"), at, duration)
                        }
                    }
                    if (validPoint(point) && count in 1..100_000 && arrival > 0) {
                        clusters += Cluster(
                            item.getString("id"),
                            point,
                            label,
                            count,
                            arrival,
                            item.getString("trip_id"),
                            observations,
                            item.optLong("last_confirmed_arrival_at", arrival).coerceAtLeast(arrival)
                        )
                    }
                }
            }.onFailure {
                clusters.clear()
                patternResetAt.clear()
            }
        }
    }

    /** Call only for a genuine Navi arrival; proximity validates the destination, not building entry. */
    fun confirmArrival(
        destination: VehicleCoordinate,
        current: VehicleCoordinate,
        locationMeasuredAt: Long,
        arrivalAt: Long,
        nowMillis: Long,
        tripId: String,
        explicitLabel: String?,
        durationSeconds: Int? = null
    ): Cluster? {
        if (!validPoint(destination) || !validPoint(current) || tripId.isBlank()) return null
        if (nowMillis - locationMeasuredAt !in 0..LOCATION_MAX_AGE || nowMillis - arrivalAt !in 0..ARRIVAL_WINDOW) return null
        if (distanceMeters(destination, current) > CLUSTER_RADIUS_METERS) return null
        prune(nowMillis)
        val previous = closest(destination)
        val label = explicitLabel?.takeIf { it in ALLOWED_LABELS } ?: previous?.label
        val distinctVisit = previous == null ||
            (previous.lastTripId != tripId && arrivalAt - previous.lastArrivalAt >= MIN_REVISIT_INTERVAL)
        var observations = previous?.observations.orEmpty()
        if (previous?.label != null && label != previous.label) observations = emptyList()
        if (distinctVisit && durationSeconds != null && durationSeconds in 1..MAX_TRIP_DURATION_SECONDS) {
            observations = (observations + Observation(tripId, arrivalAt, durationSeconds))
                .filter { nowMillis - it.at in 0..BASELINE_WINDOW }
                .takeLast(MAX_OBSERVATIONS)
        }
        val result = if (previous == null) {
            Cluster(UUID.randomUUID().toString(), destination, label, 1, arrivalAt, tripId, observations)
        } else {
            previous.copy(
                label = label,
                visits = if (distinctVisit) (previous.visits + 1).coerceAtMost(100_000) else previous.visits,
                lastArrivalAt = if (distinctVisit) arrivalAt else previous.lastArrivalAt,
                lastTripId = if (distinctVisit) tripId else previous.lastTripId,
                observations = observations,
                lastConfirmedArrivalAt = arrivalAt
            )
        }
        if (previous != null) clusters.remove(previous)
        clusters.add(result)
        trim()
        return result
    }

    /** Explicit correction is permitted only for a recently confirmed arrival at this place. */
    fun correctArrival(current: VehicleCoordinate, label: String, nowMillis: Long): Boolean {
        if (!validPoint(current) || label !in ALLOWED_LABELS) return false
        prune(nowMillis)
        val previous = closest(current) ?: return false
        if (nowMillis - previous.lastConfirmedArrivalAt !in 0..CORRECTION_WINDOW) return false
        clusters[clusters.indexOf(previous)] = previous.copy(
            label = label,
            observations = if (previous.label != label) emptyList() else previous.observations
        )
        return true
    }

    /** A known label is only emitted after repeated, distinct, confirmed arrivals. */
    fun labelAt(point: VehicleCoordinate, nowMillis: Long): String? {
        if (!validPoint(point)) return null
        prune(nowMillis)
        return closest(point)?.takeIf { it.visits >= MIN_VISITS }?.label
    }

    /** Previous same-destination leg arrivals only; current trip is excluded even after persistence. */
    fun baselineAt(point: VehicleCoordinate, nowMillis: Long, excludeTripId: String? = null): TripBaseline? {
        if (!validPoint(point)) return null
        prune(nowMillis)
        val cluster = closest(point)?.takeIf { it.visits >= MIN_VISITS && it.label != null } ?: return null
        val resetAt = patternResetAt[cluster.label] ?: 0
        val samples = cluster.observations.filter {
            it.tripId != excludeTripId && it.at >= resetAt && nowMillis - it.at in 1..BASELINE_WINDOW
        }.distinctBy { it.tripId }
        if (samples.size < MIN_VISITS) return null
        val arrivalMinutes = samples.map {
            val at = Instant.ofEpochMilli(it.at).atZone(KST)
            at.hour * 60 + at.minute
        }
        return TripBaseline(
            samples.size,
            median(samples.map { it.durationSeconds.toDouble() }),
            circularMinuteMedian(arrivalMinutes),
            samples.maxOf { it.at }
        )
    }

    /** This is a new pattern segment, not a claim that the user was late before the correction. */
    fun observeScheduleCorrection(text: String, nowMillis: Long): Boolean {
        if (!isWorkScheduleCorrection(text)) return false
        patternResetAt["회사"] = nowMillis
        for (index in clusters.indices) {
            if (clusters[index].label == "회사") clusters[index] = clusters[index].copy(observations = emptyList())
        }
        return true
    }

    fun clear() {
        clusters.clear()
        patternResetAt.clear()
    }

    fun serialize(): String = JSONObject().put("version", 2).put("pattern_reset_at", JSONObject(patternResetAt.toMap())).put(
        "clusters",
        JSONArray().apply {
            clusters.forEach { item ->
                put(
                    JSONObject().put("id", item.id)
                        .put("latitude", item.center.latitude).put("longitude", item.center.longitude)
                        .put("label", item.label ?: JSONObject.NULL).put("visits", item.visits)
                        .put("last_arrival_at", item.lastArrivalAt).put("trip_id", item.lastTripId)
                        .put("last_confirmed_arrival_at", item.lastConfirmedArrivalAt)
                        .put(
                            "observations",
                            JSONArray().apply {
                                item.observations.forEach { sample ->
                                    put(
                                        JSONObject().put(
                                            "trip_id",
                                            sample.tripId
                                        ).put("at", sample.at).put("duration_seconds", sample.durationSeconds)
                                    )
                                }
                            }
                        )
                )
            }
        }
    ).toString()

    private fun closest(point: VehicleCoordinate): Cluster? = clusters
        .map { it to distanceMeters(it.center, point) }
        .filter { it.second <= CLUSTER_RADIUS_METERS }
        .minByOrNull { it.second }?.first

    private fun prune(nowMillis: Long) {
        clusters.removeAll { nowMillis - it.lastArrivalAt !in 0..RETENTION_MILLIS }
    }

    private fun trim() {
        while (clusters.size > MAX_CLUSTERS) clusters.minByOrNull { it.lastArrivalAt }?.let(clusters::remove)
    }

    companion object {
        private val ALLOWED_LABELS = setOf("회사", "집")
        private const val MAX_CLUSTERS = 100
        private const val MIN_VISITS = 3
        private const val CLUSTER_RADIUS_METERS = 200.0
        private const val LOCATION_MAX_AGE = 30_000L
        private const val ARRIVAL_WINDOW = 120_000L
        private const val CORRECTION_WINDOW = 600_000L
        private const val MIN_REVISIT_INTERVAL = 1_800_000L
        private const val RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000
        private const val BASELINE_WINDOW = 30L * 24 * 60 * 60 * 1000
        private const val MAX_OBSERVATIONS = 120
        private const val MAX_TRIP_DURATION_SECONDS = 172_800
        private val KST = ZoneId.of("Asia/Seoul")

        private fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
        }

        private fun circularMinuteMedian(minutes: List<Int>): Int {
            fun distance(a: Int, b: Int): Int = minOf(abs(a - b), 1440 - abs(a - b))
            val anchor = minutes.minBy { candidate -> minutes.sumOf { distance(candidate, it) } }
            val unwrapped = minutes.map { anchor + ((it - anchor + 2160) % 1440 - 720) }
            return ((median(unwrapped.map(Int::toDouble)).toInt() % 1440) + 1440) % 1440
        }

        fun isWorkScheduleCorrection(text: String): Boolean {
            if (text.any { it in "\"'“”‘’" }) return false
            val compact = text.trim().replace(Regex("\\s+"), "")
            val prefix = "^(?:(?:요즘|최근에|최근|이제|지금은|제|내))*(?:출근시간)(?:이|은)?"
            return Regex(prefix + "(?:바뀌었(?:어요|어|습니다)|달라졌(?:어요|어|습니다))[.!。]?$").matches(compact) ||
                Regex(prefix + "(?:바뀌어서|달라져서|바뀌었는데|달라졌는데)").containsMatchIn(compact)
        }

        // Deliberately narrow utterance patterns. A quoted claim or a hypothetical is not consent.
        fun departureLabel(text: String): String? {
            val normalized = text.trim()
            if (normalized.any { it in "\"'“”‘’" } || normalized.contains("아니")) return null
            val compact = normalized.replace(Regex("\\s+"), "")
            val prefix = "^(?:(?:지금|현재|오늘은|오늘|이제|저는|나는))*(회사|직장|집)(?:으로|로|에)?"
            val complete = Regex(prefix + "(?:가는중(?:이에요|입니다|이야)?|가고있(?:어요|습니다|어)|돌아가요|향하고있(?:어요|습니다|어)|출근하는중(?:이에요|입니다|이야)?)[.!?。]?$")
            val clause = Regex(prefix + "(?:가는중인데|가고있는데|향하고있는데|출근하는중인데)")
            val match = complete.find(compact) ?: clause.find(compact) ?: return null
            return if (match.groupValues[1] == "직장") "회사" else match.groupValues[1]
        }

        fun correctionLabel(text: String): String? {
            val match = Regex(
                "^(?:지금\\s*)?여기(?:는|가)?\\s*(?:(?:회사|직장|집)(?:이|가)?\\s*아니라\\s*)?(회사|직장|집)(?:이야|이에요|입니다|야)[.!。]?$",
                RegexOption.IGNORE_CASE
            ).matchEntire(text.trim()) ?: return null
            return if (match.groupValues[1] == "직장") "회사" else match.groupValues[1]
        }

        private fun validPoint(point: VehicleCoordinate) = point.latitude.isFinite() &&
            point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 &&
            point.longitude in -180.0..180.0

        fun distanceMeters(a: VehicleCoordinate, b: VehicleCoordinate): Double {
            val latitudeDelta = Math.toRadians(b.latitude - a.latitude)
            val longitudeDelta = Math.toRadians(b.longitude - a.longitude)
            val h = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
                sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
            return 6_371_000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
