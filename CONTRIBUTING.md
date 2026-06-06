# Contributing to Crisix

## Pull Requests

1. Fork the repo and create your branch from `main`
2. Run tests before submitting: `./gradlew testDebug`
3. Update docs if you change public API or transport behaviour
4. Keep commits small and use [Conventional Commits](https://www.conventionalcommits.org/)

## Code Style

- Kotlin, no `!!` unless absolutely necessary
- No comments in code (the code should be self-documenting)
- Format with project conventions (`.editorconfig` enforced)

## Commit Messages

```
<type>: <short description>

<optional body>
```

Types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`.

## Tests

- Unit tests: `./gradlew testDebug`
- Instrumented tests: `./gradlew connectedDebugAndroidTest`
- All pre-existing test failures are documented; don't introduce new ones.

## E2EE

Never log key material. X3DH + Double Ratchet sessions must remain forward-secret.
