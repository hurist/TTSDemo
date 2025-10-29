# TTSEngine 回调流程图

## 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        TtsSynthesizer                        │
│                                                              │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐  │
│  │   Sentence   │ -> │   Sentence   │ -> │   Sentence   │  │
│  │      0       │    │      1       │    │      2       │  │
│  └──────────────┘    └──────────────┘    └──────────────┘  │
│         │                   │                   │           │
│         ├─ PCM Chunk 0      ├─ PCM Chunk 0      ├─ PCM...  │
│         ├─ PCM Chunk 1      ├─ PCM Chunk 1                  │
│         └─ PCM Chunk 2      └─ PCM Chunk 2                  │
│                                                              │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           v
                    ┌─────────────┐
                    │ AudioPlayer │
                    └─────────────┘
```

## 旧实现（使用 Thread.sleep）

```
synthesizeSentence() {
    while (有数据) {
        合成 PCM 数据
        播放 PCM 数据
        Thread.sleep(1000) ❌ 轮询等待
    }
}

时间线：
┌────────┬────────┬────────┬────────┬────────┬────────┐
│Chunk 0 │Sleep 1s│Chunk 1 │Sleep 1s│Chunk 2 │Sleep 1s│
│播放中  │⏱ 等待 │播放中  │⏱ 等待 │播放中  │⏱ 等待 │
└────────┴────────┴────────┴────────┴────────┴────────┘
         ↑ CPU浪费          ↑ CPU浪费         ↑ CPU浪费
```

## 新实现（使用回调）

```
synthesizeSentence() {
    // 1. 合成所有PCM块
    while (有数据) {
        pcmChunk = 合成 PCM 数据
        currentSentencePcm.add(pcmChunk)  // 存储
    }
    
    // 2. 开始播放第一个块
    playPcmChunksFromIndex(0)
}

playPcmChunksFromIndex(index) {
    pcmData = currentSentencePcm[index]
    
    audioPlayer.play(pcmData) {
        // 3. 播放完成回调 ✅
        currentPcmChunkIndex++
        if (更多块) {
            playPcmChunksFromIndex(currentPcmChunkIndex)
        } else {
            moveToNextSentence()
        }
    }
}

时间线：
┌────────┬────────┬────────┬────────┬────────┬────────┐
│Chunk 0 │回调触发│Chunk 1 │回调触发│Chunk 2 │回调触发│
│播放中  │⚡ 立即 │播放中  │⚡ 立即 │播放中  │⚡ 立即 │
└────────┴────────┴────────┴────────┴────────┴────────┘
         ↑ 零延迟           ↑ 零延迟          ↑ 零延迟
```

## 详细流程图

### 播放流程（无暂停）

```
speak("第一句。第二句。")
    │
    v
分句: ["第一句。", "第二句。"]
    │
    v
processNextSentence()  (index=0)
    │
    v
synthesizeSentenceAndPlay("第一句。")
    │
    ├─ 合成PCM块 -> [chunk0, chunk1, chunk2]
    │
    v
playPcmChunksFromIndex(0)
    │
    v
AudioPlayer.play(chunk0) {
    完成回调 ───────────┐
}                      │
                       v
                playPcmChunksFromIndex(1)
                       │
                       v
                AudioPlayer.play(chunk1) {
                    完成回调 ───────────┐
                }                      │
                                       v
                                playPcmChunksFromIndex(2)
                                       │
                                       v
                                AudioPlayer.play(chunk2) {
                                    完成回调 ───────────┐
                                }                      │
                                                       v
                                                moveToNextSentence()
                                                       │
                                                       v
                                                processNextSentence() (index=1)
                                                       │
                                                       v
                                                synthesizeSentenceAndPlay("第二句。")
                                                       │
                                                       ... (重复流程)
```

### 暂停/恢复流程

```
初始状态: 正在播放 Sentence 1, Chunk 1
┌────────────────────────────────────────┐
│ Sentence 0: [✓ chunk0, ✓ chunk1, ✓ chunk2] │ 已完成
│ Sentence 1: [✓ chunk0, ► chunk1, chunk2]    │ 当前播放
│ Sentence 2: [chunk0, chunk1]                │ 未开始
└────────────────────────────────────────┘

用户调用: pause()
    │
    v
保存状态:
    currentSentenceIndex = 1
    currentPcmChunkIndex = 1
    currentSentencePcm = [chunk0, chunk1, chunk2]
    │
    v
AudioPlayer.pause()  // 暂停AudioTrack，不释放
    │
    v
状态变为: PAUSED
┌────────────────────────────────────────┐
│ Sentence 1: [✓ chunk0, ⏸ chunk1, chunk2]   │ 暂停在chunk1
└────────────────────────────────────────┘

用户调用: resume()
    │
    v
AudioPlayer.resume()  // 恢复AudioTrack
    │
    v
continuePlayback()
    │
    v
playPcmChunksFromIndex(1)  // 从chunk1继续 ✅
    │
    v
┌────────────────────────────────────────┐
│ Sentence 1: [✓ chunk0, ► chunk1, chunk2]    │ 继续播放
└────────────────────────────────────────┘
```

### speak() 立即停止流程

```
当前状态: 正在播放 "旧文本"
┌────────────────────────────────────────┐
│ Sentence 0: [✓ chunk0, ► chunk1, chunk2]    │ 正在播放
│ Sentence 1: [chunk0, chunk1]                │ 待播放
└────────────────────────────────────────┘

用户调用: speak("新文本")
    │
    v
stateLock.withLock {
    │
    v
    stopInternal()  // 同步停止 ✅
        │
        ├─ shouldStop = true
        ├─ audioPlayer.stopAndRelease()
        ├─ sentences.clear()
        ├─ currentSentencePcm.clear()
        └─ currentPcmChunkIndex = 0
    │
    v
    加载新数据
        │
        ├─ sentences = ["新", "文", "本"]
        ├─ currentSentenceIndex = 0
        └─ shouldStop = false
    │
    v
    启动新播放
}
    │
    v
新状态: 播放 "新文本"
┌────────────────────────────────────────┐
│ Sentence 0: [► chunk0, chunk1]              │ 播放新内容
│ Sentence 1: [chunk0, chunk1]                │ 待播放
│ Sentence 2: [chunk0, chunk1, chunk2]        │ 待播放
└────────────────────────────────────────┘
```

## 状态转换图

```
              initialize()
                   │
                   v
    ┌──────────────────────────┐
    │         IDLE             │ <─────────┐
    │  (初始化完成/播放完成)    │           │
    └──────────────────────────┘           │
               │                            │
               │ speak()                    │
               v                            │
    ┌──────────────────────────┐           │
    │       PLAYING            │           │
    │    (正在播放句子)         │           │
    └──────────────────────────┘           │
          │           ^                     │
  pause() │           │ resume()            │
          v           │                     │
    ┌──────────────────────────┐           │
    │        PAUSED            │           │
    │   (暂停在某个位置)        │           │
    └──────────────────────────┘           │
                                            │
              stop() / 播放完成 ────────────┘
```

## 对比总结

| 特性 | 旧实现 | 新实现 |
|------|--------|--------|
| **播放机制** | Thread.sleep轮询 ❌ | 回调驱动 ✅ |
| **CPU占用** | 高（持续轮询） | 低（事件驱动） |
| **响应延迟** | 最多1秒 | 即时（毫秒级） |
| **暂停位置** | 丢失 ❌ | 精确保存 ✅ |
| **状态数量** | 6个 | 3个 ✅ |
| **speak()行为** | 可能竞态 ❌ | 同步停止 ✅ |
| **代码复杂度** | 高（标志位多） | 低（状态少） ✅ |

## 关键优化点

1. **事件驱动 vs 轮询**
   - 旧: `while() { play(); sleep(1000); }`
   - 新: `play(data) { onComplete -> playNext() }`

2. **状态管理**
   - 旧: 多个标志位 (`isPausedFlag`, `isStoppedFlag`, `currentState`)
   - 新: 单一状态 + 位置信息 (`currentState`, `currentPcmChunkIndex`)

3. **资源利用**
   - 旧: 暂停时释放所有资源，恢复时重新合成
   - 新: 暂停时保留PCM数据，恢复时直接播放

4. **并发控制**
   - 旧: 多个锁 (`stateLock`, `pauseLock`) + 标志位
   - 新: 单一锁 + 简化的控制流
