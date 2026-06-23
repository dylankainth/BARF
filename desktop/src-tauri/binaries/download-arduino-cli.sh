#!/usr/bin/env bash
# Download arduino-cli for the current platform and place it in the binaries/ directory.
set -euo pipefail

VERSION="1.1.1"
BIN_DIR="$(cd "$(dirname "$0")" && pwd)"

OS="$(uname -s)"
ARCH="$(uname -m)"

case "$OS" in
  Linux)
    case "$ARCH" in
      x86_64) FILE="arduino-cli_${VERSION}_Linux_64bit.tar.gz" ;;
      aarch64) FILE="arduino-cli_${VERSION}_Linux_ARM64.tar.gz" ;;
      *) echo "Unsupported arch: $ARCH"; exit 1 ;;
    esac
    ;;
  Darwin)
    case "$ARCH" in
      x86_64) FILE="arduino-cli_${VERSION}_macOS_64bit.tar.gz" ;;
      arm64)  FILE="arduino-cli_${VERSION}_macOS_ARM64.tar.gz" ;;
      *) echo "Unsupported arch: $ARCH"; exit 1 ;;
    esac
    ;;
  *) echo "Unsupported OS: $OS"; exit 1 ;;
esac

URL="https://github.com/arduino/arduino-cli/releases/download/${VERSION}/${FILE}"

echo "Downloading ${URL}..."
curl -sL "$URL" -o "/tmp/${FILE}"
tar -xzf "/tmp/${FILE}" -C "$BIN_DIR"
echo "Installed arduino-cli to ${BIN_DIR}/arduino-cli"
