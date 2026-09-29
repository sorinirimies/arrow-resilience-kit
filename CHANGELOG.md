# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]
### 🐛 Bug Fixes
- fix: pin published kotlin-stdlib dependency to 2.2.20, not the 2.4.20 toolchain
### 📚 Documentation
- docs: update API documentation for 0.5.9
### 🔧 Chores
- chore: update Package.swift for 0.5.9
## 0.5.9 - 2026-09-29
### 🐛 Bug Fixes
- fix(ci): update pinned gradle-wrapper.jar checksum for Gradle 9.8.0
### 🔧 Chores
- chore: bump version to 0.5.9
**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.8...0.5.9
## 0.5.8 - 2026-09-29
### 🐛 Bug Fixes
- fix: silence all fixable Gradle/Kotlin build warnings
- fix(gitea-ci): point RUNNER_TOOL_CACHE at a writable path
### 📚 Documentation
- docs: update API documentation for 0.5.7
### 📦 Other Changes
- feat!: rename Maven groupId and Kotlin package ro.sorinirmies -> com.sorinirmies
- Merge remote-tracking branch 'github/main'
### 🔧 Chores
- chore: update Package.swift for 0.5.7
- chore: bump version to 0.5.8
**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.7...0.5.8
## 0.5.7 - 2026-09-29
### 🔧 Chores
- chore: bump version to 0.5.7
**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.6...0.5.7
## 0.5.6 - 2026-09-29
### 🔧 Chores
- chore: bump version to 0.5.6
**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.5...0.5.6
## 0.5.5 - 2026-09-29
### 🐛 Bug Fixes
- fix(jitpack): only publish the JVM variant, skip the JS target entirely
### 📚 Documentation
- docs: clean up README (dep versions, drop macOS x64, fix publishing/project-tree)
- docs: update API documentation for 0.5.4
### 🔧 Chores
- chore: update Package.swift for 0.5.4
- chore: bump version to 0.5.5
**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.4...0.5.5
## 0.5.4 - 2026-09-29
### ✨ Features
- feat: add AdaptiveLimiter.asPolicy() and hedgePolicy() to Policy combinator
### 🐛 Bug Fixes
- fix(justfile): stop bump from validating tag against pre-bump version
### 🔧 Chores
- chore: bump version to 0.5.4
**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.3...0.5.4
## 0.5.3 - 2026-09-29
### 🐛 Bug Fixes
- fix(ci): update Gitea release workflow to publishToCentralPortal
- fix: sync build.gradle.kts to 0.5.3, and fail-fast on tag/version mismatch
### 📚 Documentation
- docs: update API documentation for 0.5.2
### 📦 Other Changes
- Merge remote-tracking branch 'gitea_starscream/main'
### 🔧 Chores
- chore: update Package.swift for 0.5.2
- chore: bump dependencies (Arrow 2.2.3, Kotlin 2.4.20) and drop vanniktech publish plugin
- chore: bump version to 0.5.1
- chore: bump version to 0.5.3
**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.2...0.5.3
## 0.5.2 - 2026-09-25
### 📚 Documentation
- docs: update API documentation for 0.5.1
- docs: replace hardcoded versions in README/INSTALLATION with dynamic badges
### 🔧 Chores
- chore: update Package.swift for 0.5.1
- chore: bump version to 0.5.2
**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.1...0.5.2
## 0.5.1 - 2026-09-16
### ✨ Features
- feat: publish to Maven Central, Gitea Packages, and SPM (iOS XCFramework)
### 📚 Documentation
- docs: update API documentation for 0.5.0
### 🔧 Chores
- chore: bump version to 0.5.1
**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.0...0.5.1
## 0.5.0 - 2026-09-15
### ✨ Features
- feat: add Policy combinator, Flow operators, Hedge, AdaptiveLimiter, Chaos, iOS targets, serialization, Micrometer bridge
### 📚 Documentation
- docs: update API documentation for 0.4.4
- docs: refresh README/INSTALLATION for 0.4.4 and theme Dokka output
### 🔧 Chores
- chore: bump version to 0.5.0
**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.4.4...0.5.0
## 0.4.4 - 2026-09-14
### 🔄 CI
- ci: retrigger after runner disk cleanup
### 🔧 Chores
- chore: bump version to 0.4.4
**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.4.3...0.4.4
## 0.4.3 - 2026-09-12
### 🐛 Bug Fixes
- fix(ci): install nushell fallback in release test job cleanup step
- fix(ci): pin known-good gradle-wrapper.jar checksum for wrapper-validation
### 🔧 Chores
- chore: bump version to 0.4.3
**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.4.2...0.4.3
## 0.4.2 - 2026-09-12
### ➕ Added
- Add .codegraph ignore rules for local data
### 🐛 Bug Fixes
- fix(ci): resolve nushell release asset by exact version instead of unsupported glob
### 🔧 Chores
- chore: bump version to 0.4.2
**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.4.1...0.4.2
## 0.4.1 - 2026-09-11
### 🔧 Chores
- chore: bump version to 0.4.1
**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.4.0...0.4.1
## 0.4.0 - 2026-09-11
### ♻️ Refactor
- Refactor to use STM for concurrency and add create() constructors
### ✨ Features
- feat: remove v-prefix from release tags and add automated release + deps-update workflows
### ➕ Added
- Add Gitea and GitHub Actions workflows and update release process
- Add Nushell scripts, update workflows, and modernize dependencies
- Add robust cleanup script and CI workflow integration
- Add installation guide, improve docs, and expand STM tests
### 📈 Improvements
- Improve push/pull scripts with clearer output and error checks
### 📚 Documentation
- docs: add note about expected Dokka optimization warnings
- docs: document Dokka optimization warnings as expected
### 📦 Other Changes
- Disable browser tests in JS target for CI compatibility
- Simplify contributing guide and remove extra docs
- Remove v prefix from version tags and update validation logic
- Remove Maven Central publishing and test results upload
- Refine release/git tasks and add remote setup automation
- Use SSH URL for GitHub remote setup
### 🔄 Updated
- Update push/pull scripts to use fixed remote list
- Update release tasks to push tags and improve output
### 🔧 Chores
- chore: bump version to 0.3.1
- chore: bump version to 0.4.0
**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/v0.2.0...0.4.0
## 0.2.0 - 2025-11-30
### ♻️ Refactor
- Refactor to use suspend factory methods for resource classes
### ✨ Features
- feat: initial setup with modular Gradle configuration and GitHub Packages publishing
- feat: add justfile workflow automation and git-cliff changelog generation
- feat: automate GitHub Release creation via justfile
### ➕ Added
- Add automated documentation deployment and update guides
- Add Module.md and update version to 0.1.1
### 🐛 Bug Fixes
- fix: correct TVar initialization for all classes
- fix: comment out missing Arrow repeat imports and re-enable CI builds
- fix: improve justfile git operations and sync handling
### 📚 Documentation
- docs: add Git dual-remote setup documentation
- docs: add badges to README and configure git-cliff changelog
- docs: add changelog and badges setup documentation
- docs: document known compilation issues
- docs: add comprehensive compilation status report
- docs: add comprehensive fix guide for remaining 59 compilation errors
- docs: update API documentation from 39f94b2f901b45cc3f50d3d43259e71bcb6315b4
- docs: update API documentation from 5d7ffb26bcd241ed93f094e2ce264c0ff106ca1b
- docs: update NEXT_STEPS with completed v0.1.2 release status
- docs: add comprehensive release automation guide
### 📦 Other Changes
- Initial commit
- Include gradle-wrapper.jar in version control
- Remove setup and documentation markdown files
### 🔄 CI
- ci: add comment for artifact retention period
- ci: disable build/test/publish steps until compilation errors are fixed
### 🔄 Updated
- Update CI workflows to use latest actions and simplify config
### 🔧 Chores
- chore: bump version to 0.1.2
- chore: remove unused listener methods and parameters
- chore: bump version to 0.2.0
**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/v0.1.2...v0.2.0
