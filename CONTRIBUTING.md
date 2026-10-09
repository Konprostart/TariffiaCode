# Contributing to TariffiaCode

Thank you for your interest in contributing!

## Getting Started

1. Fork the repository
2. Create a feature branch: `git checkout -b feat/your-feature`
3. Install the commit-msg hook: `ln -sf ../../scripts/commit-msg-hook.sh .git/hooks/commit-msg`
4. Make your changes
5. Run checks: `./gradlew testGithubDebugUnitTest lintGithubDebug assembleGithubDebug`
6. Commit with a clear message following [Conventional Commits](https://www.conventionalcommits.org/)
7. Push and open a Pull Request against `main`

## Commit Message Convention

All commits must follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`

Examples:
- `feat: add voice input support`
- `fix(chat): resolve SSE reconnection loop`
- `chore: bump dependencies`

CI validates commit messages on all PRs.

## Development Setup

- **JDK 17** (Temurin recommended)
- **Android SDK** (compileSdk/targetSdk 35, minSdk 26)
- **Python 3** (for runtime asset generation)
- Network access on first build (downloads Termux packages)

## Project Structure

```
app/src/main/java/com/konprostart/tariffiacode/
├── core/          # Shared platform abstractions and utilities
├── data/          # API clients, repositories, models
├── di/            # Dependency injection
├── feature/       # Feature-scoped UI and logic
├── runtime/       # On-device PRoot runtime management
├── startup/       # Startup gating and initialization
└── ui/            # Jetpack Compose screens and components
```

## Code Style

- Kotlin with Jetpack Compose
- Follow existing patterns in the codebase
- No comments unless explaining non-obvious logic
- Prefer immutable data classes and sealed interfaces

## Pull Request Guidelines

- Keep PRs focused on a single change
- Include tests for new functionality
- Ensure CI passes (tests, lint, R8 build)
- Update documentation if behavior changes
- Screenshots for UI changes are appreciated

## Reporting Issues

- Use the GitHub issue tracker
- Include device model, Android version, and app version
- For connection issues, note whether you're using local runtime or remote

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
