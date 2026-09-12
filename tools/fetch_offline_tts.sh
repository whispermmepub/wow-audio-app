#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AAR_DIR="$ROOT/app/libs"
MODEL_DIR="$ROOT/app/src/main/assets/tts/mya"
mkdir -p "$AAR_DIR" "$MODEL_DIR"

SHERPA_VERSION="1.13.7"
SHERPA_UPSTREAM_AAR="$AAR_DIR/sherpa-onnx-${SHERPA_VERSION}-upstream.aar"
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

# Sherpa and F5 both use ONNX Runtime. Sherpa v1.13.7 was built against the ORT 1.27 line,
# but its AAR bundles a second libonnxruntime.so. Keep Sherpa's JNI and Java classes while
# removing only that duplicate runtime so the app has one modern ORT shared library supplied
# by the Maven onnxruntime-android dependency used by F5LocalTtsEngine.
fetch "$SHERPA_URL" "$SHERPA_UPSTREAM_AAR" 40000000
echo "$SHERPA_SHA256  $SHERPA_UPSTREAM_AAR" | sha256sum -c -
python3 - "$SHERPA_UPSTREAM_AAR" "$SHERPA_AAR" <<'PY'
import os
import sys
import zipfile

src, dst = sys.argv[1:3]
tmp = dst + '.tmp'
with zipfile.ZipFile(src, 'r') as zin, zipfile.ZipFile(tmp, 'w') as zout:
    removed = []
    kept_sherpa_jni = []
    for info in zin.infolist():
        normalized = info.filename.replace('\\', '/')
        if normalized.endswith('/libonnxruntime.so') or normalized == 'libonnxruntime.so':
            removed.append(normalized)
            continue
        data = zin.read(info.filename)
        zout.writestr(info, data)
        if normalized.endswith('/libsherpa-onnx-jni.so'):
            kept_sherpa_jni.append(normalized)
if not removed:
    raise SystemExit('Expected bundled libonnxruntime.so was not found in Sherpa AAR')
if not kept_sherpa_jni:
    raise SystemExit('Sherpa JNI was unexpectedly missing from repacked AAR')
os.replace(tmp, dst)
print('Removed duplicate ORT:', ', '.join(removed))
print('Kept Sherpa JNI:', ', '.join(kept_sherpa_jni))
PY
if unzip -l "$SHERPA_AAR" | grep -q 'libonnxruntime.so'; then
  echo "Duplicate ONNX Runtime still present in repacked Sherpa AAR" >&2
  exit 1
fi
unzip -l "$SHERPA_AAR" | grep -q 'libsherpa-onnx-jni.so'

fetch "$MODEL_URL" "$MODEL" 100000000
fetch "$TOKENS_URL" "$TOKENS" 100

echo "Offline Burmese TTS assets ready:"
ls -lh "$SHERPA_AAR" "$MODEL" "$TOKENS"
echo "Model SHA256: $(sha256sum "$MODEL" | awk '{print $1}')"
echo "Tokens SHA256: $(sha256sum "$TOKENS" | awk '{print $1}')"
