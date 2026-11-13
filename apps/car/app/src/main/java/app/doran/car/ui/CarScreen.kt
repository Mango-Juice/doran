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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
    val showTranscript = state.canShowTranscript && state.phase != Phase.CONNECTING
    val setupTitle = when {
        !state.uxKnown -> "차량 상태를 확인하고 있어요"
        state.driving -> "정차 후 휴대폰을\n연결해 주세요"
        else -> "지금은 차량 연결을\n사용할 수 없어요"
    }
    val setupHelp = if (state.driving) "연결과 설정은 주차 후 사용할 수 있어요" else "차량의 사용 제한이 해제되면 연결할 수 있어요"
    val active = state.phase in setOf(Phase.STARTING, Phase.LISTENING, Phase.THINKING, Phase.SPEAKING)
    val busy = state.phase in setOf(Phase.CONNECTING, Phase.STARTING, Phase.SAVING)
    CompositionLocalProvider(LocalCarTextLimit provides state.textLimit) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
                val compact = maxWidth < 800.dp
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ConversationMark(Modifier.size(36.dp), state.phase)
                            CarText("도란", style = MaterialTheme.typography.titleLarge)
                        }
                        CarText(
                            when {
                                !state.uxKnown -> "차량 상태 확인 중"
                                state.driving -> "음성으로 함께해요"
                                state.paired -> "휴대폰과 연결됨"
                                else -> "차량 연결"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Quiet
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center
                        ) {
                            CarText(
                                if (state.paired) "말로 남기는 오늘" else "차 안의 이야기, 휴대폰의 기록",
                                color = Warm,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(24.dp))
                            CarText(
                                if (!parked && !state.paired) setupTitle else state.title,
                                style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall
                            )
                            Spacer(Modifier.height(16.dp))
                            CarText(
                                if (!parked && !state.paired) setupHelp else state.detail,
                                color = Quiet,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (parked && state.code.isNotEmpty()) {
                                Spacer(Modifier.height(32.dp))
                                CarText(
                                    state.code.chunked(3).joinToString("  "),
                                    color = Paper,
                                    fontSize = if (compact) 42.sp else 64.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.semantics {
                                        contentDescription = "차량 연결 코드 ${state.code.toList().joinToString(" ")}"
                                    }
                                )
                                Spacer(Modifier.height(12.dp))
                                CarText("발급 후 5분 동안 유효한 일회용 코드", color = Quiet, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (parked && state.claimant != null) {
                                Spacer(Modifier.height(24.dp))
                                CarText(state.claimant, color = Warm, style = MaterialTheme.typography.titleLarge)
                            }
                            if (showTranscript && compact && state.transcript.isNotBlank()) {
                                Spacer(Modifier.height(24.dp))
                                CarText(state.transcript.take(180), color = Quiet, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (!compact && showTranscript && state.paired) {
                            Surface(
                                Modifier.widthIn(max = 440.dp).weight(0.8f).fillMaxHeight(),
                                shape = RoundedCornerShape(20.dp),
                                color = Raised
                            ) {
                                Column(
                                    Modifier.padding(28.dp).verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(24.dp)
                                ) {
                                    CarText("지금 나누는 이야기", style = MaterialTheme.typography.bodyMedium, color = Quiet)
                                    if (state.transcript.isBlank() && state.reply.isBlank()) {
                                        Spacer(Modifier.weight(1f))
                                        CarText("오늘 있었던 일도,\n문득 떠오른 생각도", style = MaterialTheme.typography.titleLarge)
                                        CarText("대화를 마치면 하나의 기록으로 남아요", color = Quiet, style = MaterialTheme.typography.bodyMedium)
                                        Spacer(Modifier.weight(1f))
                                    } else {
                                        if (state.transcript.isNotBlank()) {
                                            CarText("나", color = Warm, style = MaterialTheme.typography.bodyMedium)
                                            CarText(state.transcript, style = MaterialTheme.typography.bodyLarge)
                                        }
                                        if (state.reply.isNotBlank()) {
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                            CarText("도란", color = Warm, style = MaterialTheme.typography.bodyMedium)
                                            CarText(state.reply, color = Quiet, style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (state.pendingCount > 0 &&
                        parked
                    ) {
                        CarText("아직 서버에 저장되지 않은 말씀이 ${state.pendingCount}개 있어요", color = Warm, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (parked && state.paired && !active && !busy) {
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
                                    if (state.passengerDetected) "동승자가 감지되어 과거 이야기를 먼저 꺼내지 않아요" else "직접 확인한 경우에만 과거 이야기를 먼저 건넬 수 있어요",
                                    color = Quiet,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    if (parked && state.vehicleConsent) {
                        TextButton(onClick = onLocationPermission, modifier = Modifier.heightIn(min = 56.dp)) {
                            CarText("위치 정보 권한 확인", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
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
                                if (state.sessionId ==
                                    null
                                ) {
                                    "대화 시작"
                                } else {
                                    "이어서 말하기"
                                },
                                onStart,
                                state.uxKnown && !busy,
                                Modifier.weight(1f)
                            )
                        }
                        if (state.paired && state.sessionId != null) {
                            OutlinedButton(
                                onClick = onFinish,
                                enabled = state.phase !in setOf(Phase.CONNECTING, Phase.SAVING),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).heightIn(min = 80.dp)
                            ) {
                                CarText("여기까지 기록", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    if (parked &&
                        !state.paired
                    ) {
                        CarText("음성 원본을 저장하지 않아요 · 대화 텍스트와 기록은 내 계정에 보관해요", style = MaterialTheme.typography.bodyMedium, color = Quiet)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp), modifier = modifier.heightIn(min = 80.dp)) {
        CarText(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ConversationMark(modifier: Modifier, phase: Phase) {
    Canvas(modifier.semantics { contentDescription = "도란 대화 상태" }) {
        val color = if (phase in setOf(Phase.LISTENING, Phase.SPEAKING)) Warm else Quiet
        val heights = listOf(0.35f, 0.72f, 1f, 0.6f)
        heights.forEachIndexed { index, h ->
            val x = size.width * (index + 0.5f) / 4
            drawLine(
                color,
                Offset(x, size.height * (1 - h) / 2),
                Offset(x, size.height * (1 + h) / 2),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun CarText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    style: TextStyle = LocalTextStyle.current
) {
    Text(
        text = restrictCarText(text, LocalCarTextLimit.current),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        style = style
    )
}
