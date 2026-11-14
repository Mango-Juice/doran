package app.doran.car.qa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.doran.car.domain.ConversationState
import app.doran.car.domain.Phase
import app.doran.car.ui.CarScreen
import app.doran.car.ui.DoranTheme

/** Debug-only visual fixture. Never constructs a ViewModel, SDK, database or network client. */
class DesignPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent.getStringExtra("screen") ?: "idle"
        val driving = intent.getBooleanExtra("driving", false)
        val noSetup = intent.getBooleanExtra("no_setup", false)
        setContent {
            var state by remember { mutableStateOf(previewState(scenario, driving, noSetup)) }
            DoranTheme {
                Column(Modifier.fillMaxSize()) {
                    Surface(color = Color.White, contentColor = Color.Black) {
                        Text("합성 화면 미리보기 / 실제 대화 아님", Modifier.fillMaxWidth().padding(8.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        CarScreen(
                            state,
                            onStart = { state = previewState("listening", driving, noSetup) },
                            onPause = { state = previewState("paused", driving, noSetup) },
                            onFinish = { state = previewState("saved", driving, noSetup) },
                            onConnect = { state = previewState("pairing", driving, noSetup) },
                            onConfirm = { state = previewState("idle", driving, noSetup) },
                            onLocationPermission = {},
                            onSoloChange = { state = state.copy(soloAsserted = it) }
                        )
                    }
                }
            }
        }
    }
}

private fun previewState(screen: String, driving: Boolean, noSetup: Boolean): ConversationState {
    val base = ConversationState(
        phase = Phase.READY,
        title = "어떤 이야기를 나눌까요?",
        detail = "생각나는 것부터 편하게 말씀해 주세요",
        paired = true,
        uxKnown = true,
        driving = driving,
        canSetup = !driving && !noSetup,
        canShowTranscript = !driving
    )
    return when (screen) {
        "pairing" -> base.copy(
            phase = Phase.UNPAIRED,
            paired = false,
            code = "246831",
            title = "휴대폰과 연결해 주세요",
            detail = "도란 모바일의 차량 연결에서 아래 코드를 입력해 주세요"
        )
        "listening" -> base.copy(phase = Phase.LISTENING, sessionId = "synthetic", title = "듣고 있어요", detail = "편하게 말씀해 주세요")
        "paused" -> base.copy(
            phase = Phase.PAUSED,
            sessionId = "synthetic",
            title = "잠시 쉬어갈까요",
            detail = "준비되면 이어서 말씀해 주세요",
            transcript = "[가상 사례] 오늘 파란 노트 프로젝트 발표를 마쳤어요. 다음에는 설명을 조금 더 짧게 해보려고요.",
            reply = "발표를 마쳤군요. 어떤 부분을 더 짧게 설명하고 싶으세요?"
        )
        "error" -> base.copy(
            phase = Phase.ERROR,
            sessionId = "synthetic",
            pendingCount = 1,
            title = "연결이 잠시 끊겼어요",
            detail = "말씀하신 내용은 기기에 보관했어요. 연결되면 이어서 말할 수 있어요"
        )
        "saved" -> base.copy(phase = Phase.SAVED, title = "오늘의 이야기를 남겼어요", detail = "휴대폰에서 기록을 확인하고 다듬을 수 있어요")
        else -> base
    }
}
