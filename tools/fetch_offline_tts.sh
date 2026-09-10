#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AAR_DIR="$ROOT/app/libs"
MODEL_DIR="$ROOT/app/src/main/assets/tts/mya"
mkdir -p "$AAR_DIR" "$MODEL_DIR"

SHERPA_VERSION="1.13.7"
SHERPA_AAR="$AAR_DIR/sherpa-onnx-${SHERPA_VERSION}.aar"
SHERPA_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"
SHERPA_SHA256="c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8"

# Immutable snapshot of willwade's Sherpa-compatible conversion of Meta MMS-TTS Burmese.
MODEL_REV="d0f3eabab69a178ad836f5689044998909c88ae7"
MODEL_URL="https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/${MODEL_REV}/mya/model.onnx?download=true"
TOKENS_URL="https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/${MODEL_REV}/mya/tokens.txt?download=true"
MODEL="$MODEL_DIR/model.onnx"
TOKENS="$MODEL_DIR/tokens.txt"

fetch() {
  local url="$1"
  local out="$2"
  local min_bytes="$3"
  if [[ -f "$out" ]] && [[ $(stat -c%s "$out") -ge "$min_bytes" ]]; then
    echo "Using cached $(basename "$out") ($(stat -c%s "$out") bytes)"
    return
  fi
  rm -f "$out.tmp"
  curl -L --fail --retry 5 --retry-delay 2 --connect-timeout 30 \
    --output "$out.tmp" "$url"
  local size
  size=$(stat -c%s "$out.tmp")
  if [[ "$size" -lt "$min_bytes" ]]; then
    echo "Downloaded $(basename "$out") is unexpectedly small: $size bytes" >&2
    exit 1
  fi
  mv "$out.tmp" "$out"
}

fetch "$SHERPA_URL" "$SHERPA_AAR" 40000000
echo "$SHERPA_SHA256  $SHERPA_AAR" | sha256sum -c -

fetch "$MODEL_URL" "$MODEL" 100000000
fetch "$TOKENS_URL" "$TOKENS" 100

echo "Offline Burmese TTS assets ready:"
ls -lh "$SHERPA_AAR" "$MODEL" "$TOKENS"
echo "Model SHA256: $(sha256sum "$MODEL" | awk '{print $1}')"
echo "Tokens SHA256: $(sha256sum "$TOKENS" | awk '{print $1}')"
