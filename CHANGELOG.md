# Changelog

All notable changes to this project will be documented in this file.

## [0.10.2](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.10.2) - 2026-11-01

### 🐛 Bug Fixes

- **release:** atomic tag+branch pushes, refuse to bump a branch behind GitHub, and let the iOS job commit Package.swift when main is ahead of the tag ([caa6b92](https://github.com/sorinirimies/arrow-resilience-kit/commit/caa6b92426755c6eebe3a88f6fe703753663a0bd))
- **npm:** no environment access in the package - pin kotlin-logging's startup-banner env read to 'off' (JsLogging disables it anyway); smoke test asserts no shipped .js reads process.env ([1011647](https://github.com/sorinirimies/arrow-resilience-kit/commit/10116471d94afc2492ce95a9686b011a73380fda))

### 📦 Other Changes

- Merge remote-tracking branch 'github/main' ([3bb261c](https://github.com/sorinirimies/arrow-resilience-kit/commit/3bb261c5f37124e122f7adc5f89fb0eaae34ee58))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.10.1...0.10.2

## [0.10.1](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.10.1) - 2026-10-31

### 📚 Documentation

- **npm:** ship a JS/TS README and the LICENSE in the npm package (the npm page and package README were empty); smoke test type-checks every README example against the generated .d.ts ([a178018](https://github.com/sorinirimies/arrow-resilience-kit/commit/a178018a5cc5b3a2d9e4aa80f932f7f6fcff7f28))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.10.0...0.10.1

## [0.10.0](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.10.0) - 2026-10-30

### ✨ Features

- **retry:** retryWithBackoff - capped exponential backoff with per-error delay override (Retry-After), error predicate with retry number, onRetry hook ([6083e01](https://github.com/sorinirimies/arrow-resilience-kit/commit/6083e01f719dbbfdc4effe1be857147fa20ed9f3))
- **js:** reject with the caller's own error, AbortSignal support, silent logging by default (Logging.setLevel/setHandler), Retry.withBackoff, static X.create() ([09330a3](https://github.com/sorinirimies/arrow-resilience-kit/commit/09330a3d92bcded088ceadc1a8f04c358110874f))
- **npm:** ESM entry + exports map (named imports), keep X.Companion.create working, drop unused kotlinx-datetime (removes @js-joda/core) ([e7e2450](https://github.com/sorinirimies/arrow-resilience-kit/commit/e7e2450e6cf85a6de9440591bbdb411391260bd6))
- **scripts:** nightly upgrade updates the version catalog itself (newest stable within the major, kotlin-stdlib kept on 2.2.x) instead of relying on a missing Gradle plugin ([25a00bc](https://github.com/sorinirimies/arrow-resilience-kit/commit/25a00bc674014449b6d52fe694627dc0e0d88d06))

### 🐛 Bug Fixes

- **npm:** clear the distribution directory before packaging so files of removed dependencies can't be published; smoke test checks for unreferenced code ([b2026cd](https://github.com/sorinirimies/arrow-resilience-kit/commit/b2026cdc15c63b05a13f1230d592f1dcd997cb50))
- **js:** run callbacks in the caller's AsyncLocalStorage context (concurrent calls saw the first caller's context) ([64f538d](https://github.com/sorinirimies/arrow-resilience-kit/commit/64f538dbe926e29a44391624b499758ae857e535))
- **scripts:** bump_version renders the new version's changelog section (git-cliff --tag) instead of Unreleased ([47a70e2](https://github.com/sorinirimies/arrow-resilience-kit/commit/47a70e24368dfeb3fa98ea9578b14e36c8fbe832))

### 📚 Documentation

- correct JS/TS and Kotlin examples (positional create, real retry parameter names, LoadingCache.create), document errors, AbortSignal, Retry.withBackoff and logging, fix code fences ([54dd61c](https://github.com/sorinirimies/arrow-resilience-kit/commit/54dd61c736f8c85b59c31e6c50c8a1c692e37b5c))
- **changelog:** readable git-cliff output - ordered sections, scope instead of type prefix, commit links, blank lines, release-automation commits hidden but releases kept; regenerate CHANGELOG (adds 0.9.3) ([f6bca01](https://github.com/sorinirimies/arrow-resilience-kit/commit/f6bca017a228dfca77a2aa30175dfd4e0a9df9e8))

### 🧪 Testing

- **npm:** smoke-test the built npm package like a consumer (ESM/CJS, error identity, abort, logging, .d.ts) in CI, release, quality gate and just test-npm ([fcc85a5](https://github.com/sorinirimies/arrow-resilience-kit/commit/fcc85a5429c49a3dd9f05776fa4c7135cfbfd410))
- **retry:** Kotlin/Native-safe test names (no commas/parentheses) ([b862e64](https://github.com/sorinirimies/arrow-resilience-kit/commit/b862e64e2d95302b04331f9462bfb99b4a9bc9ed))

### ⬆️ Dependencies

- Kotlin 2.4.21, Arrow 2.3.0.1, kotlinx-serialization 1.12.0, Ktor 3.6.1, kotest 6.2.6 and patch updates ([31b3b32](https://github.com/sorinirimies/arrow-resilience-kit/commit/31b3b321b024ce37589d072cc2ef0a8aa203708b))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.9.3...0.10.0

## [0.9.3](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.9.3) - 2026-10-20

_Maintenance release: version bump and generated files only._

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.9.2...0.9.3

## [0.9.2](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.9.2) - 2026-10-20

### 🔄 CI

- stop calling api.github.com (runner can't resolve it); pin/derive Nushell via github.com ([6b15f85](https://github.com/sorinirimies/arrow-resilience-kit/commit/6b15f8590aca0a73ed517a5c0b7d4e42c3dbe654))
- use system node on runner; retry setup-java in npm publish ([30fbcfc](https://github.com/sorinirimies/arrow-resilience-kit/commit/30fbcfc59ce86ff17fa1b5957c4c17b9310a755b))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.9.1...0.9.2

## [0.9.1](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.9.1) - 2026-10-20

### 🔧 Chores

- **justfile:** skip unconfigured remotes in *-all recipes ([05c01e7](https://github.com/sorinirimies/arrow-resilience-kit/commit/05c01e7269f90a41dc23b108b7fa7cd6962d9210))
- **justfile:** push/pull current branch instead of hardcoded main ([666a33c](https://github.com/sorinirimies/arrow-resilience-kit/commit/666a33c15307cdbf8bc9c66bfc1279087def650a))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.9.0...0.9.1

## [0.9.0](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.9.0) - 2026-10-12

### 🔄 CI

- **release:** build docs in temp dir so checkout of main doesn't clash ([0a79f70](https://github.com/sorinirimies/arrow-resilience-kit/commit/0a79f7023b4ce278ebe84a410ac3f85629762627))
- **release:** retry main pushes with rebase on rejection ([362b865](https://github.com/sorinirimies/arrow-resilience-kit/commit/362b86544af1375b6f3f77789ebed41095e3c218))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.8.7...0.9.0

## [0.8.7](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.8.7) - 2026-10-12

### 📦 Build

- publish under io.github.sorinirimies.arrow (verified Central namespace) instead of com.sorinirmies.arrow ([700d6a0](https://github.com/sorinirimies/arrow-resilience-kit/commit/700d6a0e1f59413a6b4f351e7c09156792cd8437))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.8.5...0.8.7

## [0.8.5](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.8.5) - 2026-10-12

### 🔄 CI

- split publish into parallel per-target jobs; bump to 0.8.5 ([b67132f](https://github.com/sorinirimies/arrow-resilience-kit/commit/b67132fad10ebdaae1a27d79907f2313c3297f7c))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.8.4...0.8.5

## [0.8.4](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.8.4) - 2026-10-12

### 🔧 Chores

- bump to 0.8.4 ([46d8f20](https://github.com/sorinirimies/arrow-resilience-kit/commit/46d8f20f6f52e2a1e8fcbe57736a66e0c024829e))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.8.3...0.8.4

## [0.8.3](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.8.3) - 2026-10-12

### 🐛 Bug Fixes

- **publish:** order publish tasks after signing tasks ([a191a11](https://github.com/sorinirimies/arrow-resilience-kit/commit/a191a118e00f4667d97133d3ca1f75b566694530))

### 🔄 CI

- publish to Maven Central using SONATYPE_TOKEN; bump to 0.8.3 ([0eec848](https://github.com/sorinirimies/arrow-resilience-kit/commit/0eec84869b5c97e76e3c35df549f884eb3521e43))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.8.2...0.8.3

## [0.8.2](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.8.2) - 2026-10-12

### ✨ Features

- thread-pool bulkhead, distributed state, Ktor plugin, Spring auto-config, config-driven setup, mingwX64/wasmJs targets ([921c12e](https://github.com/sorinirimies/arrow-resilience-kit/commit/921c12e6add4407bcf3fba16693d98ec48fd65ca))

### 🔧 Chores

- **just:** fail fast on unreachable remotes ([b260115](https://github.com/sorinirimies/arrow-resilience-kit/commit/b260115edea1ed07f7d724025fd98ec104bfc24d))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.8.1...0.8.2

## [0.8.1](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.8.1) - 2026-10-10

### 🐛 Bug Fixes

- decouple publish job from release job + surface Gitea release API errors ([db05e0a](https://github.com/sorinirimies/arrow-resilience-kit/commit/db05e0a8d3fc0d3d8b0eb56cd6e2a26dfc383f88))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.8.0...0.8.1

## [0.8.0](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.8.0) - 2026-10-09

### ✨ Features

- self-healing primitives -- retry budget, sliding-window circuit breaker, failover health probing ([01dbd83](https://github.com/sorinirimies/arrow-resilience-kit/commit/01dbd835a20143769c0e703d8ffce4d0ea09e1b3))
- publish to npm as a real Promise/TypeScript facade ([7ec0ef5](https://github.com/sorinirimies/arrow-resilience-kit/commit/7ec0ef52b1cf8e041eaa49ae5f26f7e77b95bab2))

### 📦 Other Changes

- KDoc gaps, registries for Failover/AdaptiveLimiter/Saga, MicrometerBridge coverage ([2994154](https://github.com/sorinirimies/arrow-resilience-kit/commit/2994154891b16b2afcfb6dda82bf52edfbf1c740))
- Merge remote-tracking branch 'gitea_starscream/main' ([2cce2af](https://github.com/sorinirimies/arrow-resilience-kit/commit/2cce2af4e3115c66a861817a78769ea602cbf16c))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.7.1...0.8.0

## [0.7.1](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.7.1) - 2026-10-08

### 🐛 Bug Fixes

- **ci:** switch JDK distribution from Temurin to Corretto ([dd2d6b2](https://github.com/sorinirimies/arrow-resilience-kit/commit/dd2d6b2745ef2cf82246318de2a4e0454d5976ab))

### 📚 Documentation

- add INTEROP.md (verified Java/Swift/JS usage, fix overclaiming Swift note) ([81e4ada](https://github.com/sorinirimies/arrow-resilience-kit/commit/81e4ada49bb400a79bee72ec208fb0d913f2a61c))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.7.0...0.7.1

## [0.7.0](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.7.0) - 2026-10-02

### ✨ Features

- add Failover pattern; migrate Dokka v1 -> v2 ([15e1b9f](https://github.com/sorinirimies/arrow-resilience-kit/commit/15e1b9f775918e94dfa247d85d06843d9ed1f08c))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.6.1...0.7.0

## [0.6.1](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.6.1) - 2026-09-29

### 🐛 Bug Fixes

- **build:** add JetBrains cache-redirector as a Maven Central fallback ([41c1d7a](https://github.com/sorinirimies/arrow-resilience-kit/commit/41c1d7aa8d67f6e6837e412938c49c7b6b06216c))

### 📈 Improvements

- Add retryIfWithExponentialBackoff, retryIfWithCappedBackoff, and retryIfPolicy ([d7a38db](https://github.com/sorinirimies/arrow-resilience-kit/commit/d7a38db47df7e83cb4ebf08d79ddd4968809e759))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.6.0...0.6.1

## [0.6.0](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.6.0) - 2026-09-29

### 🐛 Bug Fixes

- pin published kotlin-stdlib dependency to 2.2.20, not the 2.4.20 toolchain ([327d1b8](https://github.com/sorinirimies/arrow-resilience-kit/commit/327d1b8ef4e77600ad82a26f99fbfdc0531e79c2))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.9...0.6.0

## [0.5.9](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.5.9) - 2026-09-29

### 🐛 Bug Fixes

- **ci:** update pinned gradle-wrapper.jar checksum for Gradle 9.8.0 ([df967b6](https://github.com/sorinirimies/arrow-resilience-kit/commit/df967b6a1d1e5f954787547c1065377249566023))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.8...0.5.9

## [0.5.8](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.5.8) - 2026-09-29

### 🐛 Bug Fixes

- silence all fixable Gradle/Kotlin build warnings ([15256ce](https://github.com/sorinirimies/arrow-resilience-kit/commit/15256ce7b7067485a739afabd27ef5e4d2449796))
- **gitea-ci:** point RUNNER_TOOL_CACHE at a writable path ([a75b12f](https://github.com/sorinirimies/arrow-resilience-kit/commit/a75b12f7f5542a89abfc0ea9320f0a1664db73c1))

### 📦 Other Changes

- rename Maven groupId and Kotlin package ro.sorinirmies -> com.sorinirmies **[BREAKING]** ([f33a959](https://github.com/sorinirimies/arrow-resilience-kit/commit/f33a959bf9eec72ab6203bf796ac75a712c6b0ec))
- Merge remote-tracking branch 'github/main' ([27e0977](https://github.com/sorinirimies/arrow-resilience-kit/commit/27e097745e15bf98effb5716109fc96ed13940b7))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.7...0.5.8

## [0.5.7](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.5.7) - 2026-09-29

_Maintenance release: version bump and generated files only._

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.6...0.5.7

## [0.5.6](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.5.6) - 2026-09-29

_Maintenance release: version bump and generated files only._

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.5...0.5.6

## [0.5.5](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.5.5) - 2026-09-29

### 🐛 Bug Fixes

- **jitpack:** only publish the JVM variant, skip the JS target entirely ([20d842d](https://github.com/sorinirimies/arrow-resilience-kit/commit/20d842deb7a8d82d37acfec6b90068d340f17a13))

### 📚 Documentation

- clean up README (dep versions, drop macOS x64, fix publishing/project-tree) ([ac1e353](https://github.com/sorinirimies/arrow-resilience-kit/commit/ac1e353109e599ec8619a424ef3263794db23f7b))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.4...0.5.5

## [0.5.4](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.5.4) - 2026-09-29

### ✨ Features

- add AdaptiveLimiter.asPolicy() and hedgePolicy() to Policy combinator ([455ae12](https://github.com/sorinirimies/arrow-resilience-kit/commit/455ae122ba31d806c83e4698c1f82af3e188f5a5))

### 🐛 Bug Fixes

- **justfile:** stop bump from validating tag against pre-bump version ([6d1fd15](https://github.com/sorinirimies/arrow-resilience-kit/commit/6d1fd158938db5fda19d0275b11cc49706635327))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.3...0.5.4

## [0.5.3](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.5.3) - 2026-09-29

### 🐛 Bug Fixes

- **ci:** update Gitea release workflow to publishToCentralPortal ([cd30aa3](https://github.com/sorinirimies/arrow-resilience-kit/commit/cd30aa3469e0348cfdfc39d031979a2957f5bf56))
- sync build.gradle.kts to 0.5.3, and fail-fast on tag/version mismatch ([472604a](https://github.com/sorinirimies/arrow-resilience-kit/commit/472604a2748ecee4d4c151a948be5d8d24e55214))

### 🔧 Chores

- bump dependencies (Arrow 2.2.3, Kotlin 2.4.20) and drop vanniktech publish plugin ([545b2ca](https://github.com/sorinirimies/arrow-resilience-kit/commit/545b2cacae8044567aff1547a55aaa35ada65ae6))

### 📦 Other Changes

- Merge remote-tracking branch 'gitea_starscream/main' ([d7d0afa](https://github.com/sorinirimies/arrow-resilience-kit/commit/d7d0afa325cae19304ca4c8dc1af453fac276321))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.2...0.5.3

## [0.5.2](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.5.2) - 2026-09-25

### 📚 Documentation

- replace hardcoded versions in README/INSTALLATION with dynamic badges ([6d894e4](https://github.com/sorinirimies/arrow-resilience-kit/commit/6d894e4deeab4ef2187ab98847e3adf2f1e4a311))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.1...0.5.2

## [0.5.1](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.5.1) - 2026-09-16

### ✨ Features

- publish to Maven Central, Gitea Packages, and SPM (iOS XCFramework) ([39cc4fc](https://github.com/sorinirimies/arrow-resilience-kit/commit/39cc4fc1a82e726fa90e7e8a7130825b23087d13))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.5.0...0.5.1

## [0.5.0](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.5.0) - 2026-09-15

### ✨ Features

- add Policy combinator, Flow operators, Hedge, AdaptiveLimiter, Chaos, iOS targets, serialization, Micrometer bridge ([d2a8dcd](https://github.com/sorinirimies/arrow-resilience-kit/commit/d2a8dcd420f38c3da254cb464c401ae62faabe06))

### 📚 Documentation

- refresh README/INSTALLATION for 0.4.4 and theme Dokka output ([7d4ca9c](https://github.com/sorinirimies/arrow-resilience-kit/commit/7d4ca9c4782689d7984b0ae66e426a34c4694ec2))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.4.4...0.5.0

## [0.4.4](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.4.4) - 2026-09-14

### 🔄 CI

- retrigger after runner disk cleanup ([842d151](https://github.com/sorinirimies/arrow-resilience-kit/commit/842d151f076e823efd6386e53cd5e78051812fdb))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.4.3...0.4.4

## [0.4.3](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.4.3) - 2026-09-12

### 🐛 Bug Fixes

- **ci:** install nushell fallback in release test job cleanup step ([c5b84fd](https://github.com/sorinirimies/arrow-resilience-kit/commit/c5b84fd3b420725fd508aa5bef610f7fde01f3ae))
- **ci:** pin known-good gradle-wrapper.jar checksum for wrapper-validation ([b99dead](https://github.com/sorinirimies/arrow-resilience-kit/commit/b99dead468da2e0f8a76e5e2f0bb17b718ce44a1))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.4.2...0.4.3

## [0.4.2](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.4.2) - 2026-09-12

### 🐛 Bug Fixes

- **ci:** resolve nushell release asset by exact version instead of unsupported glob ([a119566](https://github.com/sorinirimies/arrow-resilience-kit/commit/a1195660b30dc1331b8eff4db05fe64cd5a19c56))

### 📈 Improvements

- Add .codegraph ignore rules for local data ([7905a4f](https://github.com/sorinirimies/arrow-resilience-kit/commit/7905a4fb2b364c2a5d9cf072f6eafb6c26054f11))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.4.1...0.4.2

## [0.4.1](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.4.1) - 2026-09-11

_Maintenance release: version bump and generated files only._

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/0.4.0...0.4.1

## [0.4.0](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/0.4.0) - 2026-09-11

### ✨ Features

- remove v-prefix from release tags and add automated release + deps-update workflows ([ee6cd42](https://github.com/sorinirimies/arrow-resilience-kit/commit/ee6cd423e2a12cbb0f0c0a1bc00b284978dd2e11))

### 📈 Improvements

- Add Gitea and GitHub Actions workflows and update release process ([b04e43d](https://github.com/sorinirimies/arrow-resilience-kit/commit/b04e43d684ec7829cd34c4841e1e256a3689d5dc))
- Add Nushell scripts, update workflows, and modernize dependencies ([578c112](https://github.com/sorinirimies/arrow-resilience-kit/commit/578c112ceec6fffd387299df3098787dd7c045a6))
- Add robust cleanup script and CI workflow integration ([c48437b](https://github.com/sorinirimies/arrow-resilience-kit/commit/c48437b1fb2792b34bf7b2ce8bac4ca10d3e0af0))
- Add installation guide, improve docs, and expand STM tests ([fd3885d](https://github.com/sorinirimies/arrow-resilience-kit/commit/fd3885dec95e647fde0cc57dcff115353a1516d7))
- Update push/pull scripts to use fixed remote list ([5a30926](https://github.com/sorinirimies/arrow-resilience-kit/commit/5a309268e178c4436deaa285da236aaa055742ec))
- Improve push/pull scripts with clearer output and error checks ([0747c4e](https://github.com/sorinirimies/arrow-resilience-kit/commit/0747c4eeb9cde7b4722c7dcadd57b6272aeb0e3b))
- Update release tasks to push tags and improve output ([4387c15](https://github.com/sorinirimies/arrow-resilience-kit/commit/4387c157f332aedab1992c14457d4681c4b7ba53))

### 📦 Other Changes

- Disable browser tests in JS target for CI compatibility ([245ec38](https://github.com/sorinirimies/arrow-resilience-kit/commit/245ec386f1d8750b52d98e4eabab9bbf465c8449))
- Simplify contributing guide and remove extra docs ([cb5d774](https://github.com/sorinirimies/arrow-resilience-kit/commit/cb5d77476b372d7fb947a58ed69fe8f392631f76))
- Remove v prefix from version tags and update validation logic ([574d53e](https://github.com/sorinirimies/arrow-resilience-kit/commit/574d53edbf132c7e0bc8a6e9059f2da08ed03d9e))
- Remove Maven Central publishing and test results upload ([b5a6ce5](https://github.com/sorinirimies/arrow-resilience-kit/commit/b5a6ce5666d91e3b1f7e2ce641e2a7089d380ec5))
- Refine release/git tasks and add remote setup automation ([7a34640](https://github.com/sorinirimies/arrow-resilience-kit/commit/7a34640c383c63ed719c104f23fe38e1f340e530))
- Use SSH URL for GitHub remote setup ([a530a65](https://github.com/sorinirimies/arrow-resilience-kit/commit/a530a65add2ac3bf8fb2f8e3b5e78dee97a93f50))

### ♻️ Refactor

- Refactor to use STM for concurrency and add create() constructors ([bedf52d](https://github.com/sorinirimies/arrow-resilience-kit/commit/bedf52d9dfc8dd9126a96c33eca3f124892e066b))

### 📚 Documentation

- add note about expected Dokka optimization warnings ([5093a73](https://github.com/sorinirimies/arrow-resilience-kit/commit/5093a73f61b006e5f7a5b2f079ba1df706a5ab5a))
- document Dokka optimization warnings as expected ([71a8085](https://github.com/sorinirimies/arrow-resilience-kit/commit/71a80859f5d9362e888b0b31edd45b25309d060c))

### ⬆️ Dependencies

- upgrade Kotlin toolchain to 2.2.20 and refresh all dependencies ([5e0d6e9](https://github.com/sorinirimies/arrow-resilience-kit/commit/5e0d6e9c30823ce11f95a80341c2a2672ff9f802))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/v0.2.0...0.4.0

## [0.2.0](https://github.com/sorinirimies/arrow-resilience-kit/releases/tag/v0.2.0) - 2025-11-30

### ✨ Features

- initial setup with modular Gradle configuration and GitHub Packages publishing ([90fe45d](https://github.com/sorinirimies/arrow-resilience-kit/commit/90fe45da61b9a73e0cf2d9d9cfd067bda5d1fd30))
- add justfile workflow automation and git-cliff changelog generation ([a24e66f](https://github.com/sorinirimies/arrow-resilience-kit/commit/a24e66feb844e26f6344fbdf8d079dfac691cdee))
- automate GitHub Release creation via justfile ([19149d8](https://github.com/sorinirimies/arrow-resilience-kit/commit/19149d8e0ba65dc325f339b8723f6ee27016b511))

### 🐛 Bug Fixes

- correct TVar initialization for all classes ([27f4abf](https://github.com/sorinirimies/arrow-resilience-kit/commit/27f4abfc973f82b111ca2388bff7acfb1e696ca9))
- comment out missing Arrow repeat imports and re-enable CI builds ([e900759](https://github.com/sorinirimies/arrow-resilience-kit/commit/e900759cae920f2176b8d7d7b9ad23b52a1a3290))
- improve justfile git operations and sync handling ([abc243f](https://github.com/sorinirimies/arrow-resilience-kit/commit/abc243f7b5671fc85f68907807f54485da69c4b6))

### 🔧 Chores

- remove unused listener methods and parameters ([5d7ffb2](https://github.com/sorinirimies/arrow-resilience-kit/commit/5d7ffb26bcd241ed93f094e2ce264c0ff106ca1b))

### 📈 Improvements

- Update CI workflows to use latest actions and simplify config ([529c824](https://github.com/sorinirimies/arrow-resilience-kit/commit/529c824432452edb0804ef50a7e97b217b80810e))
- Add automated documentation deployment and update guides ([f653cbb](https://github.com/sorinirimies/arrow-resilience-kit/commit/f653cbbbd44d9bd0f0d156b3fc00d54668b223b9))
- Add Module.md and update version to 0.1.1 ([39f94b2](https://github.com/sorinirimies/arrow-resilience-kit/commit/39f94b2f901b45cc3f50d3d43259e71bcb6315b4))

### 📦 Other Changes

- Initial commit ([4ac9d1d](https://github.com/sorinirimies/arrow-resilience-kit/commit/4ac9d1de9993d021702939d94b4581d88abf7b90))
- Include gradle-wrapper.jar in version control ([141cd40](https://github.com/sorinirimies/arrow-resilience-kit/commit/141cd409b2b238683f92cf60e993c94240f68b57))
- Remove setup and documentation markdown files ([2cabdc9](https://github.com/sorinirimies/arrow-resilience-kit/commit/2cabdc98b3b0cc641b65aa5f6eafd453569ffcae))

### ♻️ Refactor

- Refactor to use suspend factory methods for resource classes ([3b582b0](https://github.com/sorinirimies/arrow-resilience-kit/commit/3b582b0bc6d15b7e935e75116a9dbf4c5d1829be))

### 📚 Documentation

- add Git dual-remote setup documentation ([56f3634](https://github.com/sorinirimies/arrow-resilience-kit/commit/56f363412f5aa9c2cfe81fdc6f9f3c7b514b8516))
- add badges to README and configure git-cliff changelog ([b675499](https://github.com/sorinirimies/arrow-resilience-kit/commit/b67549986dd6d2650caab5dbe8c6075f2e412f4b))
- add changelog and badges setup documentation ([3a489e2](https://github.com/sorinirimies/arrow-resilience-kit/commit/3a489e2a6c1b4762f8c199358ae426d8abe6c3f0))
- document known compilation issues ([1e8d4c8](https://github.com/sorinirimies/arrow-resilience-kit/commit/1e8d4c89d137d5b1e7457cfbdf58741d5114f0d5))
- add comprehensive compilation status report ([86e9603](https://github.com/sorinirimies/arrow-resilience-kit/commit/86e96030a73084e828ff19c93b59d06411b381ef))
- add comprehensive fix guide for remaining 59 compilation errors ([cc8fa82](https://github.com/sorinirimies/arrow-resilience-kit/commit/cc8fa821595c5638d661deedd673ebe547660c72))
- update API documentation from 39f94b2f901b45cc3f50d3d43259e71bcb6315b4 ([ba4a1f4](https://github.com/sorinirimies/arrow-resilience-kit/commit/ba4a1f43b3018e4f46e4bb6361eb160a21c5f123))
- update API documentation from 5d7ffb26bcd241ed93f094e2ce264c0ff106ca1b ([b35cac0](https://github.com/sorinirimies/arrow-resilience-kit/commit/b35cac09b358e9e764f4150f8d513acb5dd610e8))
- update NEXT_STEPS with completed v0.1.2 release status ([cdc61be](https://github.com/sorinirimies/arrow-resilience-kit/commit/cdc61bebac3a4546b4a603e982c59918f4aeda87))
- add comprehensive release automation guide ([e23409e](https://github.com/sorinirimies/arrow-resilience-kit/commit/e23409e3929fedf85ae5a039ca58013247ebec78))

### 🔄 CI

- add comment for artifact retention period ([8517a67](https://github.com/sorinirimies/arrow-resilience-kit/commit/8517a67e81cd00ce7ff59c2702d134c70bdab7a5))
- disable build/test/publish steps until compilation errors are fixed ([b9ef2d5](https://github.com/sorinirimies/arrow-resilience-kit/commit/b9ef2d523692b2c1fe37aa65359709ba7e64acf4))

**Full Changelog**: https://github.com/sorinirimies/arrow-resilience-kit/compare/v0.1.2...v0.2.0

