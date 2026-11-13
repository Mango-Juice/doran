package app.doran.car

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doran.car.ui.CarScreen
import app.doran.car.ui.DoranTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: CarViewModel
    private var firstContentDrawn = false
    private val locationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.refreshVehiclePermissions()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val startedAt = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[CarViewModel::class.java]
        Log.i("DoranStartup", "model_ready_ms=${SystemClock.elapsedRealtime() - startedAt}")
        setContent {
            val state = model.state.collectAsStateWithLifecycle(lifecycleOwner = this@MainActivity).value
            DoranTheme {
                Box(
                    Modifier.fillMaxSize().drawWithContent {
                        drawContent()
                        if (!firstContentDrawn) {
                            firstContentDrawn = true
                            Log.i("DoranStartup", "first_content_draw_ms=${SystemClock.elapsedRealtime() - startedAt}")
                            window.decorView.post { reportFullyDrawn() }
                        }
                    }
                ) {
                    CarScreen(state, model::start, {
                        model.pause()
                    }, model::finish, model::connect, model::confirmPairing, ::requestVehiclePermission, model::setSoloAsserted)
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        model.foreground()
    }
    override fun onStop() {
        model.background()
        super.onStop()
    }
    private fun requestVehiclePermission() {
        val state = model.state.value
        if (!state.canSetup || !state.vehicleConsent) return
        if (checkSelfPermission("pleos.car.permission.FUSED_LOCATION") != PackageManager.PERMISSION_GRANTED) {
            locationPermissionRequest.launch("pleos.car.permission.FUSED_LOCATION")
        } else {
            model.refreshVehiclePermissions()
        }
    }
}
