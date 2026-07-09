# Camera Eyes Build Justfile

# Exported to the environment of every recipe.
export JAVA_HOME := home_directory() / "Android/jdk-17.0.19+10"
export ANDROID_HOME := home_directory() / "Android/Sdk"

# Default: show available recipes
default:
    @just --list

# Install build dependencies: JDK 17 and Android SDK
install-deps:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "Installing build dependencies..."
    mkdir -p "$HOME/Android"

    if [ ! -d "$JAVA_HOME" ]; then
        echo "JDK 17 not found at $JAVA_HOME"
        echo "Download Eclipse Temurin JDK 17 from https://adoptium.net/"
        echo "and extract it to $HOME/Android/"
        exit 1
    else
        echo "JDK 17 already installed at $JAVA_HOME"
    fi

    if [ ! -d "$ANDROID_HOME" ]; then
        echo "Downloading Android SDK command-line tools..."
        cd "$HOME/Android"
        curl -fL -o cmdline-tools.zip \
            "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
        unzip -q cmdline-tools.zip
        mkdir -p "$ANDROID_HOME/cmdline-tools"
        mv cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
        rm cmdline-tools.zip

        SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
        yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true

        echo "Installing SDK packages..."
        "$SDKMANAGER" \
            "platform-tools" \
            "platforms;android-34" \
            "build-tools;34.0.0"
        echo "Android SDK installed at $ANDROID_HOME"
    else
        echo "Android SDK already installed at $ANDROID_HOME"
    fi

    echo "Build dependencies ready."

# Verify the build environment is set up correctly
verify-env:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "Verifying build environment..."
    [ -d "$JAVA_HOME" ] || { echo "ERROR: JDK 17 not found at $JAVA_HOME"; exit 1; }
    echo "JDK 17 found: $JAVA_HOME"
    [ -d "$ANDROID_HOME" ] || { echo "ERROR: Android SDK not found at $ANDROID_HOME"; exit 1; }
    echo "Android SDK found: $ANDROID_HOME"
    [ -f gradlew ] || { echo "ERROR: gradlew not found"; exit 1; }
    echo "Gradle wrapper found"
    echo "Build environment OK."

# Build the debug APK
build-debug:
    ./gradlew assembleDebug
    @echo "Debug APK: app/build/outputs/apk/debug/app-debug.apk"

# Build the release APK
build-release:
    ./gradlew assembleRelease
    @echo "Release APK: app/build/outputs/apk/release/app-release.apk"

# Build and install the debug APK on a connected device
install-debug:
    "$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk

# Launch the app on a connected device
run-debug:
    "$ANDROID_HOME/platform-tools/adb" shell monkey -p com.compuglobal.astralprojector -c android.intent.category.LAUNCHER 1

# List connected Android devices
devices:
    "$ANDROID_HOME/platform-tools/adb" devices -l

# Run unit tests
test:
    ./gradlew test

# Clean build artifacts
clean:
    ./gradlew clean

# Show the build environment
env:
    @echo "JAVA_HOME=$JAVA_HOME"
    @echo "ANDROID_HOME=$ANDROID_HOME"
