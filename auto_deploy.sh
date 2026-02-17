#!/bin/bash
# Bridge APK 自动部署脚本
# 功能：下载最新 APK、安装、启动、监控日志

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 配置
REPO="dongaicloud/bridge"
PACKAGE="com.bridge"
ACTIVITY="com.bridge.MainActivity"
LOCAL_APK="app-debug.apk"
LOG_TAG="BridgeServer"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}    Bridge APK 自动部署脚本${NC}"
echo -e "${BLUE}========================================${NC}"

# 函数：获取最新版本号
get_latest_version() {
    echo -e "${YELLOW}正在获取最新版本信息...${NC}"
    LATEST_RELEASE=$(curl -s "https://api.github.com/repos/$REPO/releases/latest")
    VERSION=$(echo "$LATEST_RELEASE" | grep -o '"tag_name": *"[^"]*"' | sed 's/"tag_name": *"\([^"]*\)"/\1/')
    DOWNLOAD_URL="https://github.com/$REPO/releases/download/$VERSION/app-debug.apk"
    echo "$VERSION|$DOWNLOAD_URL"
}

# 函数：下载 APK
download_apk() {
    local url="$1"
    local version="$2"

    echo -e "${YELLOW}正在下载 $version ...${NC}"
    curl -L -o "$LOCAL_APK" "$url"
    echo -e "${GREEN}下载完成: $LOCAL_APK${NC}"
}

# 函数：检查设备连接
check_device() {
    echo -e "${YELLOW}检查设备连接...${NC}"
    DEVICES=$(adb devices | grep -v "List of devices" | grep -c "device" || true)

    if [ "$DEVICES" -eq 0 ]; then
        echo -e "${RED}错误: 没有检测到已连接的设备${NC}"
        echo -e "${YELLOW}请确保:${NC}"
        echo "  1. 设备已通过 USB 连接"
        echo "  2. 已启用 USB 调试"
        echo "  3. 已授权此电脑进行调试"
        exit 1
    fi

    echo -e "${GREEN}检测到 $DEVICES 个设备${NC}"
    adb devices
}

# 函数：安装 APK
install_apk() {
    echo -e "${YELLOW}正在卸载旧版本...${NC}"
    adb uninstall "$PACKAGE" 2>/dev/null || true

    echo -e "${YELLOW}正在安装新版本...${NC}"
    adb install -r "$LOCAL_APK"

    if [ $? -eq 0 ]; then
        echo -e "${GREEN}安装成功!${NC}"
    else
        echo -e "${RED}安装失败!${NC}"
        exit 1
    fi
}

# 函数：启动应用
start_app() {
    echo -e "${YELLOW}正在启动应用...${NC}"
    adb shell am start -n "$PACKAGE/$ACTIVITY"
    sleep 2
    echo -e "${GREEN}应用已启动${NC}"
}

# 函数：监控日志
monitor_logs() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}    开始监控日志 (Ctrl+C 停止)${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""

    # 清除旧日志
    adb logcat -c

    # 监控 Bridge 相关日志
    adb logcat -v time \
        | grep -E "($LOG_TAG|BridgeAccessibility|BridgeService|WeChatData)" \
        | while read -r line; do
            # 根据日志级别着色
            if echo "$line" | grep -q " ERROR "; then
                echo -e "${RED}$line${NC}"
            elif echo "$line" | grep -q " WARN "; then
                echo -e "${YELLOW}$line${NC}"
            elif echo "$line" | grep -q " DEBUG "; then
                echo -e "${BLUE}$line${NC}"
            else
                echo -e "${GREEN}$line${NC}"
            fi
        done
}

# 函数：测试 API
test_api() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}    测试 API 端点${NC}"
    echo -e "${BLUE}========================================${NC}"

    echo -e "${YELLOW}1. 测试 /ping ...${NC}"
    curl -s http://127.0.0.1:7788/ping | python3 -m json.tool 2>/dev/null || \
    curl -s http://127.0.0.1:7788/ping
    echo ""

    echo -e "${YELLOW}2. 测试 /health ...${NC}"
    curl -s http://127.0.0.1:7788/health | python3 -m json.tool 2>/dev/null || \
    curl -s http://127.0.0.1:7788/health
    echo ""

    echo -e "${YELLOW}3. 测试 /chat_list ...${NC}"
    curl -s http://127.0.0.1:7788/chat_list | python3 -m json.tool 2>/dev/null || \
    curl -s http://127.0.0.1:7788/chat_list
    echo ""

    echo -e "${YELLOW}4. 测试 /chat_history ...${NC}"
    curl -s "http://127.0.0.1:7788/chat_history?target=test" | python3 -m json.tool 2>/dev/null || \
    curl -s "http://127.0.0.1:7788/chat_history?target=test"
    echo ""
}

# 主流程
main() {
    # 解析参数
    ACTION="${1:-full}"

    case "$ACTION" in
        "download")
            INFO=$(get_latest_version)
            VERSION=$(echo "$INFO" | cut -d'|' -f1)
            URL=$(echo "$INFO" | cut -d'|' -f2)
            download_apk "$URL" "$VERSION"
            ;;

        "install")
            check_device
            install_apk
            ;;

        "start")
            start_app
            ;;

        "logs")
            monitor_logs
            ;;

        "test")
            test_api
            ;;

        "quick")
            # 快速更新：不下载，直接安装本地 APK
            check_device
            install_apk
            start_app
            monitor_logs
            ;;

        *)
            # 完整流程
            INFO=$(get_latest_version)
            VERSION=$(echo "$INFO" | cut -d'|' -f1)
            URL=$(echo "$INFO" | cut -d'|' -f2)

            echo -e "${GREEN}最新版本: $VERSION${NC}"
            echo ""

            download_apk "$URL" "$VERSION"
            check_device
            install_apk
            start_app

            echo ""
            echo -e "${GREEN}部署完成!${NC}"
            echo -e "${YELLOW}提示: 使用 'bash $0 logs' 监控日志${NC}"
            echo -e "${YELLOW}提示: 使用 'bash $0 test' 测试 API${NC}"
            ;;
    esac
}

# 显示帮助
show_help() {
    echo "用法: $0 [命令]"
    echo ""
    echo "命令:"
    echo "  (无参数)  - 完整流程：下载、安装、启动"
    echo "  download  - 仅下载最新 APK"
    echo "  install   - 仅安装本地 APK"
    echo "  start     - 仅启动应用"
    echo "  logs      - 监控日志"
    echo "  test      - 测试 API 端点"
    echo "  quick     - 快速更新（使用本地 APK）"
    echo "  help      - 显示帮助信息"
}

# 检查是否需要显示帮助
if [ "$1" = "help" ] || [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    show_help
    exit 0
fi

# 运行主流程
main "$@"
