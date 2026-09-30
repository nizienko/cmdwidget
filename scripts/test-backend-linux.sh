#!/usr/bin/env bash
set -euo pipefail

# Runs already-built JVM process tests in Linux; no IDE or repository writes in the container.
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"
./gradlew :backend:testClasses

gradle_cache="${GRADLE_USER_HOME:-${HOME}/.gradle}/caches/modules-2/files-2.1"
junit_jar="$(rg --files "$gradle_cache/junit/junit/4.13.2" -g 'junit-4.13.2.jar' | head -n 1)"
hamcrest_jar="$(rg --files "$gradle_cache/org.hamcrest/hamcrest-core/1.3" -g 'hamcrest-core-1.3.jar' | head -n 1)"
platform_lib="$project_root/.intellijPlatform/ides/IU-2026.1.5/lib"
test -f "$junit_jar"
test -f "$hamcrest_jar"
test -d "$platform_lib"

docker_args=()
if [[ -n "${CMDWIDGET_DOCKER_CONTEXT:-}" ]]; then
    docker_args+=(--context "$CMDWIDGET_DOCKER_CONTEXT")
fi
docker "${docker_args[@]}" run --rm --init --network none --read-only \
    --tmpfs /tmp:rw,exec,size=64m \
    -v "$project_root:/workspace:ro" \
    -v "$platform_lib:/platformlib:ro" \
    -v "$junit_jar:/junit.jar:ro" \
    -v "$hamcrest_jar:/hamcrest.jar:ro" \
    "${CMDWIDGET_TEST_IMAGE:-eclipse-temurin:21-jre}" \
    java -cp '/workspace/backend/build/classes/kotlin/main:/workspace/backend/build/classes/kotlin/test:/workspace/shared/build/classes/kotlin/main:/platformlib/*:/junit.jar:/hamcrest.jar' \
    org.junit.runner.JUnitCore \
    com.github.nizienko.cmdwidget.backend.CommandExecutorTest \
    com.github.nizienko.cmdwidget.backend.ProjectWidgetRuntimeTest
