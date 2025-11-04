package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import android.net.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NetworkMonitor(context: Context) {
    private val _isNetworkGood = MutableStateFlow(false)
    val isNetworkGood: StateFlow<Boolean> = _isNetworkGood.asStateFlow()

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // 维护一个当前可用并具备上网能力的网络的集合
    private val validNetworks: MutableSet<Network> = HashSet()
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                mutex.withLock {
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                        validNetworks.add(network)
                    }
                    updateStatus()
                }
            }
        }

        override fun onLost(network: Network) {
            scope.launch {
                mutex.withLock {
                    validNetworks.remove(network)
                    updateStatus()
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            scope.launch {
                mutex.withLock {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        validNetworks.add(network)
                    } else {
                        validNetworks.remove(network)
                    }
                    updateStatus()
                }
            }
        }
    }

    init {
        registerCallback()
        // 首次检查当前状态
        checkInitialState()
    }

    private fun registerCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    private fun checkInitialState() {
        scope.launch {
            mutex.withLock {
                try {
                    val networks = connectivityManager.allNetworks
                    for (network in networks) {
                        val caps = connectivityManager.getNetworkCapabilities(network)
                        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                            validNetworks.add(network)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("NetworkMonitor", "初始化网络状态检查失败", e)
                }
                updateStatus()
            }
        }
    }

    // 更新状态，只有在状态确实改变时才发射新值
    private fun updateStatus() {
        val newStatus = validNetworks.isNotEmpty()
        if (_isNetworkGood.value != newStatus) {
            Log.d("NetworkMonitor", "网络状态更新: isNetworkGood = $newStatus")
            _isNetworkGood.value = newStatus
        }
    }

    fun release() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e("NetworkMonitor", "注销网络回调失败", e)
        }
    }
}