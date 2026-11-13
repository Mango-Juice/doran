package app.doran.car.domain

import java.util.UUID
import org.json.JSONObject

/** A cached code is displayable only after the server revalidates this exact scope. */
class PendingPairing private constructor(
    val deviceId: String,
    val ownerId: String?,
    val pairingId: String,
    val code: String,
    val expiresAtMillis: Long
) {
    fun matches(deviceId: String, ownerId: String?): Boolean = canonicalId(deviceId) == this.deviceId &&
        (ownerId == null || canonicalId(ownerId) != null) &&
        canonicalId(ownerId) == this.ownerId

    /** Server time owns the five-minute TTL; a local clock offset must not extend it. */
    fun isValidAt(nowMillis: Long): Boolean = nowMillis >= 0 && nowMillis < expiresAtMillis

    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", SCHEMA_VERSION)
        .put("device_id", deviceId)
        .put("owner_id", ownerId ?: JSONObject.NULL)
        .put("pairing_id", pairingId)
        .put("code", code)
        .put("expires_at_millis", expiresAtMillis)

    /** Reconciliation can shorten a ticket's life, never revive or extend a ticket. */
    fun reconcileServer(
        pairingId: String,
        status: String,
        expiresAtMillis: Long,
        nowMillis: Long,
        deviceId: String,
        ownerId: String?
    ): PendingPairing? {
        if (!matches(deviceId, ownerId) || canonicalId(pairingId) != this.pairingId) return null
        if (status !in setOf("waiting", "claimed") || !isValidAt(nowMillis) || expiresAtMillis <= nowMillis) return null
        return PendingPairing(this.deviceId, this.ownerId, this.pairingId, code, minOf(this.expiresAtMillis, expiresAtMillis))
    }

    companion object {
        private const val SCHEMA_VERSION = 1L
        private val codePattern = Regex("[0-9]{6}")

        fun create(deviceId: String, ownerId: String?, pairingId: String, code: String, expiresAtMillis: Long): PendingPairing? {
            val device = canonicalId(deviceId) ?: return null
            val owner = ownerId?.let { canonicalId(it) ?: return null }
            val pairing = canonicalId(pairingId) ?: return null
            if (!codePattern.matches(code) || expiresAtMillis <= 0) return null
            return PendingPairing(device, owner, pairing, code, expiresAtMillis)
        }

        fun fromJson(json: JSONObject): PendingPairing? {
            if (integer(json.opt("schema_version")) != SCHEMA_VERSION || !json.has("owner_id")) return null
            val owner = when (val value = json.opt("owner_id")) {
                JSONObject.NULL -> null
                is String -> value
                else -> return null
            }
            return create(
                deviceId = json.opt("device_id") as? String ?: return null,
                ownerId = owner,
                pairingId = json.opt("pairing_id") as? String ?: return null,
                code = json.opt("code") as? String ?: return null,
                expiresAtMillis = integer(json.opt("expires_at_millis")) ?: return null
            )
        }

        private fun canonicalId(value: String?): String? {
            if (value == null) return null
            val parsed = runCatching { UUID.fromString(value).toString() }.getOrNull() ?: return null
            return parsed.takeIf { it.equals(value, ignoreCase = true) }
        }

        private fun integer(value: Any?): Long? = when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }
    }
}
