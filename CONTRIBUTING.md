# Contributing

Thanks for helping with ultimateVE. Read [CLAUDE.md](CLAUDE.md) first: it holds the engineering rules (UI/engine
isolation, integer frame time, MVI, explicit errors) that every change must follow. Everything (code, comments,
commits, docs) is in English.

## Branches and pull requests

- `master` is the main branch. Never commit to it directly.
- One pull request per feature or fix, from a branch named `feat/<topic>`, `fix/<topic>` or `chore/<topic>`.
- Keep a PR focused. If it touches the native engine, the JNI layer, the domain and the UI, say so in the description.
- CI must pass (unit tests, debug build, native host tests). Do not mark a PR ready with failing checks.
- Say plainly in the PR what was and was not verified, especially on a real device.
- Record non-obvious decisions (what, why, the alternative) in [DECISIONS.md](DECISIONS.md), append-only.
- Update [PLAN.md](PLAN.md) and [SPECS.md](SPECS.md) when behaviour or decisions change; tick a box only when it is truly done.

## Tests

- Every clip-manipulation feature (split, move, overwrite, ripple, trim, insert, delete, speed, keyframes) needs unit tests
  for collisions, gaps and boundary frames, written together with the operation. The `domain/` package is pure Kotlin so
  they run on the JVM without a device.
- Pure-logic C++ gets a host test in `app/src/main/cpp/tests/` (add it to that directory's `CMakeLists.txt`).
- Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` and `scripts/run-native-tests.sh` before pushing.
- Do not run `connectedDebugAndroidTest` on a phone holding data you care about: it clears the app's data.

## Style

- Kotlin: strict typing, no `!!`, no empty `catch`, explicit error types. Match the surrounding code.
- C++: C++20, RAII, no owning raw pointers, builds with `-Wall -Wextra -Werror`.
- Timeline positions are integer `FrameIndex` values; never floating-point seconds.
- Compose never draws timeline clips or touches decoding; only `engine/EngineClient` calls JNI.

## Commits

Short imperative subject, a body explaining why when it is not obvious. Stage specific files rather than `git add -A`;
never commit `.claude/worktrees/`, build output or local settings.

## Licence

By contributing you agree that your work is released under GPL-3.0 (see [LICENSE](LICENSE)). When adding a dependency,
check that its licence is compatible and update [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
