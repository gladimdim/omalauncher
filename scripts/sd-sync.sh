#!/bin/sh
# Fork: brings upstream Omalauncher in under the SUPER DESKTOP branch, then builds and tests it.
# main mirrors upstream; superdesktop is rebased onto it. Pushing is left to you: git push --force-with-lease origin superdesktop
set -e
cd "$(dirname "$0")/.."
git fetch upstream
git push origin upstream/main:main
git rebase upstream/main superdesktop
./gradlew :owl:test :app:testDebugUnitTest :superdesktop:testDebugUnitTest :app:assembleDebug
