package earth.worldwind.examples.autel

import com.autel.drone.sdk.vmodelx.constants.SDKUtils
import com.autel.drone.sdk.vmodelx.manager.DeviceManager
import com.autel.drone.sdk.vmodelx.manager.keyvalue.key.base.AutelKey

object KeyManager {
    fun getKeyManager(key: AutelKey<*>) = if (SDKUtils.isRemoteControlMode(key.mComponentIndex)) {
        DeviceManager.getDeviceManager().getFirstRemoteDevice()?.getKeyManager()
    } else {
        DeviceManager.getDeviceManager().getFirstDroneDevice()?.getKeyManager()
    }
}