# Bridge APK 自动部署脚本 (Windows PowerShell)
# 功能：下载最新 APK、安装、启动、监控日志

param(
    [string]$Action = "full"
)

# 配置
$REPO = "dongaicloud/bridge"
$PACKAGE = "com.bridge"
$ACTIVITY = "com.bridge.MainActivity"
$LOCAL_APK = "app-debug.apk"

# 颜色函数
function Write-ColorOutput($ForegroundColor) {
    $fc = $host.UI.RawUI.ForegroundColor
    $host.UI.RawUI.ForegroundColor = $ForegroundColor
    if ($args) {
        Write-Output $args
    }
    $host.UI.RawUI.ForegroundColor = $fc
}

function Write-Success { Write-ColorOutput Green $args }
function Write-Info { Write-ColorOutput Cyan $args }
function Write-Warn { Write-ColorOutput Yellow $args }
function Write-Err { Write-ColorOutput Red $args }

# 获取最新版本
function Get-LatestVersion {
    Write-Warn "正在获取最新版本信息..."
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$REPO/releases/latest"
    $version = $release.tag_name
    $downloadUrl = "https://github.com/$REPO/releases/download/$version/app-debug.apk"
    return @{ Version = $version; Url = $downloadUrl }
}

# 下载 APK
function Download-Apk {
    param($Url, $Version)
    Write-Warn "正在下载 $Version ..."
    Invoke-WebRequest -Uri $Url -OutFile $LOCAL_APK -UseBasicParsing
    Write-Success "下载完成: $LOCAL_APK"
}

# 检查设备
function Check-Device {
    Write-Warn "检查设备连接..."
    $devices = adb devices 2>&1

    if ($devices -match "device$" -and $devices -notmatch "List of devices") {
        Write-Success "检测到设备"
        Write-Output $devices
        return $true
    } else {
        Write-Err "错误: 没有检测到已连接的设备"
        Write-Warn "请确保:"
        Write-Output "  1. 设备已通过 USB 连接"
        Write-Output "  2. 已启用 USB 调试"
        Write-Output "  3. 已授权此电脑进行调试"
        return $false
    }
}

# 安装 APK
function Install-Apk {
    Write-Warn "正在卸载旧版本..."
    adb uninstall $PACKAGE 2>$null

    Write-Warn "正在安装新版本..."
    $result = adb install -r $LOCAL_APK 2>&1

    if ($result -match "Success") {
        Write-Success "安装成功!"
        return $true
    } else {
        Write-Err "安装失败: $result"
        return $false
    }
}

# 启动应用
function Start-App {
    Write-Warn "正在启动应用..."
    adb shell am start -n "$PACKAGE/$ACTIVITY"
    Start-Sleep -Seconds 2
    Write-Success "应用已启动"
}

# 监控日志
function Monitor-Logs {
    Write-Info "========================================"
    Write-Info "    开始监控日志 (Ctrl+C 停止)"
    Write-Info "========================================"
    Write-Output ""

    # 清除旧日志
    adb logcat -c

    # 监控日志
    adb logcat -v time BridgeServer:V BridgeAccessibility:V BridgeService:V WeChatData:V *:S
}

# 测试 API
function Test-Api {
    Write-Info "========================================"
    Write-Info "    测试 API 端点"
    Write-Info "========================================"

    $endpoints = @(
        @{ Name = "/ping"; Url = "http://127.0.0.1:7788/ping" },
        @{ Name = "/health"; Url = "http://127.0.0.1:7788/health" },
        @{ Name = "/chat_list"; Url = "http://127.0.0.1:7788/chat_list" },
        @{ Name = "/chat_history"; Url = "http://127.0.0.1:7788/chat_history?target=test" }
    )

    foreach ($ep in $endpoints) {
        Write-Warn "测试 $($ep.Name) ..."
        try {
            $response = Invoke-RestMethod -Uri $ep.Url -UseBasicParsing
            $response | ConvertTo-Json -Depth 10
        } catch {
            Write-Err "请求失败: $_"
        }
        Write-Output ""
    }
}

# 显示帮助
function Show-Help {
    Write-Output "用法: .\auto_deploy.ps1 [-Action <命令>]"
    Write-Output ""
    Write-Output "命令:"
    Write-Output "  full     - 完整流程：下载、安装、启动 (默认)"
    Write-Output "  download - 仅下载最新 APK"
    Write-Output "  install  - 仅安装本地 APK"
    Write-Output "  start    - 仅启动应用"
    Write-Output "  logs     - 监控日志"
    Write-Output "  test     - 测试 API 端点"
    Write-Output "  quick    - 快速更新（使用本地 APK）"
    Write-Output "  help     - 显示帮助信息"
}

# 主流程
function Main {
    Write-Info "========================================"
    Write-Info "    Bridge APK 自动部署脚本"
    Write-Info "========================================"

    switch ($Action) {
        "download" {
            $info = Get-LatestVersion
            Write-Success "最新版本: $($info.Version)"
            Download-Apk -Url $info.Url -Version $info.Version
        }

        "install" {
            if (Check-Device) {
                Install-Apk
            }
        }

        "start" {
            Start-App
        }

        "logs" {
            Monitor-Logs
        }

        "test" {
            Test-Api
        }

        "quick" {
            if (Check-Device) {
                Install-Apk
                Start-App
                Monitor-Logs
            }
        }

        "help" {
            Show-Help
        }

        default {
            $info = Get-LatestVersion
            Write-Success "最新版本: $($info.Version)"

            Download-Apk -Url $info.Url -Version $info.Version

            if (Check-Device) {
                if (Install-Apk) {
                    Start-App
                    Write-Success "部署完成!"
                    Write-Warn "提示: 使用 '.\auto_deploy.ps1 -Action logs' 监控日志"
                    Write-Warn "提示: 使用 '.\auto_deploy.ps1 -Action test' 测试 API"
                }
            }
        }
    }
}

# 运行
Main
