package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import android.net.*
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitor(context: Context) {
    private val _isNetworkGood = MutableStateFlow(false)
    val isNetworkGood: StateFlow<Boolean> = _isNetworkGood.asStateFlow()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetworkStatus()
        }

        override fun onLost(network: Network) {
            updateNetworkStatus()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            updateNetworkStatus()
        }
    }

    init {
        registerCallback()
        updateNetworkStatus()
    }

    private fun registerCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // API 24+ 使用默认回调
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } else {
            // API 21–23 需要手动构造请求
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }
    }

    private fun updateNetworkStatus() {
        val isGood = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            val info = connectivityManager.activeNetworkInfo
            info?.isConnected == true
        }
        _isNetworkGood.value = isGood
    }

    fun release() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
            // 忽略重复注销异常
        }
    }
}
