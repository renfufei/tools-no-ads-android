#!/bin/bash

# ToolsCNC Android APK Build Script
# Usage:
#   ./build_android.sh          - Build debug APK
#   ./build_android.sh release  - Build release APK
#   ./build_android.sh clean    - Clean and rebuild debug APK

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

BUILD_TYPE="${1:-debug}"

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check Java environment
check_java() {
    if ! command -v java &> /dev/null; then
        log_error "Java not found. Please install JDK 17+."
        exit 1
    fi

    JAVA_VER=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VER" -lt 17 ] 2>/dev/null; then
        log_warn "Java 17+ recommended. Current: $(java -version 2>&1 | head -1)"
    fi
}

# Check Gradle wrapper
check_gradle() {
    if [ -f "$SCRIPT_DIR/gradlew" ]; then
        GRADLE_CMD="$SCRIPT_DIR/gradlew"
        chmod +x "$GRADLE_CMD"
    elif command -v gradle &> /dev/null; then
        GRADLE_CMD="gradle"
    else
        log_error "Neither gradlew nor system gradle found."
        echo ""
        echo "  Install via Homebrew (macOS):"
        echo "    brew install gradle"
        echo ""
        echo "  Or download from: https://gradle.org/install/"
        echo ""
        exit 1
    fi
    log_info "Gradle: $($GRADLE_CMD --version 2>&1 | grep '^Gradle' | head -1)"
}

# Check ANDROID_HOME
check_android_sdk() {
    if [ -z "$ANDROID_HOME" ]; then
        # Try common locations
        if [ -d "$HOME/Library/Android/sdk" ]; then
            export ANDROID_HOME="$HOME/Library/Android/sdk"
        elif [ -d "$HOME/Android/Sdk" ]; then
            export ANDROID_HOME="$HOME/Android/Sdk"
        else
            log_error "ANDROID_HOME not set and SDK not found in default locations."
            log_error "Please set ANDROID_HOME to your Android SDK path."
            exit 1
        fi
    fi
    log_info "ANDROID_HOME: $ANDROID_HOME"
}

# Clean build
clean_build() {
    log_info "Cleaning previous build..."
    $GRADLE_CMD clean
}

# Build APK
build_apk() {
    local type="$1"

    case "$type" in
        debug)
            log_info "Building debug APK..."
            $GRADLE_CMD assembleDebug
            APK_PATH="app/build/outputs/apk/debug/tools-cnc-app-debug.apk"
            ;;
        release)
            log_info "Building release APK..."
            $GRADLE_CMD assembleRelease
            APK_PATH="app/build/outputs/apk/release/tools-cnc-app-release-unsigned.apk"
            ;;
        clean)
            clean_build
            log_info "Building debug APK..."
            $GRADLE_CMD assembleDebug
            APK_PATH="app/build/outputs/apk/debug/tools-cnc-app-debug.apk"
            ;;
        *)
            log_error "Unknown build type: $type"
            echo "Usage: $0 [debug|release|clean]"
            exit 1
            ;;
    esac

    # Check output
    if [ -f "$APK_PATH" ]; then
        APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
        log_info "Build successful!"
        log_info "APK: $APK_PATH ($APK_SIZE)"
    else
        # Release builds with minify may use a different name
        APK_DIR="app/build/outputs/apk/${type}/"
        if [ -d "$APK_DIR" ]; then
            FOUND_APK=$(find "$APK_DIR" -name "*.apk" | head -1)
            if [ -n "$FOUND_APK" ]; then
                APK_SIZE=$(du -h "$FOUND_APK" | cut -f1)
                log_info "Build successful!"
                log_info "APK: $FOUND_APK ($APK_SIZE)"
                return 0
            fi
        fi
        log_error "Build failed. APK not found."
        exit 1
    fi
}

# Main
log_info "=== ToolsCNC Android Build ==="
check_java
check_gradle
check_android_sdk
build_apk "$BUILD_TYPE"
