package app.doran.car

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.doran.car.data.*
import app.doran.car.domain.*
import app.doran.car.speech.PleosSpeech
import app.doran.car.speech.SpeechFailure
import app.doran.car.vehicle.DrivingRestrictions
import app.doran.car.vehicle.PleosVehicleContext
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

class CarViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = mutable.asStateFlow()
    private val secure = SecureStorage(application)
    private val database = DoranDatabase.open(application)
    private val outbox = database.outbox()
    private val api = DoranApi(secure)
    private val speech = PleosSpeech(application) { pause("다른 음성 안내가 재생 중이에요") }
    private val restrictions = DrivingRestrictions(application)
    private val vehicle = PleosVehicleContext(application)
    private val gate = GenerationGate()
    private var foreground = false
    private var loop: Job? = null
    private var polling: Job? = null
    private var pendingPause: Job? = null
    private var setup: Job? = null
    private var finishing: Job? = null
    private var settings = JSONObject()
    private var finishWanted = false
    private var pairingExpires = 0L
    private var suggestedTrip: String? = null

    init {
        restrictions.start()
        viewModelScope.launch {
            var previouslyOccupied = false
            vehicle.state.collect { context ->
                val occupied = context.passengerState() == "occupied"
                if (occupied && state.value.soloAsserted) mutable.update { it.copy(soloAsserted = false) }
                if (occupied && !previouslyOccupied && isTalking()) pause("동승자가 감지되어 대화를 잠시 멈췄어요")
                previouslyOccupied = occupied
            }
        }
        viewModelScope.launch {
            restrictions.state.collect { driving ->
                mutable.update {
                    it.copy(
                        uxKnown = driving.known,
                        driving = driving.driving,
                        canSetup = driving.canSetup,
                        canShowTranscript = driving.canShowTranscript
                    )
                }
                if (!driving.known && isTalking()) pause("차량 상태를 확인한 뒤 다시 시작해 주세요")
                if (driving.canSetup && foreground && !state.value.paired && state.value.code.isEmpty()) connect()
            }
        }
    }

    fun foreground() {
        foreground = true
        if (polling?.isActive == true) return
        connect()
        polling = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                runCatching {
                    val s = state.value
                    if (s.paired) {
                        refreshSettings()
                    } else if (s.pairingId != null &&
                        restrictions.state.value.canSetup
                    ) {
                        pollPairing(s.pairingId)
                    }
                }.onFailure { /* A request failure does not silently discard local speech. */ }
            }
        }
    }

    fun background() {
        foreground = false
        polling?.cancel()
        polling = null
        setup?.cancel()
        setup = null
        if (isTalking()) pause("대화를 잠시 멈췄어요")
        speech.pause()
        vehicle.stop()
        mutable.update { it.copy(soloAsserted = false) }
    }

    private fun isTalking() = state.value.phase in setOf(Phase.STARTING, Phase.LISTENING, Phase.THINKING, Phase.SPEAKING)
    fun connect() {
        if (!foreground || setup?.isActive == true || isTalking() || state.value.phase == Phase.SAVING) return
        mutable.update { it.copy(phase = Phase.CONNECTING, title = "연결을 확인하고 있어요", detail = "잠시만 기다려 주세요") }
        setup = viewModelScope.launch {
            try {
                val result = api.request("/devices/bootstrap", JSONObject().put("label", "도란 차량"))
                val paired = result.optBoolean("paired")
                val owner = result.optJSONObject("device")?.optString("owner_id")?.takeUnless { it == "null" || it.isBlank() }
                val previousOwner = secure.get("owner_id")
                if (previousOwner != owner) {
                    clearPreviousAccount()
                    if (owner == null) secure.remove("owner_id") else secure.put("owner_id", owner)
                }
                val session = secure.get("active_session")
                finishWanted = secure.get("finish_wanted") == "true"
                val pendingCount = outboxCount()
                mutable.update { it.copy(paired = paired, sessionId = session, pendingCount = pendingCount, errorCode = null) }
                if (paired) {
                    refreshSettings()
                    mutable.update {
                        it.copy(
                            phase = if (session == null) Phase.READY else Phase.PAUSED,
                            title = if (session == null) "어떤 이야기를 나눌까요?" else "이야기를 이어갈까요?",
                            detail = if (finishWanted) "아직 저장하지 못한 대화가 있어요. 연결되면 기록을 마무리할 수 있어요" else "생각나는 것부터 편하게 말씀해 주세요",
                            code = "",
                            pairingId = null,
                            claimant = null
                        )
                    }
                } else if (restrictions.state.value.canSetup) {
                    createPairing()
                } else {
                    mutable.update { it.copy(phase = Phase.UNPAIRED, title = "정차 후 휴대폰을 연결해 주세요", detail = "차량과 휴대폰의 기록을 안전하게 이어드려요") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    private suspend fun outboxCount() = outbox.all().size
    private suspend fun createPairing() {
        val pairing = api.request("/pairings", DoranApi.mutation())
        pairingExpires = runCatching { java.time.Instant.parse(pairing.getString("expires_at")).toEpochMilli() }.getOrDefault(0)
        mutable.update {
            it.copy(
                phase = Phase.UNPAIRED,
                title = "휴대폰에서 도란을 열어 주세요",
                detail = "설정에서 차량 연결을 선택하고 아래 코드를 입력해 주세요",
                code = pairing.getString("code"),
                pairingId = pairing.getString("id"),
                claimant = null,
                errorCode = null
            )
        }
    }
    private suspend fun pollPairing(id: String) {
        if (pairingExpires > 0 && System.currentTimeMillis() >= pairingExpires) {
            mutable.update { it.copy(code = "", pairingId = null, claimant = null, title = "연결 코드가 만료됐어요", detail = "새 코드를 받아 연결해 주세요") }
            return
        }
        val result = api.request("/pairings/$id")
        when (result.optString("status")) {
            "claimed" -> mutable.update {
                it.copy(
                    claimant = result.optString("owner_label", "내 휴대폰"),
                    title = "이 휴대폰과 연결할까요?",
                    detail = "휴대폰에 표시된 연결 요청이 맞는지 확인해 주세요"
                )
            }
            "paired" -> connect()
            "expired" -> mutable.update {
                it.copy(code = "", pairingId = null, claimant = null, title = "연결 코드가 만료됐어요", detail = "새 코드를 받아 연결해 주세요")
            }
        }
    }
    fun confirmPairing() {
        val id = state.value.pairingId ?: return
        if (!restrictions.state.value.canSetup || state.value.claimant == null || state.value.phase == Phase.CONNECTING) return
        mutable.update { it.copy(phase = Phase.CONNECTING, title = "휴대폰과 연결하고 있어요") }
        viewModelScope.launch {
            try {
                api.request("/pairings/$id/confirm", DoranApi.mutation())
                connect()
            } catch (
                e: Exception
            ) {
                if (e !is CancellationException) showError(e)
            }
        }
    }
    private suspend fun refreshSettings() {
        val current = api.request("/devices/current")
        if (!current.optBoolean("paired")) {
            pause("휴대폰에서 차량 연결이 해제됐어요")
            clearPreviousAccount()
            secure.remove("owner_id")
            mutable.update {
                it.copy(
                    paired = false,
                    sessionId = null,
                    code = "",
                    pairingId = null,
                    phase = Phase.UNPAIRED,
                    title = "휴대폰에서 다시 연결해 주세요",
                    detail = "이 차량에서는 이전 계정의 기록에 접근할 수 없어요"
                )
            }
            return
        }
        settings = api.request("/settings")
        val consented = settings.optBoolean("vehicle_context_enabled")
        mutable.update { it.copy(vehicleConsent = consented) }
        vehicle.syncMemoryRevision(settings.optLong("vehicle_memory_revision", 0))
        if (foreground) vehicle.start(consented)
        if (!settings.optBoolean("consent_accepted") && isTalking()) pause("휴대폰에서 데이터 처리 안내를 확인해 주세요")
        maybeSuggestConversation()
    }

    fun start() {
        if (!foreground || !state.value.paired || !state.value.uxKnown || loop?.isActive == true) return
        if (finishWanted) {
            finish()
            return
        }
        val generation = gate.advance()
        loop = viewModelScope.launch {
            try {
                mutable.update { it.copy(phase = Phase.STARTING, title = "대화를 준비하고 있어요", detail = "잠시만 기다려 주세요", errorCode = null) }
                pendingPause?.join()
                refreshSettings()
                if (!state.value.paired || !settings.optBoolean("consent_accepted")) {
                    mutable.update {
                        it.copy(phase = Phase.PAUSED, title = "휴대폰에서 이용 안내를 확인해 주세요", detail = "텍스트 저장과 AI 처리에 동의한 뒤 대화를 시작할 수 있어요")
                    }
                    return@launch
                }
                var session = state.value.sessionId
                if (session == null) {
                    val requestId = secure.get("start_request") ?: UUID.randomUUID().toString().also { secure.put("start_request", it) }
                    val result = api.request("/sessions", JSONObject().put("request_id", requestId).put("context", currentContext()))
                    session = result.getString("id")
                    secure.put("active_session", session)
                    secure.remove("start_request")
                    mutable.update { it.copy(sessionId = session) }
                }
                for (pending in outbox.all().filter { it.sessionId == session }) {
                    sendTurn(pending, generation)
                }
                while (isActive && foreground && gate.accepts(generation)) {
                    mutable.update { it.copy(phase = Phase.LISTENING, title = "듣고 있어요", detail = "이야기를 마치면 도란이 답해요", transcript = "") }
                    val text = speech.listen { partial ->
                        if (gate.accepts(generation)) mutable.update { it.copy(transcript = partial) }
                    }
                    if (!gate.accepts(generation)) return@launch
                    if (VoiceCommands.isFinish(text)) {
                        finish()
                        return@launch
                    }
                    vehicle.observeUserUtterance(text)
                    val request = DoranApi.mutation().put("text", text).put("context", currentContext())
                    val pending =
                        PendingTurn(
                            request.getString("request_id"),
                            session,
                            secure.encrypt(request.toString()),
                            System.currentTimeMillis()
                        )
                    outbox.insert(pending)
                    mutable.update { it.copy(pendingCount = it.pendingCount + 1) }
                    sendTurn(pending, generation)
                    delay(350)
                }
            } catch (e: TimeoutCancellationException) {
                if (gate.accepts(generation)) {
                    speech.pause()
                    showError(SpeechFailure("timeout", "잠시 멈췄어요. 준비되면 다시 말씀해 주세요"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gate.accepts(generation)) {
                    speech.pause()
                    showError(e)
                }
            }
        }
    }

    private suspend fun sendTurn(turn: PendingTurn, generation: Long) = coroutineScope {
        mutable.update { it.copy(phase = Phase.THINKING, title = "이야기를 생각하고 있어요", detail = "말씀하신 내용을 바탕으로 답할게요", reply = "") }
        val sentences = Channel<String>(Channel.BUFFERED)
        val playback = launch {
            for (sentence in sentences) {
                if (!gate.accepts(generation)) break
                mutable.update { it.copy(phase = Phase.SPEAKING, title = "도란이 이야기하고 있어요", detail = "언제든 잠시 멈출 수 있어요") }
                speech.speak(sentence)
            }
        }
        try {
            api.turn(turn.sessionId, JSONObject(secure.decrypt(turn.ciphertext))) { event ->
                withContext(Dispatchers.Main.immediate) {
                    if (!gate.accepts(generation)) throw CancellationException()
                    when (event.name) {
                        "delta" -> mutable.update { it.copy(reply = it.reply + event.body.optString("text")) }
                        "sentence" -> sentences.send(event.body.getString("text"))
                        "done" -> {
                            mutable.update { it.copy(reply = event.body.optString("assistant_text", it.reply)) }
                            outbox.remove(turn.requestId)
                            mutable.update { it.copy(pendingCount = (it.pendingCount - 1).coerceAtLeast(0)) }
                        }
                    }
                }
            }
            sentences.close()
            playback.join()
        } finally {
            sentences.close()
            playback.cancel()
        }
    }

    fun pause(message: String = "대화를 잠시 멈췄어요") {
        gate.advance()
        api.cancelTurn()
        speech.pause()
        loop?.cancel()
        loop = null
        if (state.value.phase != Phase.SAVING && state.value.paired) {
            mutable.update {
                it.copy(phase = Phase.PAUSED, title = message, detail = "준비되면 이어서 말하거나 여기까지 기록할 수 있어요")
            }
        }
        val session = state.value.sessionId
        if (session != null && pendingPause?.isActive != true) {
            pendingPause = viewModelScope.launch {
                runCatching { api.request("/sessions/$session/pause", DoranApi.mutation()) }
            }
        }
    }

    fun finish() {
        val session = state.value.sessionId ?: return
        if (state.value.phase == Phase.SAVING) return
        val finishingGeneration = gate.advance()
        val identity = SessionOperation(secure.get("owner_id"), session, finishingGeneration)
        fun stillOwned() = identity.matches(secure.get("owner_id"), state.value.sessionId, gate.current())
        api.cancelTurn()
        speech.pause()
        loop?.cancel()
        loop = null
        finishWanted = true
        secure.put("finish_wanted", "true")
        mutable.update { it.copy(phase = Phase.SAVING, title = "이야기를 기록하고 있어요", detail = "휴대폰에서 오늘의 기록을 확인할 수 있어요") }
        finishing = viewModelScope.launch {
            try {
                pendingPause?.join()
                val pending = outbox.all().filter { it.sessionId == session }
                val finishId = secure.get("finish_request") ?: UUID.randomUUID().toString().also { secure.put("finish_request", it) }
                val payload = JSONObject().put("request_id", finishId).put("context", currentContext()).put(
                    "pending_turns",
                    JSONArray().apply {
                        pending.forEach { put(JSONObject(secure.decrypt(it.ciphertext))) }
                    }
                )
                api.request("/sessions/$session/finish", payload)
                if (!stillOwned()) {
                    return@launch
                }
                outbox.removeSession(session)
                if (!stillOwned()) return@launch
                secure.remove("active_session")
                secure.remove("finish_wanted")
                secure.remove("finish_request")
                secure.remove("start_request")
                finishWanted = false
                mutable.update {
                    it.copy(
                        phase = Phase.SAVED, title = "오늘의 이야기를 남겼어요", detail = "휴대폰에서 기록을 읽고 다듬어 보세요",
                        sessionId = null, pendingCount = 0, transcript = "", reply = "", soloAsserted = false, errorCode = null
                    )
                }
            } catch (e: Exception) {
                if (e !is CancellationException && stillOwned()) {
                    mutable.update {
                        it.copy(
                            phase = Phase.PAUSED,
                            title = "기록을 기기에 안전하게 보관했어요",
                            detail = "연결이 돌아오면 ‘여기까지 기록’을 눌러 저장해 주세요",
                            errorCode = (e as? ApiFailure)?.code
                        )
                    }
                }
            }
        }
    }

    private fun maybeSuggestConversation() {
        if (!foreground || state.value.phase != Phase.READY || !state.value.uxKnown || !settings.optBoolean("consent_accepted")) return
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString()
        val trip = vehicle.state.value.tripKey
        val suggest = when (settings.optString("conversation_suggestions", "off")) {
            "daily" -> (secure.get("last_suggestion_date") != today).also { if (it) secure.put("last_suggestion_date", today) }
            "trip" -> (trip != null && suggestedTrip != trip).also { if (it) suggestedTrip = trip }
            else -> false
        }
        if (suggest) mutable.update { it.copy(title = "잠깐 이야기를 나눠볼까요?", detail = "원할 때 ‘대화 시작’을 눌러 주세요") }
    }
    fun setSoloAsserted(value: Boolean) {
        if (!restrictions.state.value.canSetup || isTalking()) return
        if (value && vehicle.state.value.passengerState() == "occupied") return
        mutable.update { it.copy(soloAsserted = value) }
    }
    private fun currentContext(): JSONObject {
        val context = vehicle.state.value.toJson()
        val occupied = vehicle.state.value.passengerState() == "occupied"
        if (occupied) mutable.update { it.copy(soloAsserted = false) }
        return context.put(
            "passenger_state",
            if (!occupied &&
                state.value.soloAsserted
            ) {
                "alone"
            } else if (occupied) {
                "occupied"
            } else {
                "unknown"
            }
        )
            .put(
                "passenger_source",
                if (occupied) {
                    "pleos_seat"
                } else if (state.value.soloAsserted) {
                    "user_assertion"
                } else {
                    "unknown"
                }
            )
    }
    private suspend fun clearPreviousAccount() {
        finishing?.cancel()
        finishing = null
        gate.advance()
        loop?.cancel()
        api.cancelTurn()
        speech.pause()
        vehicle.stop()
        vehicle.clearPrivateData()
        secure.remove("active_session")
        secure.remove("finish_wanted")
        secure.remove("finish_request")
        secure.remove("start_request")
        outbox.clear()
        finishWanted = false
        suggestedTrip = null
        secure.remove("last_suggestion_date")
        mutable.update { it.copy(sessionId = null, pendingCount = 0, transcript = "", reply = "", soloAsserted = false) }
    }
    private fun showError(error: Exception) {
        val message = when (error) {
            is ApiFailure -> error.message
            is SpeechFailure -> error.message ?: "음성을 다시 확인해 주세요"
            else -> "연결을 확인한 뒤 다시 시도해 주세요"
        }
        mutable.update {
            it.copy(
                phase = if (it.paired) Phase.PAUSED else Phase.ERROR,
                title = message,
                detail = if (it.pendingCount > 0) "말씀하신 내용은 기기에 보관했어요" else "다시 시작하면 안전하게 이어갈 수 있어요",
                errorCode = (error as? ApiFailure)?.code ?: (error as? SpeechFailure)?.kind
            )
        }
    }
    fun retry() {
        if (state.value.paired) start() else connect()
    }
    fun refreshVehiclePermissions() {
        if (foreground) {
            vehicle.stop()
            vehicle.start(state.value.vehicleConsent)
        }
    }
    override fun onCleared() {
        foreground = false
        gate.advance()
        speech.pause()
        api.close()
        vehicle.stop()
        restrictions.close()
        database.close()
        super.onCleared()
    }
}
