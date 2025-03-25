package earth.worldwind.examples.autel

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.autel.drone.sdk.SDKConstants
import com.autel.player.player.AutelPlayerManager

class AutelPlayerSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr, defStyleRes), SurfaceHolder.Callback {

    private val channelId: Int = SDKConstants.getZoomChancelId()

    fun onStart() {
        holder.addCallback(this)
    }

    fun onStop() {
        holder.removeCallback(this)
    }

    override fun surfaceCreated(surfaceHolder: SurfaceHolder) {}

    override fun surfaceChanged(surfaceHolder: SurfaceHolder, i: Int, i2: Int, i3: Int) {
        AutelPlayerManager.getInstance().getAutelPlayer(channelId)?.also {
            it.setExternalSurface(surfaceHolder.surface)
            if (it.videoWidth != 0 && it.videoHeigh != 0) {
                surfaceHolder.setFixedSize(it.videoWidth, it.videoHeigh)
            }
        }
    }

    override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
        surfaceHolder.surface?.release()
        AutelPlayerManager.getInstance().getAutelPlayer(channelId)?.also {
            it.setExternalSurface(null)
        }
    }

}