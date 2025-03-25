package earth.worldwind.examples

import android.app.Application
import earth.worldwind.examples.autel.AutelHelper

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        AutelHelper.init(this)
    }

}