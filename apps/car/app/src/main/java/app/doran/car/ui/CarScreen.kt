package app.doran.car.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.doran.car.domain.ConversationState
import app.doran.car.domain.Phase
import app.doran.car.domain.restrictCarText

private val LocalCarTextLimit = staticCompositionLocalOf<Int?> { null }

@Composable
fun CarScreen(
    state: ConversationState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onFinish: () -> Unit,
    onConnect: () -> Unit,
    onConfirm: () -> Unit,
    onLocationPermission: () -> Unit,
    onSoloChange: (Boolean) -> Unit
) {
    val parked = state.canSetup
    val showTranscript = state.canShowTranscript &&
        state.phase != Phase.CONNECTING &&
        state.paired &&
        (state.transcript.isNotBlank() || state.reply.isNotBlank())
    val active = state.phase in setOf(Phase.STARTING, Phase.LISTENING, Phase.THINKING, Phase.SPEAKING)
    val busy = state.phase in setOf(Phase.CONNECTING, Phase.STARTING, Phase.SAVING)
    val setupTitle = when {
        !state.uxKnown -> "차량 상태를 확인하고 있어요"
        state.driving -> "주차 후 휴대폰을\n연결해 주세요"
        else -> "지금은 차량 연결을\n사용할 수 없어요"
    }
    val setupHelp = if (state.driving) "연결과 설정은 주차 후 사용할 수 있어요" else "차량의 사용 제한이 해제되면 연결할 수 있어요"

    val actionDock: @Composable () -> Unit = {
        if (state.pendingCount > 0 && parked) {
            CarText(
                "저장 대기 중인 말씀이 ${state.pendingCount}개 있어요",
                color = Accent,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                when {
                    !state.paired && parked && state.claimant != null -> PrimaryAction(
                        "이 휴대폰과 연결",
                        onConfirm,
                        !busy,
                        Modifier.weight(1f)
                    )
                    !state.paired && parked -> PrimaryAction(
                        if (state.code.isEmpty()) "연결 코드 받기" else "새 연결 코드 받기",
                        onConnect,
                        !busy,
                        Modifier.weight(1f)
                    )
                    !state.paired -> Unit
                    active -> PrimaryAction("잠시 멈춤", onPause, true, Modifier.weight(1f))
                    else -> PrimaryAction(
                        if (state.sessionId == null) "대화 시작" else "이어서 말하기",
                        onStart,
                        state.uxKnown && !busy,
                        Modifier.weight(1f)
                    )
                }
                if (state.paired && state.sessionId != null) {
                    OutlinedButton(
                        onClick = onFinish,
                        enabled = state.phase !in setOf(Phase.CONNECTING, Phase.SAVING),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 80.dp)
                    ) {
                        CarText("여기까지 기록", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (parked && state.vehicleConsent) {
                TextButton(onClick = onLocationPermission, modifier = Modifier.heightIn(min = 56.dp)) {
                    CarText("위치 정보 권한 확인", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (parked && !state.paired) {
                CarText(
                    "음성 원본은 저장하지 않아요\n대화와 기록은 내 계정에 보관해요",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Quiet
                )
            }
        }
    }
    CompositionLocalProvider(LocalCarTextLimit provides state.textLimit) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compact = maxWidth < 700.dp
                val short = maxHeight < 520.dp
                val edge = if (compact) 24.dp else 40.dp
                Column(
                    Modifier.fillMaxSize().padding(horizontal = edge, vertical = if (short) 16.dp else 24.dp),
                    verticalArrangement = Arrangement.spacedBy(if (short) 16.dp else 24.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ConversationMark(Modifier.size(32.dp), state.phase)
                        Spacer(Modifier.width(12.dp))
                        CarText("도란", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(16.dp))
                        CarText(
                            when {
                                !state.uxKnown -> "차량 상태 확인 중"
                                state.driving -> "주행 중"
                                state.paired -> "휴대폰 연결됨"
                                else -> "휴대폰 연결"
                            },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.End,
                            color = Quiet
                        )
                    }
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(40.dp)
                    ) {
                        val scrollable = if (state.canShowTranscript) Modifier.verticalScroll(rememberScrollState()) else Modifier
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Column(
                                Modifier.weight(1f, fill = false).widthIn(max = 720.dp).fillMaxWidth().then(scrollable)
                            ) {
                                if (!short) {
                                    ConversationMark(Modifier.size(if (compact) 56.dp else 72.dp), state.phase)
                                    Spacer(Modifier.height(24.dp))
                                }
                                CarText(
                                    if (!parked && !state.paired) setupTitle else state.title,
                                    style = if (compact ||
                                        short
                                    ) {
                                        MaterialTheme.typography.headlineMedium
                                    } else {
                                        MaterialTheme.typography.displaySmall
                                    }
                                )
                                Spacer(Modifier.height(16.dp))
                                CarText(
                                    if (!parked && !state.paired) setupHelp else state.detail,
                                    color = Quiet,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (parked && state.code.isNotEmpty()) {
                                    Spacer(Modifier.height(24.dp))
                                    Surface(color = Raised, shape = RoundedCornerShape(16.dp)) {
                                        CarText(
                                            state.code.chunked(3).joinToString("  "),
                                            color = Paper,
                                            fontSize = if (compact) 40.sp else 56.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp).semantics {
                                                contentDescription = "차량 연결 코드 ${state.code.toList().joinToString(" ")}"
                                            }
                                        )
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    CarText("발급 후 5분 안에 모바일에서 입력해 주세요", color = Quiet, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (parked && state.claimant != null) {
                                    Spacer(Modifier.height(24.dp))
                                    CarText(state.claimant, color = Accent, style = MaterialTheme.typography.titleLarge)
                                }
                                if (compact && showTranscript) {
                                    Spacer(Modifier.height(24.dp))
                                    Transcript(state, compact = true)
                                }
                                if (parked && state.paired && !active && !busy) {
                                    Spacer(Modifier.height(24.dp))
                                    SoloSetting(state, onSoloChange)
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                            actionDock()
                        }
                        if (!compact && showTranscript) {
                            Surface(
                                Modifier.weight(0.85f).fillMaxHeight(),
                                color = Raised,
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Column(Modifier.padding(28.dp).verticalScroll(rememberScrollState())) {
                                    Transcript(state, compact = false)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SoloSetting(state: ConversationState, onSoloChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = state.soloAsserted,
            onCheckedChange = onSoloChange,
            enabled = !state.passengerDetected,
            modifier = Modifier.size(64.dp)
        )
        Column {
            CarText("지금 혼자 대화해요", style = MaterialTheme.typography.bodyMedium)
            CarText(
                if (state.passengerDetected) "동승자가 감지되어 과거 이야기를 먼저 꺼내지 않아요" else "확인하면 지난 이야기를 먼저 꺼낼 수 있어요",
                color = Quiet,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun Transcript(state: ConversationState, compact: Boolean) {
    if (state.transcript.isNotBlank()) {
        CarText("내가 한 말", color = Accent, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        CarText(state.transcript, style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
    }
    if (state.reply.isNotBlank()) {
        if (state.transcript.isNotBlank()) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(24.dp))
        }
        CarText("도란의 답", color = Accent, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        CarText(
            state.reply,
            color = Paper,
            style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp), modifier = modifier.heightIn(min = 80.dp)) {
        CarText(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** A spoken phrase becomes written lines; the mark is static, not a microphone level. */
@Composable
private fun ConversationMark(modifier: Modifier, phase: Phase) {
    Canvas(modifier) {
        val color = if (phase in setOf(Phase.LISTENING, Phase.SPEAKING, Phase.SAVED)) Accent else Quiet
        val w = size.width
        val stroke = w / 16f
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.08f, w * 0.08f),
            size = Size(w * 0.5f, w * 0.35f),
            cornerRadius = CornerRadius(w * 0.1f),
            style = Stroke(width = stroke)
        )
        drawLine(color, Offset(w * 0.2f, w * 0.43f), Offset(w * 0.16f, w * 0.55f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.43f, w * 0.6f), Offset(w * 0.92f, w * 0.6f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.43f, w * 0.76f), Offset(w * 0.92f, w * 0.76f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.43f, w * 0.92f), Offset(w * 0.76f, w * 0.92f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun CarText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    style: TextStyle = LocalTextStyle.current
) {
    Text(
        text = restrictCarText(text, LocalCarTextLimit.current),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        style = style
    )
}
