#!/bin/sh
# Wrapper launcher. If gradle/wrapper/gradle-wrapper.jar is present it is used (normal Gradle wrapper behaviour);
# otherwise the Gradle installed on this machine (8.13+) is used, which is what the GitHub Actions build does as well.
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ -f "$JAR" ]; then
  exec java -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
fi
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
echo "No gradle-wrapper.jar and no 'gradle' on PATH. Install Gradle 8.13+ or open the project in Android Studio (it creates the wrapper)." >&2
exit 1
