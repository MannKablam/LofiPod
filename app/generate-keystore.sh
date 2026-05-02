#!/usr/bin/env bash
# Generate the stable LofiPod sideload signing key. Run this ONCE per repo
# clone — the resulting `lofipod-dev.jks` should be committed so every machine
# signs builds with the same cert and Android will let updates install over
# previous versions without an uninstall.
#
# Requires `keytool` on PATH. Easiest way to get it on Windows:
#   - Open Android Studio's "Terminal" tab (it inherits the bundled JDK), OR
#   - Add %ANDROID_STUDIO%\jbr\bin to PATH (path varies per install), OR
#   - Install JDK 17+ from adoptium.net
#
# Usage: cd app && bash generate-keystore.sh

set -euo pipefail

KEYSTORE="$(dirname "$0")/lofipod-dev.jks"

if [ -f "$KEYSTORE" ]; then
    echo "Keystore already exists at $KEYSTORE — nothing to do."
    echo "Delete it first if you want to regenerate (this will break update-in-place for installed APKs)."
    exit 0
fi

keytool -genkeypair \
    -v \
    -keystore "$KEYSTORE" \
    -storetype PKCS12 \
    -alias lofipod \
    -keyalg RSA \
    -keysize 2048 \
    -validity 36500 \
    -storepass lofipod \
    -keypass lofipod \
    -dname "CN=LofiPod Dev, OU=Sideload, O=LofiPod, L=Local, ST=Local, C=US"

echo
echo "Keystore created at $KEYSTORE"
echo "Now commit it: git add app/lofipod-dev.jks && git commit -m 'Add stable sideload signing key'"
