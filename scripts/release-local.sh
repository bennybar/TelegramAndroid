#!/usr/bin/env bash
# Build a signed arm64 release APK locally and publish it to the release repo,
# where the in-app updater (USER_REPO) picks it up.
# Usage: scripts/release-local.sh [--no-publish]
set -euo pipefail

RELEASE_REPO=bennybar/congress_app_placeholder
SECRETS="$HOME/.config/forkclient/release.env"   # APP_ID=... and APP_HASH=...
KEYS="$HOME/StudioProjects/kitzi-android/key.properties"

PUBLISH=1
[ "${1:-}" = "--no-publish" ] && PUBLISH=0

cd "$(git rev-parse --show-toplevel)"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # AGP rejects Java 23
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="/opt/homebrew/opt/rustup/bin:$PATH"   # rustup's cargo, which has the Android targets

if [ "$PUBLISH" = 1 ] && [ -n "$(git status --porcelain)" ]; then
    echo "Working tree not clean; commit first (or use --no-publish)." >&2
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

# Build number N = releases already published for this upstream version + 1.
# versionCode is APP_VERSION_CODE * 10 + N, so N must stay within 1..9.
name=$(grep '^APP_VERSION_NAME=' gradle.properties | cut -d= -f2)
count=$(gh release list -R "$RELEASE_REPO" --limit 100 --json tagName --jq "[.[] | select(.tagName | startswith(\"$name.\"))] | length")
n=$((count + 1))
if [ "$n" -gt 9 ]; then
    echo "Already 9 releases for $name; wait for the next upstream version." >&2
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
export ORG_GRADLE_PROJECT_ADDITIONAL_BUILD_NUMBER="$n"
export ORG_GRADLE_PROJECT_USER_REPO="$RELEASE_REPO"
export ORG_GRADLE_PROJECT_CHECK_UPDATES=1

./gradlew :TMessagesProj_App:assembleAfatRelease -Pandroid.injected.build.abi=arm64-v8a

built=$(grep VERSION_NAME TMessagesProj/build/generated/source/buildConfig/release/org/telegram/messenger/BuildConfig.java | cut -d'"' -f 2)
if [ "$built" != "$version" ]; then
    echo "Built version $built does not match expected $version" >&2
    exit 1
fi
apk="$HOME/Downloads/ForkClient_$version.apk"
cp TMessagesProj_App/build/outputs/apk/afat/release/app.apk "$apk"
"$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)/apksigner" verify "$apk"
echo "==> APK: $apk"

if [ "$PUBLISH" = 1 ]; then
    gh release create "$version" "$apk" \
        -R "$RELEASE_REPO" \
        --title "ForkClient $version" \
        --notes "Built from $(git rev-parse HEAD)"
fi
