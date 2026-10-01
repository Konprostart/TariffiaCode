# CI

The Android debug APK workflow is intentionally opt-in because a full Android build is expensive.

Add the `build-apk` label to a pull request to run the workflow. It uploads the debug APK as a
14-day artifact and adds or updates a download link in the pull request. New commits do not rerun
the build automatically; remove and re-add the label when a fresh APK is needed.
