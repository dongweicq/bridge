# AGENTS.md

Guidelines for AI coding agents working on the Bridge APK project.

## Project Overview

Bridge is an Android automation app that enables UI automation via AccessibilityService. It provides an HTTP API (port 7788) for external agents (like OpenClaw on Termux) to control WeChat/Moxin.

**Tech Stack**: Kotlin, NanoHTTPD, Kotlin Coroutines, Room/SharedPreferences, ML Kit OCR

## Build Commands

```bash
# Build Debug APK
./gradlew assembleDebug

# Build Release APK (unsigned)
./gradlew assembleRelease

# Clean build
./gradlew clean

# Build all
./gradlew build
```

**Output locations**:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release-unsigned.apk`

## Testing

No unit tests currently exist. Manual testing via:

```bash
# Deploy and monitor
bash auto_deploy.sh          # Full: download → install → start
bash auto_deploy.sh logs     # Monitor logs
bash auto_deploy.sh test     # Test HTTP API endpoints
```

**API Testing**:
```bash
curl http://127.0.0.1:7788/ping
curl http://127.0.0.1:7788/health
```

## Code Style

### Kotlin Conventions

- **kotlin.code.style=official** (set in gradle.properties)
- Target JVM: 17
- Min SDK: 26 (Android 8.0)
- Use `kotlinx.coroutines` for async operations

### Imports

```kotlin
// Standard order:
// 1. Android SDK
import android.app.Service
import android.content.Intent
// 2. AndroidX
import androidx.core.app.NotificationCompat
// 3. Third-party (NanoHTTPD, Gson, etc.)
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
// 4. Project internal
import com.bridge.model.Task
import com.bridge.util.ToolManager
// 5. Kotlin stdlib/coroutines
import kotlinx.coroutines.Dispatchers
import kotlin.random.Random
```

### Naming

| Element | Convention | Example |
|---------|-----------|---------|
| Classes | PascalCase | `BridgeServer`, `MoxinActionEngine` |
| Functions | camelCase | `executeSendMessage()`, `openMoxin()` |
| Properties | camelCase | `isRunning`, `bridgeServer` |
| Constants | SCREAMING_SNAKE | `HTTP_PORT`, `MOXIN_PACKAGE` |
| Companion object | `companion object` | Singleton pattern with `instance` |

### Data Classes

```kotlin
data class Tool(
    val id: String,
    val name: String,
    val description: String = "",
    val x: Float,
    val y: Float,
    val preToolIds: List<String> = emptyList(),
    val isBuiltIn: Boolean = false
)
```

## Critical Threading Rules

**UI automation MUST run on `ActionDispatcher.dispatcher`, NEVER on:**
- Main Thread
- `Dispatchers.IO`
- `Dispatchers.Default`

```kotlin
// CORRECT
val result = withContext(ActionDispatcher.dispatcher) {
    service.clickAt(x, y)
}

// WRONG - will cause issues
withContext(Dispatchers.IO) {
    service.clickAt(x, y)
}
```

## Architecture Patterns

### HTTP Layer (Non-blocking)

HTTP handlers only **enqueue** tasks, return immediately:

```kotlin
private fun handleSendMessage(session: IHTTPSession): Response {
    val task = Task(type = TaskType.SEND_MESSAGE, target, message)
    tasks[task.id] = task
    executeTaskAsync(task)  // Fire and forget
    return json(Response.Status.OK, mapOf("status" to "queued", "task_id" to task.id))
}
```

### Singleton Services

```kotlin
companion object {
    var instance: BridgeAccessibilityService? = null
        private set
}
```

### Object Singletons

```kotlin
object ActionDispatcher {
    val dispatcher: CoroutineDispatcher
}

object ToolManager {
    fun getAllTools(context: Context): List<Tool>
}
```

## Error Handling

### Logging Pattern

```kotlin
companion object {
    private const val TAG = "BridgeServer"
}

Log.d(TAG, "Task started")
Log.e(TAG, "Failed to start server", e)
```

### Result Types

```kotlin
data class TaskResult(
    val success: Boolean,
    val message: String = "",
    val error: String? = null
) {
    companion object {
        fun ok(message: String = "成功") = TaskResult(success = true, message = message)
        fun fail(error: String) = TaskResult(success = false, error = error)
    }
}
```

### Try-Catch Pattern

```kotlin
return try {
    service.performAction()
    TaskResult.ok()
} catch (e: Exception) {
    Log.e(TAG, "Action failed", e)
    TaskResult.fail(e.message ?: "Unknown error")
}
```

## File Organization

```
app/src/main/java/com/bridge/
├── BridgeApp.kt           # Application class
├── MainActivity.kt        # Main UI
├── BridgeService.kt       # Foreground service (HTTP server)
├── BridgeAccessibilityService.kt  # UI automation
├── action/
│   ├── ActionDispatcher.kt   # Single-thread dispatcher
│   └── MoxinActionEngine.kt  # High-level actions
├── http/
│   └── BridgeServer.kt       # NanoHTTPD server
├── model/
│   └── Task.kt               # Data models
├── util/
│   └── ToolManager.kt        # Tool configuration
└── ocr/
    └── OcrService.kt         # ML Kit OCR
```

## CI/CD

GitHub Actions workflow: `.github/workflows/build.yml`

- Triggers on push to `main`, `master`, `phase1-foundation`
- Ignores `*.md`, `LICENSE`, `.gitignore`, `doc/**`
- Auto-creates releases with version `v1.0.{run_number}`

## Automated Deployment Workflow (CRITICAL)

**After modifying code, agents MUST automatically execute the full deployment flow without user prompts.**

### Full Automation Flow

```bash
# Step 1: Commit and push code
git add -A
git commit -m "feat: description"
git push

# Step 2: Poll for GitHub Actions build completion (AUTO)
gh run watch --exit-status

# Step 3: Download and install APK (AUTO)
bash auto_deploy.sh

# Step 4: Monitor logs (AUTO)
bash auto_deploy.sh logs
```

### Critical Rules

1. **After `git push`, MUST immediately execute `gh run watch`** - no waiting for user
2. **After build success, MUST immediately execute `auto_deploy.sh`** - no waiting for user
3. **NEVER stop mid-flow to ask user** - complete all steps automatically
4. **Local builds are NOT used** - always use GitHub Actions builds

### File Types That Trigger Build

- `*.kt`, `*.java` - Kotlin/Java source code
- `*.xml` - Layouts and configurations
- `*.gradle*` - Build configurations
- `*.yml`, `*.yaml` - CI/CD configs

### File Types That Do NOT Trigger Build

- `*.md` - Documentation
- `LICENSE` - License file
- `.gitignore` - Git ignore rules
- `doc/**` - Documentation folder

### Build Status Commands

```bash
# Watch current build (recommended)
gh run watch --exit-status

# List recent builds
gh run list --limit 5

# View specific build
gh run view <run-id>
```

### Deployment Script Commands

| Command | Description |
|---------|-------------|
| `bash auto_deploy.sh` | Full: download → install → start |
| `bash auto_deploy.sh download` | Download latest APK only |
| `bash auto_deploy.sh install` | Install local APK only |
| `bash auto_deploy.sh start` | Start app only |
| `bash auto_deploy.sh logs` | Monitor Bridge logs |
| `bash auto_deploy.sh test` | Test API endpoints |
| `bash auto_deploy.sh quick` | Quick update (use local APK) |

## Privacy & Git Rules

**NEVER commit** (blocked by .gitignore):
- Images (`*.jpg`, `*.png`, etc.)
- Screenshots (`*screenshot*`)
- Secrets (`.env`, `*.secret`, `credentials*.json`)
- Keystores (`*.jks`, `*.keystore`)

## HTTP API Reference (Port 7788)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/ping` | GET | Health check |
| `/health` | GET | Status, uptime, queue_length |
| `/chat_list` | GET | Recent contacts |
| `/chat_history?target=NAME&limit=N` | GET | Message history |
| `/send_message` | POST | Queue message send |
| `/task_status?id=TASK_ID` | GET | Task execution status |
| `/debug/ui_tree` | GET | Dump UI tree (for debugging) |

## Critical Implementation Rules

1. **HTTP layer only enqueues tasks, returns `queued` immediately** - never blocks on UI
2. **TaskQueue must persist** for crash recovery
3. **WakeLock + Keyguard dismiss** before UI operations
4. **Hide keyboard** (`GLOBAL_ACTION_BACK`) before clicking send button
5. **NotificationListener watchdog**: call `requestRebind()` periodically
6. **Safe mode**: after N consecutive failures, stop sending (only monitor)

## Node Location Strategy

Priority order for finding UI nodes (always implement fallbacks - Moxin UI changes between versions):

1. Text match
2. Content-description
3. Resource-id
4. ClassName + index

## Key Constraints

1. **Never block HTTP layer** - always return immediately with task_id
2. **Always use ActionDispatcher** for UI operations
3. **Use relative coordinates** (0.0-1.0) for touch positions
4. **Chinese UI strings** - app targets Chinese WeChat users
5. **No root required** - all via AccessibilityService

## Repository

- **GitHub**: https://github.com/dongaicloud/bridge
- **Releases**: https://github.com/dongaicloud/bridge/releases
- **Actions**: https://github.com/dongaicloud/bridge/actions

## Version Numbering

- Version is automatically incremented by GitHub Actions
- Format: `v1.0.XX` where XX is the build number (run_number)

## Pre-commit Privacy Check (MANDATORY)

**Before every commit, check for sensitive files:**

```bash
# Check staged files
git diff --cached --name-only | grep -E '\.(jpg|png|gif|jpeg|env|secret|private|jks|keystore|db|sqlite)$'

# If found, remove from staging
git reset HEAD <sensitive-file>
```

**Forbidden file types:**
| Type | Extensions |
|------|------------|
| Images | `.jpg`, `.jpeg`, `.png`, `.gif`, `.bmp`, `.webp` |
| Screenshots | `*screenshot*`, `*截图*` |
| Secrets | `.env`, `secrets.*`, `*_private*`, `credentials*.json` |
| Certificates | `.jks`, `.keystore` |
| Databases | `.db`, `.sqlite` |
| Archives | `.zip`, `.rar`, `.7z` |

## Documentation

Detailed specifications in `doc/`:
- `BRIDGE_SPEC.md` - v1 initial spec
- `BRIDGE_SPEC_v2.md` - v2 with engineering review improvements
- `BRIDGE_SPEC_v3.md` - v3 production-ready spec (authoritative)
