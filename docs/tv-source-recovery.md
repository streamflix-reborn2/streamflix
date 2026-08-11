# TV playback source recovery checks

The recovery policy and fixture canaries are local JVM tests and use no network:

```sh
./gradlew :app:testDebugUnitTest
```

With an Android TV device or emulator connected, run the Media3 failover canary:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.streamflixreborn.streamflix.fragments.player.TvPlaybackRecoveryInstrumentedCanaryTest
```

The canary serves a bundled three-second MP4 on device loopback, forces two HTTP 404 player
failures on the first candidate, verifies one retry and failover, then requires the second candidate
to advance through actual Media3 playback. It performs no external provider or media request.

Lint and each debug layout are configured through `APP_LAYOUT` in `local.properties`. Run each
layout in a fresh Gradle invocation after setting that property:

```sh
# APP_LAYOUT= (generic manifest)
./gradlew :app:lintDebug :app:assembleDebug

# APP_LAYOUT=mobile
./gradlew --no-configuration-cache :app:assembleDebug

# APP_LAYOUT=tv
./gradlew --no-configuration-cache :app:assembleDebug
```

`local.properties` must also define the existing API build fields. Local and CI builds require
`app/src/main/cpp/native-lib.cpp`; CI generates it from the checked-in template with inert values.
`app/lint-baseline.xml` contains only the six errors already present on the rebased `main`; new lint
errors still fail CI. No live provider request is part of the default test task.
