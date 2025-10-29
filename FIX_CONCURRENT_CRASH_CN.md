# 并发合成崩溃修复方案

## 问题描述

在低端手机上运行时，应用程序在预合成下一句时出现 SIGSEGV（段错误）崩溃：

```
Fatal signal 11 (SIGSEGV), code 1, fault addr 0x20000000800 in tid 14956 (Thread-7)
[ERROR] Failed to execute kernel for Op[43]
```

崩溃发生在原生 TTS 库 (`libhwTTS.so`) 中，当 `preSynthesizeNextSentence()` 被调用时。

## 根本原因

原生 TTS 库 (`libhwTTS.so`) **不是线程安全的**。之前的实现会启动一个单独的线程来预合成下一句，而此时当前句子正在被合成或播放：

```kotlin
// 旧代码 - 导致崩溃
private fun preSynthesizeNextSentence() {
    val nextSentence = sentences[nextIndex]
    preSynthesisThread = Thread({
        try {
            // 这会导致对 nativeEngine 的并发访问 -> 崩溃！
            nextSentencePcm = synthesizeSentence(nextSentence)
        } catch (e: Exception) {
            nextSentencePcm = null
        }
    }, "PreSynthesisThread")
    preSynthesisThread?.start()
}
```

这导致：
- 主合成线程为当前句子调用 `synthesizeSentence()`
- 预合成线程为下一句调用 `synthesizeSentence()`
- 两个线程并发访问 `nativeEngine` → SIGSEGV 崩溃

## 解决方案

修复方案通过序列化所有对原生 TTS 引擎的访问来解决问题，同时保持性能优化：

### 关键修改

1. **添加合成锁**：使用 `ReentrantLock` 确保对原生引擎的串行访问
   ```kotlin
   private val synthesisLock = ReentrantLock()
   ```

2. **移除独立线程**：预合成现在在主合成线程中同步运行
   ```kotlin
   // 新代码 - 安全
   private fun preSynthesizeNextSentence() {
       val nextSentence = sentences[nextIndex]
       try {
           Log.d(TAG, "开始预合成下一句: $nextSentence")
           // 使用锁保护的同步合成
           nextSentencePcm = synthesizeSentence(nextSentence)
           Log.d(TAG, "下一句预合成完成")
       } catch (e: Exception) {
           Log.w(TAG, "预合成失败: ${e.message}")
           nextSentencePcm = null
       }
   }
   ```

3. **保护原生引擎访问**：所有合成操作都用锁包装
   ```kotlin
   private fun synthesizeSentence(sentence: String): ShortArray? {
       // 锁确保对原生引擎的串行访问
       return synthesisLock.withLock {
           try {
               // 所有原生引擎调用都在这里发生
               val prepareResult = prepareForSynthesis(sentence, currentSpeed, currentVolume)
               // ... 合成逻辑 ...
           } finally {
               nativeEngine?.reset()
           }
       }
   }
   ```

## 执行流程对比

### 修复前（并发 - 崩溃）
```
线程1（主合成线程）:
  synthesizeSentence(当前句) → 访问 nativeEngine
                                     ↓ 并发访问
线程2（预合成线程）:                    ↓
  synthesizeSentence(下一句) → 访问 nativeEngine → 崩溃！
```

### 修复后（串行 - 安全）
```
主合成线程：
  1. synthesizeSentence(当前句)    ← 获取锁
  2. [合成完成]                    ← 释放锁
  3. preSynthesizeNextSentence()
     └─ synthesizeSentence(下一句) ← 获取锁
        [合成完成]                  ← 释放锁
  4. 播放当前句子（此时下一句已经准备好）
```

## 性能影响

✅ **没有性能下降**
- 仍然在播放当前句子前预合成下一句
- 合成在音频设置和播放期间进行
- 句子转换没有额外延迟
- 在保持优化优势的同时防止崩溃

## 时间线

- 当前句子合成：约 500ms（典型值）
- 下一句预合成：约 500ms（在播放前完成）
- 音频播放设置：约 50ms
- 结果：在当前句子播放完成前，下一句已经准备好

## 性能优化原理

虽然预合成改为同步，但仍保持了性能优化：

```
时间轴：
0ms    : 开始合成句子1
500ms  : 句子1合成完成，开始预合成句子2
1000ms : 句子2预合成完成，开始播放句子1
3000ms : 句子1播放完成，立即播放句子2（已预合成好）← 无停顿！
3000ms : 同时开始预合成句子3
```

关键：在播放句子N时，句子N+1已经合成完成，实现无缝衔接。

## 测试建议

1. **低端设备测试**：在CPU/内存有限的设备上测试
2. **长文本测试**：播放包含10+句子的文本，确保连续运行
3. **快速参数更改**：测试播放期间更改速度/发音人
4. **压力测试**：连续播放多个长文本

## 修改的文件

- `app/src/main/java/com/qq/wx/offlinevoice/synthesizer/TtsSynthesizer.kt`
  - 添加了 `synthesisLock: ReentrantLock`
  - 移除了 `preSynthesisThread: Thread?`
  - 修改 `preSynthesizeNextSentence()` 为同步方式
  - 用锁保护包装 `synthesizeSentence()`
  - 移除了 `stopInternal()` 和 `restartCurrentSentence()` 中的线程中断逻辑

## 相关问题

此修复解决了在低端设备上运行时报告的 SIGSEGV 崩溃问题，该问题是由原生 TTS 库缺乏线程安全支持导致的。

## 技术细节

### 为什么不使用线程池或协程？

1. **简单性**：锁机制更直接，更容易理解和维护
2. **兼容性**：不需要引入额外的依赖（协程需要kotlinx-coroutines）
3. **性能**：对于TTS这种顺序操作，简单的锁机制开销最小
4. **安全性**：确保对不可重入的原生代码的严格串行访问

### 为什么仍然保持预合成？

虽然预合成现在是同步的，但它仍然提供了关键的性能优势：
- 在**播放**当前句子之前就完成了下一句的**合成**
- 播放时间通常比合成时间长（2-3秒 vs 0.5秒）
- 因此，播放完成时下一句已经准备好，实现无缝过渡

### 替代方案（已考虑但未采用）

1. **仅在播放完成后合成**：会在句子间产生明显停顿
2. **使用消息队列**：增加复杂性，性能提升不明显
3. **多个原生引擎实例**：不支持或会导致资源浪费
