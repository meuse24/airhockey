package info.meuse24.airhockey.gdx

import android.app.Application
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import androidx.lifecycle.AndroidViewModel
import info.meuse24.airhockey.network.P2PNetworkManager
import info.meuse24.airhockey.network.WifiDirectManager

class NetworkViewModel(application: Application) : AndroidViewModel(application) {
    val networkManager: P2PNetworkManager by lazy {
        val manager = application.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        val channel = manager.initialize(application, application.mainLooper, null)
        WifiDirectManager(application, manager, channel)
    }

    override fun onCleared() {
        super.onCleared()
        networkManager.release()
    }
}
