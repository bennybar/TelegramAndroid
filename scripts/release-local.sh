#!/usr/bin/env bash
# Build a signed arm64 release APK locally and publish it to the release repo,
# where the in-app updater (USER_REPO) picks it up.
# Usage: scripts/release-local.sh
set -euo pipefail

RELEASE_REPO=bennybar/congress_app_placeholder
SECRETS="$HOME/.config/forkclient/release.env"   # APP_ID=... and APP_HASH=...
KEYS="$HOME/StudioProjects/kitzi-android/key.properties"

cd "$(git rev-parse --show-toplevel)"
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"   # AGP/buildSrc reject newer Javas (23, Studio 2026.2's bundled 25)
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="/opt/homebrew/opt/rustup/bin:$PATH"   # rustup's cargo, which has the Android targets

if [ -n "$(git status --porcelain)" ]; then
    echo "Working tree not clean; commit first." >&2
    exit 1
fi
if [ ! -f "$SECRETS" ]; then
    echo "Missing $SECRETS containing APP_ID=... and APP_HASH=..." >&2
    exit 1
fi
source "$SECRETS"

prop() { grep "^$1=" "$KEYS" | head -1 | cut -d= -f2-; }
store="$(prop storeFile)"
[[ "$store" = /* ]] || store="$(dirname "$KEYS")/$store"

# Build number N = highest build already published for this upstream version + 1.
# APP_VERSION_CODE is passed as upstream code * 10, so versionCode is upstream code * 100 + N and N can go to 99.
name=$(grep '^APP_VERSION_NAME=' gradle.properties | cut -d= -f2)
code=$(grep '^APP_VERSION_CODE=' gradle.properties | cut -d= -f2)
last=$(gh release list -R "$RELEASE_REPO" --limit 100 --json tagName --jq "[.[] | .tagName | select(startswith(\"$name.\")) | ltrimstr(\"$name.\") | tonumber] | max // 0")
n=$((last + 1))
if [ "$n" -gt 99 ]; then
    echo "Already 99 releases for $name; wait for the next upstream version." >&2
    exit 1
fi
version="$name.$n"
echo "==> Building $version"

# Passed as Gradle project properties via env, so nothing lands in gradle.properties.
export ORG_GRADLE_PROJECT_APP_ID="$APP_ID"
export ORG_GRADLE_PROJECT_APP_HASH="$APP_HASH"
export ORG_GRADLE_PROJECT_RELEASE_KEYSTORE_FILE="$store"
export ORG_GRADLE_PROJECT_RELEASE_STORE_PASSWORD="$(prop storePassword)"
export ORG_GRADLE_PROJECT_RELEASE_KEY_ALIAS="$(prop keyAlias)"
export ORG_GRADLE_PROJECT_RELEASE_KEY_PASSWORD="$(prop keyPassword)"
export ORG_GRADLE_PROJECT_APP_VERSION_CODE="$((code * 10))"
export ORG_GRADLE_PROJECT_ADDITIONAL_BUILD_NUMBER="$n"
export ORG_GRADLE_PROJECT_USER_REPO="$RELEASE_REPO"
export ORG_GRADLE_PROJECT_CHECK_UPDATES=1

# The injected ABI (IDE mode) also marks the APK testOnly, which installers reject; turn that off.
./gradlew :TMessagesProj_App:assembleAfatRelease -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false

built=$(grep VERSION_NAME TMessagesProj/build/generated/source/buildConfig/release/org/telegram/messenger/BuildConfig.java | cut -d'"' -f 2)
if [ "$built" != "$version" ]; then
    echo "Built version $built does not match expected $version" >&2
    exit 1
fi
apk="$HOME/Downloads/ForkClient_$version.apk"
# With an injected ABI, AGP leaves the APK in intermediates instead of outputs.
cp TMessagesProj_App/build/intermediates/apk/afat/release/app.apk "$apk"
build_tools="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
"$build_tools/apksigner" verify "$apk"
if "$build_tools/aapt2" dump badging "$apk" | grep -q "testOnly='-1'"; then
    echo "APK is marked testOnly; installers will reject it." >&2
    exit 1
fi
echo "==> APK: $apk"

gh release create "$version" "$apk" \
    -R "$RELEASE_REPO" \
    --title "ForkClient $version" \
    --notes "Built from $(git rev-parse HEAD)"
