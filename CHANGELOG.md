# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]
### ✨ Features
- feat: add Policy combinator, Flow operators, Hedge, AdaptiveLimiter, Chaos, iOS targets, serialization, Micrometer bridge
### 📚 Documentation
- docs: update API documentation for 0.4.4
- docs: refresh README/INSTALLATION for 0.4.4 and theme Dokka output
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
