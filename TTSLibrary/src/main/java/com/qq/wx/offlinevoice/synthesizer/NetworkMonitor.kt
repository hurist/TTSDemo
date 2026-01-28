package com.qq.wx.offlinevoice.synthesizer

import android.content.Context
import android.net.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArraySet

/**
 * NetworkMonitor（最终版，兼容 API 21+，已修复VPN和IMS问题）
 *
 * ✅ Android 6+：使用 NET_CAPABILITY_VALIDATED（最精确）
 * ⚙️ Android 5–5.1：fallback 到 legacy 的 NetworkInfo.isConnected()
 *
 * 💡 新增逻辑: 明确排除 VPN 和仅用于 VoLTE 的 IMS 网络。
 */
internal class NetworkMonitor(context: Context) {

    private val TAG = "NetworkMonitor"

    private val _isNetworkGood = MutableStateFlow(false)
    val isNetworkGood: StateFlow<Boolean> = _isNetworkGood.asStateFlow()

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val validNetworks = CopyOnWriteArraySet<Network>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            AppLogger.d(TAG, "onAvailable: 网络 $network 可用，检查其能力...")
            scope.launch {
                mutex.withLock {
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    if (isNetworkConsideredValid(caps)) {
                        validNetworks.add(network)
                        AppLogger.i(TAG, "onAvailable: ✅ 网络 $network 有效，已添加。")
                    } else {
                        AppLogger.w(TAG, "onAvailable: ❌ 网络 $network 无效或被忽略。能力: $caps")
                    }
                    updateStatus()
                }
            }
        }

        override fun onLost(network: Network) {
            AppLogger.d(TAG, "onLost: 网络 $network 已丢失。$validNetworks")
            scope.launch {
                mutex.withLock {
                    validNetworks.remove(network)
                    updateStatus()
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            AppLogger.d(TAG, "onCapabilitiesChanged -> 网络: $network, 新能力: $caps")
            scope.launch {
                mutex.withLock {
                    if (isNetworkConsideredValid(caps)) {
                        if (validNetworks.add(network)) {
                            AppLogger.i(TAG, "onCapabilitiesChanged: ✅ 网络 $network 变为有效，已添加。$validNetworks")
                        }
                    } else {
                        if (validNetworks.remove(network)) {
                            AppLogger.w(TAG, "onCapabilitiesChanged: ❌ 网络 $network 变为无效或被忽略，已移除。$validNetworks")
                        }
                    }
                    updateStatus()
                }
            }
        }
    }

    init {
        registerCallback()
        checkInitialState()
    }

    private fun registerCallback() {
        val request = NetworkRequest.Builder()
            // 保持监听所有网络类型，然后在 isNetworkConsideredValid 中进行过滤
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN) // 仍然监听VPN，以便在onCapabilitiesChanged中正确移除
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
            AppLogger.d(TAG, "网络回调注册成功。")
        } catch (e: Exception) {
            AppLogger.e(TAG, "注册网络回调失败: ${e.message}")
        }
    }

    private fun checkInitialState() {
        scope.launch {
            mutex.withLock {
                AppLogger.d(TAG, "--- 正在检查初始网络状态 ---")
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val networks = connectivityManager.allNetworks
                        validNetworks.clear() // 清空，防止重复添加
                        AppLogger.d(TAG, "发现 ${networks.size} 个网络，开始遍历...")
                        for (network in networks) {
                            val caps = connectivityManager.getNetworkCapabilities(network)
                            AppLogger.d(TAG, "检查网络: $network, 能力: $caps")
                            if (isNetworkConsideredValid(caps)) {
                                AppLogger.i(TAG, "✅ 初始检查: 网络 $network 被认为是有效的，已添加。")
                                validNetworks.add(network)
                            } else {
                                AppLogger.w(TAG, "❌ 初始检查: 网络 $network 无效或被忽略。")
                            }
                        }
                    } else {
                        val info = connectivityManager.activeNetworkInfo
                        AppLogger.d(TAG, "Android 5.x Fallback: activeNetworkInfo = $info")
                        if (info != null && info.isConnected) {
                            _isNetworkGood.value = true
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "初始化网络状态检查失败: ${e.message}")
                }
                updateStatus()
                AppLogger.d(TAG, "--- 初始网络状态检查结束 ---")
            }
        }
    }

    private fun isNetworkConsideredValid(caps: NetworkCapabilities?): Boolean {
        if (caps == null) return false

        // 1. 必须具备 INTERNET 能力
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        // 2. 必须经过验证 (Android 6.0+)
        val isValidated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            true
        }

        // 3. 必须不是 VPN
        //    我们只关心设备底层的物理连接状态。
        //    VPN 会依附于物理连接，只要物理连接有效，我们就可以认为网络是好的。
        //    单独判断 VPN 会在物理网络断开时导致误判。
        val isNotVpn = !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        return hasInternet && isValidated && isNotVpn
    }

    private fun updateStatus() {
        val newStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            validNetworks.isNotEmpty()
        } else {
            val info = connectivityManager.activeNetworkInfo
            info != null && info.isConnected
        }

        if (_isNetworkGood.value != newStatus) {
            AppLogger.i(TAG, "网络状态更新: isNetworkGood 从 ${_isNetworkGood.value} 变为 $newStatus. 有效网络数量: ${validNetworks.size}")
            _isNetworkGood.value = newStatus
        }
    }

    fun release() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            AppLogger.d(TAG, "网络回调已注销。")
        } catch (e: Exception) {
            AppLogger.e(TAG, "注销网络回调失败: ${e.message}")
        }
        scope.cancel()
    }
}