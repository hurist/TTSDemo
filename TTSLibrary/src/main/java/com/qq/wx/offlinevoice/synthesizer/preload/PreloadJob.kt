package com.qq.wx.offlinevoice.synthesizer.preload

import com.qq.wx.offlinevoice.synthesizer.AppLogger
import com.qq.wx.offlinevoice.synthesizer.NetworkMonitor
import com.qq.wx.offlinevoice.synthesizer.SentenceSplitter
import com.qq.wx.offlinevoice.synthesizer.SentenceSplitterStrategy
import com.qq.wx.offlinevoice.synthesizer.Speaker
import com.qq.wx.offlinevoice.synthesizer.TtsRepository
import com.qq.wx.offlinevoice.synthesizer.TtsSynthesizer
import com.qq.wx.offlinevoice.synthesizer.isOnlyPunctuationOrEmpty
import com.qq.wx.offlinevoice.synthesizer.normalizer.SimplifiedTtsNormalizer
import com.qq.wx.offlinevoice.synthesizer.normalizer.TraditionalTtsNormalizer
import com.qq.wx.offlinevoice.synthesizer.online.WxApiException
import com.qq.wx.offlinevoice.synthesizer.processForTts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

internal class PreloadJob(
    private val ttsRepository: TtsRepository,
    private val networkMonitor: NetworkMonitor, // 接收 NetworkMonitor
    private val content: String,
    private val speaker: Speaker,
    private val splitterStrategy: SentenceSplitterStrategy,
    private val concurrencyLimit: Int, // 从构造函数接收并发数
    private val isTtsTextTraditional: Boolean,
    private val onCompletion: (Result<Unit>) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pendingBags = ConcurrentLinkedQueue<TtsSynthesizer.TtsBag>()
    private val isRunning = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)

    companion object {
        private const val TAG = "PreloadJob"
    }

    init {
        val bags = when (splitterStrategy) {
            SentenceSplitterStrategy.NEWLINE -> SentenceSplitter.sentenceSplitListByLine(content)
            SentenceSplitterStrategy.PUNCTUATION -> SentenceSplitter.sentenceSplitList(content)
        }
        pendingBags.addAll(bags)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        // 如果已取消、已完成或已在运行中，则直接返回
        if (isCancelled.get() || pendingBags.isEmpty() || !isRunning.compareAndSet(false, true)) {
            if (pendingBags.isEmpty() && !isCancelled.get()) {
                AppLogger.d(TAG,"任务已完成，无需再次启动。")
            }
            return
        }

        // 如果当前网络不好，则不启动，等待网络恢复后自动触发
        if (!networkMonitor.isNetworkGood.value) {
            AppLogger.w(TAG, "网络不佳，暂停预加载。等待网络恢复... text=${content.take(20)}")
            isRunning.set(false) // 重置状态，允许下次尝试
            return
        }

        AppLogger.d(TAG, "启动一轮预加载，待处理句子数: ${pendingBags.size}")

        pendingBags.asFlow()
            .map { bag ->
                val processedText = processTextForTts(bag.text)
                Triple(bag, processedText, processedText.isOnlyPunctuationOrEmpty())
            }
            .transform { (bag, text, isInvalid) ->
                if (isInvalid) {
                    AppLogger.d(TAG, "跳过无效/纯符号文本，并移除: ${bag.text}")
                    pendingBags.remove(bag) // <--- 关键：无效的要移除！
                    // 不发射数据，下游就不会处理它
                } else {
                    emit(bag to text) // 有效的数据才发射给 flatMapMerge
                }
            }
            .flatMapMerge(concurrency = concurrencyLimit) { (bag, text) ->
                flow {
                    try {
                        ttsRepository.getDecodedPcm(text, speaker, allowNetwork = true)
                        AppLogger.d(TAG, "预加载成功: text=${text.take(20)}")
                        // 成功后从队列中移除
                        pendingBags.remove(bag)
                        emit(Unit)
                    } catch (e: CancellationException) {
                        throw e // 协程取消，向上抛出
                    } catch (e: WxApiException) {
                        if (e.isTokenInvalid.not()) {
                            pendingBags.remove(bag) // 非Token无效的API错误，移除任务
                        }
                        // 网络或API错误，保留在队列中等待重试
                        AppLogger.e(TAG, "网络/API错误: text=${text.take(20)}, error=${e.message}")
                    } catch (e: Exception) {
                        // 其他未知异常，也暂时保留，可根据业务调整
                        AppLogger.e(TAG, "未知错误，将保留任务待重试: text=${text.take(20)}, error=${e.message}")
                    }
                }
            }
            .onCompletion { cause ->
                // 无论成功失败，一轮流结束后，都重置 isRunning 状态
                isRunning.set(false)

                if (cause is CancellationException) {
                    AppLogger.w(TAG, "预加载轮次被取消。")
                    // 取消由 cancel() 方法统一处理
                    return@onCompletion
                }
                if (cause != null) {
                    AppLogger.e(TAG, "预加载流因意外错误终止: $cause")
                    // 出现严重错误，可以考虑是否要回调 onCompletion(Result.failure(cause))
                }

                // 检查任务是否真正完成
                if (pendingBags.isEmpty() && !isCancelled.get()) {
                    AppLogger.d(TAG, "所有句子预加载完成。text=${content.take(20)}")
                    onCompletion(Result.success(Unit))
                } else if (!isCancelled.get()){
                    AppLogger.d(TAG, "本轮预加载结束，仍有 ${pendingBags.size} 个句子待处理。")
                }
            }
            .launchIn(scope)
    }

    private fun processTextForTts(input: String): String {
        var text = input.trim().processForTts()
        text = if (isTtsTextTraditional) {
            TraditionalTtsNormalizer.process(text)
        } else {
            //SimplifiedTtsNormalizer.process(text)
            text
        }
        return text
    }

    fun cancel(reason: String) {
        if (isCancelled.compareAndSet(false, true)) {
            scope.cancel("PreloadJob was cancelled, reason: $reason")
            pendingBags.clear()
            // 主动回调一个失败结果，让 Manager 知道是因取消而结束
            onCompletion(Result.failure(CancellationException("Preload job was cancelled by user.")))
            AppLogger.d(TAG, "预加载任务已取消, 原因：${reason}。text=${content.take(20)}")
        }
    }
}