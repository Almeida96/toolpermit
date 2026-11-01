# Contributing

Use JDK 21 and the Gradle wrapper:

```sh
./gradlew test installDist distZip
```

On Windows, use `gradlew.bat`. No model key is needed. Tests use temporary SQLite databases, local HTTP servers, and separate CLI processes. Compiler warnings fail the build. Test and coverage reports appear under `build/reports`.

For a bug report, provide a minimal sanitized policy/request, the command, Java/OS versions, and the expected/observed state. Do not upload a production database, upstream credential, or raw user data. An audit export is safer than an entire database, but review its identifiers before sharing it.

Changes to approval, claims, or expiry need tests that exercise state transitions through the store and broker. Concurrency changes should include separate SQLite connections. A network failure must never be interpreted as permission to replay a side effect. Update the API/policy documentation when changing matching semantics or persisted fields.

Dependencies are pinned in `gradle.lockfile`. To update them, change the build file, run `./gradlew dependencies --write-locks`, and review the resulting lock changes. CI runs on Linux and Windows. Releases are built from a tested main-branch commit, with a downloadable ZIP and checksum.
