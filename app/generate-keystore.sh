#!/usr/bin/env bash
# Generate the stable LofiPod sideload signing key. Run this ONCE per repo
# clone — the resulting `lofipod-dev.jks` should be committed so every machine
# signs builds with the same cert and Android will let updates install over
# previous versions without an uninstall.
#
# Requires `keytool` on PATH and the LOFIPOD_KEYSTORE_PASSWORD env var set
# to a strong password (save it to a password manager — you'll need to
# inject it into every CI build via a GitHub Actions secret).
#
# Easiest way to get keytool on Windows:
#   - Open Android Studio's "Terminal" tab (it inherits the bundled JDK), OR
#   - Add %ANDROID_STUDIO%\jbr\bin to PATH (path varies per install), OR
#   - Install JDK 17+ from adoptium.net
#
# Usage:
#   export LOFIPOD_KEYSTORE_PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)"
#   cd app && bash generate-keystore.sh
#
# (CI invokes this from .github/workflows/bootstrap-keystore.yml with a
# generated password injected via env, then uploads the keystore + password
# as separate artifacts.)

set -euo pipefail

KEYSTORE="$(dirname "$0")/lofipod-dev.jks"

if [ -f "$KEYSTORE" ]; then
    echo "Keystore already exists at $KEYSTORE — nothing to do."
    echo "Delete it first if you want to regenerate (this will break update-in-place for installed APKs)."
    exit 0
fi

# Password must come from the env. Refuse to use a default — silently
# baking a weak password into the cert would undermine the whole point of
# moving the password out of the repo.
if [ -z "${LOFIPOD_KEYSTORE_PASSWORD:-}" ]; then
    echo "ERROR: LOFIPOD_KEYSTORE_PASSWORD env var is required." >&2
    echo "  Generate one, e.g.:" >&2
    echo "    export LOFIPOD_KEYSTORE_PASSWORD=\"\$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)\"" >&2
    echo "  Save it to your password manager, then re-run this script." >&2
    exit 1
fi

keytool -genkeypair \
    -v \
    -keystore "$KEYSTORE" \
    -storetype PKCS12 \
    -alias lofipod \
    -keyalg RSA \
    -keysize 2048 \
    -validity 36500 \
    -storepass "$LOFIPOD_KEYSTORE_PASSWORD" \
    -keypass "$LOFIPOD_KEYSTORE_PASSWORD" \
    -dname "CN=LofiPod Dev, OU=Sideload, O=LofiPod, L=Local, ST=Local, C=US"

echo
echo "Keystore created at $KEYSTORE"
echo "Save your LOFIPOD_KEYSTORE_PASSWORD to your password manager NOW."
echo "Then add it as a repo secret named LOFIPOD_KEYSTORE_PASSWORD"
echo "(GitHub repo Settings → Secrets and variables → Actions → New repository secret),"
echo "then commit the keystore: git add app/lofipod-dev.jks"
