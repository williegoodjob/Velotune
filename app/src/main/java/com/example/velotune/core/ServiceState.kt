package com.example.velotune.core

enum class ServiceState {
    STOPPED,   // 服務未啟動
    RUNNING,   // 正常模式：追蹤速度並調音
    PAUSED,    // 暫停模式：維持目前音量
    MUTED      // 靜音模式：強制靜音
}