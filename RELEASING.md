# Releasing

```bash
just release 1.2.3
```

Bumps the version, runs the quality gate, regenerates `CHANGELOG.md` (git-cliff), commits, tags `1.2.3` (no `v` prefix) and pushes the branch and the tag to GitHub in one atomic push. It refuses to start when the branch is behind GitHub: pull first (the release job commits `Package.swift` and the API docs back to `main`). `just push-release-all` also pushes to the Gitea remotes.

The tag starts the release workflow, which tests, publishes to every target below, attaches the iOS XCFramework, updates `Package.swift` and the API docs, and creates the GitHub release from the changelog.

## Targets

| Target | Workflow | Needs |
|---|---|---|
| Maven Central | GitHub and Gitea | `SONATYPE_TOKEN` (Central Portal token), `GPG_SIGNING_KEY`, `GPG_PASSPHRASE`; the `io.github.sorinirimies` namespace is verified once at [central.sonatype.com](https://central.sonatype.com/). Uploaded by `./gradlew publishToCentralPortal` ([central-portal.gradle.kts](gradle/central-portal.gradle.kts)), no plugin. |
| GitHub Packages | GitHub and Gitea | `PACKAGES_PUBLISH` (PAT with `write:packages`); on Gitea it must be set as a Gitea secret too. |
| npm | GitHub and Gitea | `NPM_TOKEN`. Publishes the JS/TS facade (`src/jsMain/kotlin/`) from `build/dist/js/productionLibrary`, after `scripts/npm_smoke/run.mjs` passes. Its README is `src/jsMain/npm/README.md`. |
| Swift Package Manager | GitHub only (macOS runner) | Nothing. Builds `ArrowResilienceKit.xcframework`, attaches it to the release and commits the new checksum to `Package.swift`. |
| Gitea Packages | Gitea only (LAN) | `GITEA_PACKAGES_TOKEN`. |
| JitPack | none | Builds on demand from any GitHub tag. |
