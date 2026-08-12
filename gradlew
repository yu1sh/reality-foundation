#!/usr/bin/env sh
# Reproducible Gradle 8.8 launcher. It only accepts a maintainer-supplied
# installation or the official distribution with the checksum pinned in
# gradle.properties; it never follows a moving Gradle version.
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
EXPECTED_VERSION=8.8
EXPECTED_SHA256=a4b4158601f8636cdeeab09bd76afb640030bb5b144aafe261a5e8af027dc612
DIST_URL=https://services.gradle.org/distributions/gradle-8.8-bin.zip

find_gradle() {
    if [ -n "${REALITY_GRADLE_HOME:-}" ] && [ -x "${REALITY_GRADLE_HOME}/bin/gradle" ]; then
        printf '%s\n' "${REALITY_GRADLE_HOME}/bin/gradle"
        return 0
    fi
    if [ -n "${GRADLE_HOME:-}" ] && [ -x "${GRADLE_HOME}/bin/gradle" ]; then
        printf '%s\n' "${GRADLE_HOME}/bin/gradle"
        return 0
    fi
    return 1
}

if GRADLE_BIN=$(find_gradle); then
    VERSION_LINE=$(${GRADLE_BIN} --version 2>/dev/null | awk '/^Gradle / {print $2; exit}')
    if [ "${VERSION_LINE}" != "${EXPECTED_VERSION}" ]; then
        echo "Expected Gradle ${EXPECTED_VERSION}, found ${VERSION_LINE:-unknown}" >&2
        exit 1
    fi
    exec "${GRADLE_BIN}" "$@"
fi

GRADLE_USER_HOME=${GRADLE_USER_HOME:-${XDG_CACHE_HOME:-${ROOT_DIR}/.gradle-user-home}}
DIST_DIR=${GRADLE_USER_HOME}/wrapper/dists/gradle-${EXPECTED_VERSION}-bin
DIST_ZIP=${DIST_DIR}/gradle-${EXPECTED_VERSION}-bin.zip
DIST_HOME=${DIST_DIR}/gradle-${EXPECTED_VERSION}

if [ ! -x "${DIST_HOME}/bin/gradle" ]; then
    mkdir -p "${DIST_DIR}"
    if [ ! -f "${DIST_ZIP}" ]; then
        command -v curl >/dev/null 2>&1 || { echo 'curl is required to bootstrap Gradle 8.8' >&2; exit 1; }
        curl --fail --location --proto '=https' --tlsv1.2 --silent --show-error "${DIST_URL}" --output "${DIST_ZIP}"
    fi
    ACTUAL_SHA256=$(sha256sum "${DIST_ZIP}" | awk '{print $1}')
    if [ "${ACTUAL_SHA256}" != "${EXPECTED_SHA256}" ]; then
        echo "Gradle distribution checksum mismatch" >&2
        exit 1
    fi
    command -v unzip >/dev/null 2>&1 || { echo 'unzip is required to bootstrap Gradle 8.8' >&2; exit 1; }
    TMP_DIR=$(mktemp -d "${DIST_DIR}/extract.XXXXXX")
    trap 'rm -rf "${TMP_DIR}"' EXIT HUP INT TERM
    unzip -q "${DIST_ZIP}" -d "${TMP_DIR}"
    mv "${TMP_DIR}/gradle-${EXPECTED_VERSION}" "${DIST_HOME}"
    rm -rf "${TMP_DIR}"
    trap - EXIT HUP INT TERM
fi

VERSION_LINE=$(${DIST_HOME}/bin/gradle --version 2>/dev/null | awk '/^Gradle / {print $2; exit}')
if [ "${VERSION_LINE}" != "${EXPECTED_VERSION}" ]; then
    echo "Gradle launcher resolved an unexpected version" >&2
    exit 1
fi
exec "${DIST_HOME}/bin/gradle" "$@"
