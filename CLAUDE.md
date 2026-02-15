# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Bridge APK is an Android automation bridge for OpenClaw (an AI agent running on Termux). It enables UI automation on Android without root access by using AccessibilityService and NotificationListenerService to interact with apps like WeChat.

**Status**: Early development phase - specifications complete, source code to be implemented.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    HTTP Layer (NanoHTTPD)               │
│                  Non-blocking, enqueue-only             │
│                    Port: 127.0.0.1:7788                 │
└─────────────────────┬───────────────────────────────────┘
                      │ Task Queue (persistent)
┌─────────────────────▼───────────────────────────────────┐
│               Action Engine (HandlerThread)             │
│           Single-threaded UI automation execution       │
└─────────────────────┬───────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────┐
│              AccessibilityBridgeService                 │
│           UI reading, clicking, input, scroll           │
└─────────────────────────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────┐
│            NotificationListenerService                  │
│               Monitor incoming messages                 │
└─────────────────────────────────────────────────────────┘
```

## Threading Model (Critical)

**UI automation MUST run on BridgeActionThread (HandlerThread), never on:**
- Main Thread
- Dispatchers.IO
- Dispatchers.Default
- Any thread pool

```kotlin
object ActionDispatcher {
    private val handlerThread = HandlerThread("BridgeActionThread").apply { start() }
    private val handler = Handler(handlerThread.looper)
    val dispatcher = handler.asCoroutineDispatcher()
}
```

## HTTP API (Port 7788)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/ping` | GET | Health check |
| `/health` | GET | Status, uptime, queue_length, mode |
| `/chat_list` | GET | Recent contacts |
| `/chat_history?target=NAME&limit=N` | GET | Message history |
| `/send_message` | POST | Queue message send |
| `/task_status?id=TASK_ID` | GET | Task execution status |
| `/config` | POST | Dynamic configuration |

## Technology Stack

- **Language**: Kotlin
- **HTTP Server**: NanoHTTPD (lightweight)
- **Concurrency**: Kotlin Coroutines + HandlerThread
- **Persistence**: Room or JSON file
- **Min SDK**: Android 9+ (API 28)

## Key Modules (Planned)

1. **BridgeServer**: HTTP routing, authentication, request queueing
2. **AccessibilityBridgeService**: UI operations (openApp, findAndClick, setText, scroll)
3. **NotificationListener**: Message monitoring with watchdog
4. **UILocator**: Multi-strategy node finding (text → content-desc → resource-id → class+index)
5. **ActionEngine**: High-level API to action sequence mapping
6. **StateManager**: Running state, error counting, safe mode
7. **ConfigManager**: Dynamic parameters, black/white lists, rate limiting

## Critical Implementation Rules

1. **HTTP layer only enqueues tasks, returns `queued` immediately** - never blocks on UI
2. **TaskQueue must persist** for crash recovery
3. **WakeLock + Keyguard dismiss** before UI operations
4. **Hide keyboard** (`GLOBAL_ACTION_BACK`) before clicking send button
5. **NotificationListener watchdog**: call `requestRebind()` periodically
6. **Safe mode**: after N consecutive failures, stop sending (only monitor)

## Node Location Strategy

Priority order for finding UI nodes:
1. Text match
2. Content-description
3. Resource-id
4. ClassName + index

Always implement fallbacks - WeChat UI changes between versions.

## Documentation

Detailed specifications in `doc/`:
- `BRIDGE_SPEC.md` - v1 initial spec
- `BRIDGE_SPEC_v2.md` - v2 with engineering review improvements
- `BRIDGE_SPEC_v3.md` - v3 production-ready spec (authoritative)
