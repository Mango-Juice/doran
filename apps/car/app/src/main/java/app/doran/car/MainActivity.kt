package app.doran.car

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doran.car.ui.CarScreen
import app.doran.car.ui.DoranTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: CarViewModel
    private val locationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.refreshVehiclePermissions()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[CarViewModel::class.java]
        setContent {
            val state = model.state.collectAsStateWithLifecycle(lifecycleOwner = this@MainActivity).value
            DoranTheme {
                CarScreen(state, model::start, {
                    model.pause()
                }, model::finish, model::connect, model::confirmPairing, ::requestVehiclePermission, model::setSoloAsserted)
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
