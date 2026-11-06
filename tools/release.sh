#!/usr/bin/env bash
set -euo pipefail

export FASTLANE_OPT_OUT_USAGE=1

# Locate project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

# Package name default
PACKAGE_NAME="${PACKAGE_NAME:?provide a package name}"

# AAB path default
DEFAULT_AAB="app/build/outputs/bundle/gmsAutomotiveUniversalRelease/app-gms-automotive-universal-release.aab"
AAB_PATH="${AAB_PATH:-${1:-$DEFAULT_AAB}}"

# Proguard / R8 mapping file (optional but recommended)
DEFAULT_MAPPING="app/build/outputs/mapping/gmsAutomotiveUniversalRelease/mapping.txt"
MAPPING_PATH="${MAPPING_PATH:-$DEFAULT_MAPPING}"

# Track (Google Play uses namespaced tracks for form factors like 'automotive:internal')
TRACK="${TRACK:-automotive:internal}"

# Check for AAB existence
if [[ ! -f "$AAB_PATH" ]]; then
  echo "❌ Error: AAB file not found at: $AAB_PATH"
  echo "👉 Build it first with: mise run bundle"
  exit 1
fi

# Detect Service Account credentials from environment
AUTH_ARGS=()

# 1. Check for file path
KEY_PATH="${SUPPLY_JSON_KEY:-${PLAY_STORE_JSON_KEY:-${GOOGLE_PLAY_KEY_FILE:-${GOOGLE_APPLICATION_CREDENTIALS:-${SA_KEY_FILE:-}}}}}"
KEY_PATH="${KEY_PATH%}" # ensure clean

# 2. Check for raw JSON string
KEY_DATA="${SUPPLY_JSON_KEY_DATA:-${PLAY_STORE_JSON_KEY_DATA:-${GOOGLE_PLAY_KEY_DATA:-${SA_KEY_DATA:-}}}}"

# 3. Check generic SA_TOKEN / PLAY_TOKEN
GENERIC_TOKEN="${SA_TOKEN:-${PLAY_TOKEN:-${GOOGLE_PLAY_KEY:-}}}"
if [[ -n "$GENERIC_TOKEN" ]]; then
  if [[ -f "$GENERIC_TOKEN" ]]; then
    KEY_PATH="$GENERIC_TOKEN"
  elif [[ "$GENERIC_TOKEN" =~ ^[[:space:]]*\{ ]]; then
    KEY_DATA="$GENERIC_TOKEN"
  fi
fi

if [[ -n "$KEY_DATA" ]]; then
  AUTH_ARGS+=(--json_key_data "$KEY_DATA")
elif [[ -n "$KEY_PATH" && -f "$KEY_PATH" ]]; then
  AUTH_ARGS+=(--json_key "$KEY_PATH")
else
  echo "❌ Error: Google Play Service Account token not found in environment."
  echo ""
  echo "Please provide the Service Account credentials using one of the following:"
  echo "  1) As a file path:"
  echo "     export PLAY_STORE_JSON_KEY=\"/path/to/service-account.json\""
  echo "  2) Or as raw JSON content:"
  echo "     export PLAY_STORE_JSON_KEY_DATA='\$(cat /path/to/service-account.json)'"
  echo "     export SA_TOKEN='{...}'"
  echo ""
  exit 1
fi

# Optional mapping file
EXTRA_ARGS=()
if [[ -f "$MAPPING_PATH" ]]; then
  echo "📄 Found mapping file: $MAPPING_PATH"
  EXTRA_ARGS+=(--mapping "$MAPPING_PATH")
fi

echo "🚀 Uploading to Google Play track '$TRACK'..."
echo "📦 Package: $PACKAGE_NAME"
echo "📦 AAB:     $AAB_PATH"

fastlane supply \
  --package_name "$PACKAGE_NAME" \
  --track "$TRACK" \
  --aab "$AAB_PATH" \
  "${AUTH_ARGS[@]}" \
  "${EXTRA_ARGS[@]}" \
  --skip_upload_metadata true \
  --skip_upload_changelogs true \
  --skip_upload_images true \
  --skip_upload_screenshots true

echo "✅ Successfully released to $TRACK track!"
