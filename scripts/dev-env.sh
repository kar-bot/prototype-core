#!/usr/bin/env bash
# Source this file for the cloud machine. Any existing JDK 25 installation is accepted.
prototype_java_version=""
if command -v java >/dev/null 2>&1; then
    prototype_java_version=$(java -version 2>&1 | head -n 1)
fi
if [[ "${prototype_java_version}" != *'version "25'* ]]; then
    export JAVA_HOME=/workspace/.prototype-tools/jdk-25.0.4.1
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="${JAVA_HOME}/bin:${PATH}"; fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/.prototype-tools/gradle-home}"
unset prototype_java_version
