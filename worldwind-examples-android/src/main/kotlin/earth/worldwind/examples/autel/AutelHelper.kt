package earth.worldwind.examples.autel

import android.content.Context
import com.autel.drone.sdk.store.SDKStorage
import com.autel.drone.sdk.vmodelx.SDKManager
import com.autel.drone.sdk.vmodelx.device.IAutelDroneListener
import com.autel.drone.sdk.vmodelx.interfaces.IBaseDevice
import com.autel.drone.sdk.vmodelx.manager.keyvalue.callback.CommonCallbacks
import com.autel.drone.sdk.vmodelx.manager.keyvalue.key.CommonKey
import com.autel.drone.sdk.vmodelx.manager.keyvalue.key.base.KeyTools
import com.autel.drone.sdk.vmodelx.manager.keyvalue.value.flight.bean.DroneSystemStateHFNtfyBean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object AutelHelper : IAutelDroneListener {

    private val _gimbalAttitude = MutableStateFlow(GimbalAttitude())
    val gimbalAttitude: StateFlow<GimbalAttitude> = _gimbalAttitude.asStateFlow()

    private val keyDroneSystemStatusHFNtfy = KeyTools.createKey(CommonKey.KeyDroneSystemStatusHFNtfy)

    private val keyDroneSystemStatusHFNtfyListener = object : CommonCallbacks.KeyListener<DroneSystemStateHFNtfyBean> {
        override fun onValueChange(oldValue: DroneSystemStateHFNtfyBean?, newValue: DroneSystemStateHFNtfyBean) {
            newValue.gimbalAttitude?.run {
                _gimbalAttitude.update {
                    GimbalAttitude(
                        roll = getRollDegree().toDouble(),
                        yaw = getYawDegree().toDouble(),
                        pitch = getPitchDegree().toDouble()
                    )
                }
            }
        }
    }

    fun init(context: Context) {
        SDKStorage.init(context)
        SDKManager.get().init(context, false)
        SDKManager.get().getDeviceManager().addDroneListener(this)
    }

    override fun onCameraAbilityFetchListener(fetched: Boolean) {

    }

    override fun onDroneChangedListener(connected: Boolean, drone: IBaseDevice) {
        if (connected) {
           subscribe()
        } else {
            unsubscribe()
        }
    }

    private fun subscribe() {
        KeyManager.getKeyManager(keyDroneSystemStatusHFNtfy)
            ?.listen(keyDroneSystemStatusHFNtfy, keyDroneSystemStatusHFNtfyListener)
    }

    private fun unsubscribe() {
        KeyManager.getKeyManager(keyDroneSystemStatusHFNtfy)
            ?.cancelListen(keyDroneSystemStatusHFNtfy, keyDroneSystemStatusHFNtfyListener)
    }

    var yaw = 0.0
    fun test(scope: CoroutineScope) {
        scope.launch {
//            while (true) {
//                yaw += 1
                _gimbalAttitude.update {
                    GimbalAttitude(
                        roll = 0.0,
                        yaw = yaw,
                        pitch = -45.0
                    )
//                }
//                delay(100L)
            }
        }
    }

}