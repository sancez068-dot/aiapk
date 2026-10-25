#!/bin/sh
APP_HOME="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)" || exit 1
exec java ${JAVA_OPTS:-} -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
