# Contributing

## Setup

```bash
git clone https://github.com/sorinirimies/arrow-resilience-kit.git
cd arrow-resilience-kit
./gradlew build
```

Requires JDK 17+.

## Build & Test

```bash
just build          # compile all targets
just test           # JVM tests
just test-all       # all platforms
just lint           # detekt
just check-all      # lint + build + test
just doc            # generate Dokka docs
just test-nu        # nushell script tests
```

## Code Style

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- `explicitApi()` is enabled — all public APIs need explicit visibility and return types
- All public APIs must have KDoc

## Submitting Changes

1. Fork the repo and clone it:
   ```bash
   git clone https://github.com/YOUR_USERNAME/arrow-resilience-kit.git
   cd arrow-resilience-kit
   ```
2. Create a feature branch (`feature/...`, `fix/...`)
3. Use [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, etc.)
4. Run `just check-all` before pushing
5. Push to your fork and open a PR against `main`

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
