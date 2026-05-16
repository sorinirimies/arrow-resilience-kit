# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]
### ♻️ Refactor
- Refactor to use STM for concurrency and add create() constructors
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
### 🔄 Updated
- Update push/pull scripts to use fixed remote list
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
- docs: update API documentation from d25df6bac3b3efd632788ed1e2bdd051b90768dc
- docs: update API documentation from ee2645377b751bdf5a413ecc9e257b8d0d69cf07
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
