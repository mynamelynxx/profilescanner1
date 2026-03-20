#!/bin/sh
# Gradle wrapper script
# Download full wrapper from: https://services.gradle.org/distributions/gradle-8.11.1-bin.zip
# Or run: gradle wrapper --gradle-version 8.11.1

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
APP_HOME=$(cd "$(dirname "$0")" && pwd)

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

JAVA_OPTS=""
GRADLE_OPTS=""

exec "$JAVA_HOME/bin/java" $JAVA_OPTS \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"
