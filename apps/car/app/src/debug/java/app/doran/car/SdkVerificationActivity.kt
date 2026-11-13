package app.doran.car

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/** Debug-only foreground host. No account, network, or microphone initialization. */
class SdkVerificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(
            TextView(this).apply {
                text = "도란 SDK 검증 중\n합성 입력과 차량 에뮬레이터 신호를 확인합니다"
                textSize = 28f
                setPadding(48, 48, 48, 48)
            }
        )
    }
}
