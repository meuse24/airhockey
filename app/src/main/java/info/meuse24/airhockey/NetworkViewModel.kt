package info.meuse24.airhockey

import android.app.Application
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import info.meuse24.airhockey.network.P2PNetworkManager
import info.meuse24.airhockey.network.WifiDirectNetworkManager

class NetworkViewModel(application: Application) : AndroidViewModel(application) {

    val networkManager: P2PNetworkManager by lazy {
        val p2pManager = application.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        val channel = p2pManager.initialize(application, application.mainLooper, null)
        WifiDirectNetworkManager(application, p2pManager, channel).apply {
            initialize()
        }
    }

    var isTransmissionActive by mutableStateOf(true)
        private set

    fun toggleTransmission(active: Boolean) {
        isTransmissionActive = active
    }

    override fun onCleared() {
        super.onCleared()
        networkManager.release()
    }
}