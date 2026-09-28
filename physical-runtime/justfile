# tokito-physical-runtime build recipes.
#
# CI calls these rather than inlining commands, so the pipeline definition stays
# a thin caller and `just` locally runs what CI runs.

_default:
    @just --list --unsorted

# Unit tests, lint and a debug APK — what android.yml ran before it moved to
# Buildkite. `validate-wrappers` in the old workflow came from
# gradle/actions/setup-gradle; the equivalent here is the committed wrapper
# checksum, verified by `gradlew` itself.
# Android verification build.
android-verify:
    ./gradlew testDebugUnitTest lintDebug assembleDebug --stacktrace

# Repository governance and evidence contracts.
governance:
    python3 tools/validate_governance.py
    cd tools && python3 -m unittest discover -p 'test_*.py'
