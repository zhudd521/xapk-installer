#!/bin/sh
# Gradle wrapper for XAPK Installer
DIRNAME="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17}"
export GRADLE_USER_HOME="$HOME/.gradle"
exec "$JAVA_HOME/bin/java" -jar "$DIRNAME/gradle/wrapper/gradle-wrapper.jar" "$@"
