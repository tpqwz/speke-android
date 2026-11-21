#!/usr/bin/env bash
# Regenerates SignalProtos.java, StorageProtos.java and FingerprintProtos.java from
# src/main/proto with protoc 25.9, the compiler that matches the protobuf-java 3.25.9 runtime.
#
# The protoc binary is downloaded from Maven Central and checked against a pinned SHA-256
# (cross-checked once against the protocolbuffers/protobuf v25.9 GitHub release, same bytes).
# Linux x86_64 only; on other hosts put a verified protoc 25.9 on PATH as PROTOC=/path/to/protoc.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
version=3.25.9
sha256=277c8548ead748ac3e600d3b860f114d36a1311dff29568cd95115ad65f59e2f
url="https://repo1.maven.org/maven2/com/google/protobuf/protoc/${version}/protoc-${version}-linux-x86_64.exe"

if [[ -z "${PROTOC:-}" ]]; then
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    curl -sSfL -o "$tmp/protoc" "$url"
    echo "${sha256}  $tmp/protoc" | sha256sum -c --quiet -
    chmod +x "$tmp/protoc"
    PROTOC="$tmp/protoc"
fi

actual="$("$PROTOC" --version)"
if [[ "$actual" != "libprotoc 25.9" ]]; then
    echo "expected libprotoc 25.9, got: $actual" >&2
    exit 1
fi

"$PROTOC" \
    --proto_path="$here/src/main/proto" \
    --java_out="$here/src/main/java" \
    "$here"/src/main/proto/*.proto

echo "Regenerated with $actual"
