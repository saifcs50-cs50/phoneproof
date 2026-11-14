#!/bin/bash
# PhoneProof build helper: runs the Gradle wrapper with JDK 21 and the Android SDK on PATH.
# Both variables are respected if you already export them; otherwise the macOS defaults below apply.
export JAVA_HOME="${JAVA_HOME:-/usr/local/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
cd "$(dirname "$0")"
exec ./gradlew "$@"
