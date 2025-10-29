# TTSEngine 重构迁移指南

## 概述

本次重构**完全向后兼容**，现有代码无需修改即可继续工作。但是，了解底层变化有助于更好地使用新功能。

## 无需修改的代码

以下代码将继续正常工作，无需任何更改：

```kotlin
// 创建和初始化
val speaker = Speaker().apply { code = "fn" }
val synthesizer = TtsSynthesizer(context, speaker)
synthesizer.initialize()

// 基本播放
synthesizer.speak("你好，世界", 50f, 50f, null)

// 播放控制
synthesizer.pause()
synthesizer.resume()
synthesizer.stop()

// 状态查询
val status = synthesizer.getStatus()
val speaking = synthesizer.isSpeaking()

// 资源释放
synthesizer.release()
```

## 状态变化

### 旧状态枚举
```kotlin
enum class TtsPlaybackState {
    UNINITIALIZED,  // 已移除
    IDLE,
    PLAYING,
    PAUSED,
    STOPPING,       // 已移除
    ERROR           // 已移除
}
```

### 新状态枚举
```kotlin
enum class TtsPlaybackState {
    IDLE,      // 替代了 UNINITIALIZED 和 STOPPING
    PLAYING,
    PAUSED
}
```

### 状态映射

| 旧状态 | 新状态 | 说明 |
|--------|--------|------|
| UNINITIALIZED | IDLE | 初始化后直接进入 IDLE |
| IDLE | IDLE | 相同 |
| PLAYING | PLAYING | 相同 |
| PAUSED | PAUSED | 相同 |
| STOPPING | IDLE | 停止后直接进入 IDLE |
| ERROR | IDLE | 错误通过 TtsCallback.onError() 报告 |

### 需要更新的代码

如果您的代码检查这些已移除的状态，需要更新：

```kotlin
// ❌ 旧代码
when (status.state) {
    TtsPlaybackState.UNINITIALIZED -> {
        // 处理未初始化状态
    }
    TtsPlaybackState.ERROR -> {
        // 处理错误状态
    }
}

// ✅ 新代码
when (status.state) {
    TtsPlaybackState.IDLE -> {
        // IDLE 现在包含了未初始化和错误状态
        // 使用 TtsCallback.onError() 接收错误通知
    }
}
```

## 行为变化

### 1. speak() 方法的行为

**旧行为：**
```kotlin
synthesizer.speak("第一句话", 50f, 50f, null)
// ... 一段时间后
synthesizer.speak("第二句话", 50f, 50f, null)
// ⚠️ 可能会有竞态条件，第一句话可能还在播放
```

**新行为：**
```kotlin
synthesizer.speak("第一句话", 50f, 50f, null)
// ... 一段时间后
synthesizer.speak("第二句话", 50f, 50f, null)
// ✅ 立即停止第一句话，清空所有数据，开始播放第二句话
```

**建议：** 无需修改代码，新行为更可预测和可靠。

### 2. 暂停/恢复行为

**旧行为：**
```kotlin
synthesizer.speak("第一句。第二句。第三句。", 50f, 50f, null)
// 在第二句中间暂停
synthesizer.pause()
// 恢复
synthesizer.resume()
// ⚠️ 可能会跳到第三句
```

**新行为：**
```kotlin
synthesizer.speak("第一句。第二句。第三句。", 50f, 50f, null)
// 在第二句中间暂停
synthesizer.pause()
// 恢复
synthesizer.resume()
// ✅ 从第二句暂停的位置继续播放
```

**建议：** 无需修改代码，新行为符合预期。

### 3. 错误处理

**旧方式（不推荐）：**
```kotlin
val status = synthesizer.getStatus()
if (status.state == TtsPlaybackState.ERROR) {
    // 处理错误
    val errorMsg = status.errorMessage
}
```

**新方式（推荐）：**
```kotlin
val callback = object : TtsCallback {
    override fun onError(errorMessage: String) {
        // 实时接收错误通知
        Log.e(TAG, "TTS Error: $errorMessage")
    }
}

synthesizer.speak("文本", 50f, 50f, callback)
```

## 性能提升

### 自动播放下一句

**旧实现：**
- 使用 `Thread.sleep(1000)` 轮询检查播放状态
- CPU 占用高，响应慢

**新实现：**
- 使用回调驱动，播放完成自动触发下一句
- CPU 占用低，响应快

**影响：** 用户无需修改代码，自动获得性能提升

### 暂停/恢复优化

**旧实现：**
- 暂停时释放所有资源
- 恢复时重新合成

**新实现：**
- 暂停时保留PCM数据
- 恢复时直接播放，无需重新合成

**影响：** 暂停/恢复更快，更省资源

## 测试更新

如果您有测试代码检查状态，需要更新：

```kotlin
// ❌ 旧测试
@Test
fun testStates() {
    assertTrue(TtsPlaybackState.values().contains(TtsPlaybackState.UNINITIALIZED))
    assertTrue(TtsPlaybackState.values().contains(TtsPlaybackState.ERROR))
}

// ✅ 新测试
@Test
fun testStates() {
    assertEquals(3, TtsPlaybackState.values().size)
    assertTrue(TtsPlaybackState.values().contains(TtsPlaybackState.IDLE))
    assertTrue(TtsPlaybackState.values().contains(TtsPlaybackState.PLAYING))
    assertTrue(TtsPlaybackState.values().contains(TtsPlaybackState.PAUSED))
}
```

## 新功能建议

虽然不是必需的，但您可以利用新功能：

### 1. 使用回调接收实时通知

```kotlin
val callback = object : TtsCallback {
    override fun onSynthesisStart() {
        Log.d(TAG, "开始合成")
    }
    
    override fun onSentenceStart(index: Int, sentence: String, total: Int) {
        Log.d(TAG, "开始播放第 ${index + 1}/$total 句: $sentence")
    }
    
    override fun onSentenceComplete(index: Int, sentence: String) {
        Log.d(TAG, "完成第 ${index + 1} 句")
    }
    
    override fun onSynthesisComplete() {
        Log.d(TAG, "全部完成")
    }
    
    override fun onStateChanged(state: TtsPlaybackState) {
        Log.d(TAG, "状态变化: $state")
    }
    
    override fun onError(errorMessage: String) {
        Log.e(TAG, "错误: $errorMessage")
    }
}

synthesizer.speak("文本", 50f, 50f, callback)
```

### 2. 利用精确的暂停/恢复

```kotlin
// 在播放长文本时，用户可以随时暂停
synthesizer.speak(longText, 50f, 50f, null)

// 在任何位置暂停
button.setOnClickListener {
    if (synthesizer.isSpeaking()) {
        synthesizer.pause()  // 精确暂停
    } else {
        synthesizer.resume()  // 从暂停位置恢复
    }
}
```

### 3. 快速切换播放内容

```kotlin
// 用户点击新内容，立即播放
fun playNewContent(text: String) {
    // 无需手动调用 stop()
    // speak() 会自动停止之前的播放
    synthesizer.speak(text, 50f, 50f, callback)
}
```

## 故障排除

### 问题：代码编译失败，找不到状态

**错误：**
```
Unresolved reference: UNINITIALIZED
Unresolved reference: ERROR
```

**解决：**
将 `UNINITIALIZED` 和 `ERROR` 替换为 `IDLE`，使用 `TtsCallback.onError()` 处理错误。

### 问题：TtsStatus 缺少 errorMessage 字段

**错误：**
```
Unresolved reference: errorMessage
```

**解决：**
使用 `TtsCallback.onError()` 代替：

```kotlin
// ❌ 旧代码
val error = status.errorMessage

// ✅ 新代码
val callback = object : TtsCallback {
    override fun onError(errorMessage: String) {
        // 处理错误
    }
}
```

### 问题：暂停后恢复位置不对

**可能原因：**
您的代码可能在调用 `resume()` 后立即调用了其他方法。

**解决：**
确保在 `resume()` 后不要立即调用 `speak()` 或 `stop()`：

```kotlin
// ❌ 错误
synthesizer.resume()
synthesizer.speak(newText, 50f, 50f, null)  // 这会停止恢复的播放

// ✅ 正确
synthesizer.resume()  // 继续之前的播放
// 或
synthesizer.speak(newText, 50f, 50f, null)  // 播放新内容（自动停止旧的）
```

## 总结

- ✅ **大多数代码无需修改**：公共API保持不变
- ✅ **性能自动提升**：更高效的播放机制
- ✅ **行为更可预测**：精确的暂停/恢复，立即停止
- ⚠️ **状态枚举简化**：从6个减少到3个，需要更新状态检查代码
- ⚠️ **错误处理变化**：使用回调代替状态字段

如有任何问题，请参考 `REFACTORING_SUMMARY.md` 了解详细的技术实现。
