# Known Issues

This document tracks known compilation errors that need to be fixed before the library can be built and published.

## Status: Code Compilation Errors

The project currently has **compilation errors** in the source code that prevent building. These are pre-existing issues in the original code that need to be resolved.

## Critical Issues

### 1. Arrow STM TVar Constructor Access

**Problem:** Arrow's `TVar` constructor is internal and cannot be accessed directly.

**Affected Files:**
- `Cache.kt` (lines 56-60)
- `CircuitBreaker.kt` (lines 55-58)
- `RateLimiter.kt` (lines 54-58, 406-409)
- `TimeLimiter.kt` (lines 48-52)

**Error:**
```
Cannot access '<init>': it is internal in 'TVar'
```

**Solution:** Use Arrow's public STM APIs instead of direct TVar construction:
```kotlin
// Instead of:
val state = TVar(initialValue)

// Use:
val state = atomically { TVar.new(initialValue) }
```

### 2. Platform-Specific @Synchronized Annotation

**Problem:** `@Synchronized` annotation is not available on all Kotlin Multiplatform targets.

**Affected Files:**
- `Cache.kt` (lines 632, 658)
- `CircuitBreaker.kt` (lines 455, 485)
- `RateLimiter.kt` (lines 597, 621)
- `TimeLimiter.kt` (lines 504, 528)

**Error:**
```
Unresolved reference: Synchronized
```

**Solution:** Use expect/actual pattern for platform-specific synchronization:
```kotlin
// In commonMain
expect class Lock()
expect inline fun <T> Lock.withLock(action: () -> T): T

// In jvmMain
actual typealias Lock = java.util.concurrent.locks.ReentrantLock
actual inline fun <T> Lock.withLock(action: () -> T): T = this.withLock(action)
```

Or use Arrow's STM for thread-safety across all platforms.

### 3. Missing Arrow Resilience Imports

**Problem:** Using Arrow resilience functions without proper imports.

**Affected Files:**
- `RetryRepeat.kt` (lines 7-8)

**Error:**
```
Unresolved reference: repeat
Unresolved reference: repeatOrElse
```

**Solution:** Add correct imports:
```kotlin
import arrow.resilience.Schedule
import arrow.resilience.repeat
import arrow.resilience.repeatOrElse
```

### 4. Suspension Function Context Issues

**Problem:** Suspension functions called outside coroutine context.

**Affected Files:**
- `Cache.kt` (line 116)
- `RateLimiter.kt` (lines 65, 188, 415, 423, 475)
- `Saga.kt` (line 430)
- `TimeLimiter.kt` (lines 290, 317)

**Error:**
```
Suspension functions can be called only within coroutine body
```

**Solution:** Wrap in `suspend` function or use `runBlocking`/`coroutineScope`.

### 5. Coroutine async/await Missing

**Problem:** Using `async` and `await` without importing kotlinx.coroutines.

**Affected Files:**
- `Saga.kt` (lines 429, 432)
- `TimeLimiter.kt` (lines 289, 292, 316)

**Error:**
```
Unresolved reference: async
Unresolved reference: await
```

**Solution:** Add import:
```kotlin
import kotlinx.coroutines.async
import kotlinx.coroutines.await
```

### 6. Type Inference Issues

**Problem:** Type mismatches and inference failures.

**Affected Files:**
- `RetryRepeat.kt` (lines 166, 390, 421, 465, 603-607, 655, 677)
- `Saga.kt` (lines 77, 145, 188, 212, 240, 268, 427-432)
- `TimeLimiter.kt` (lines 287-288)

**Errors:**
```
Type mismatch
Not enough information to infer type variable T
```

**Solution:** Add explicit type parameters and fix generic type usage.

### 7. Syntax Errors

**Problem:** Malformed code in Cache.kt

**Affected File:**
- `Cache.kt` (line 659)

**Error:**
```
Expecting a '>'
Function declaration must have a name
```

**Solution:** Fix the syntax error on line 659.

## Error Summary

| Category | Count | Severity |
|----------|-------|----------|
| TVar Constructor Access | ~20 | Critical |
| @Synchronized Missing | ~6 | Critical |
| Missing Imports | ~8 | High |
| Suspension Context | ~8 | High |
| Type Inference | ~15 | High |
| Syntax Errors | ~4 | Critical |

**Total Errors:** ~61 compilation errors

## Workaround for Development

The project is configured to skip compilation errors for now:

```bash
# These commands will fail until issues are fixed:
just build        # Will fail
just test         # Will fail
just publish      # Will fail

# These work without compiling:
just info         # ✓ Works
just version      # ✓ Works
just remotes      # ✓ Works
just changelog    # ✓ Works
```

## Recommended Fix Order

1. **Fix syntax errors** (Cache.kt line 659) - Blocking everything
2. **Fix TVar usage** - Use Arrow's public APIs
3. **Fix @Synchronized** - Use expect/actual or remove
4. **Add missing imports** - Arrow and Coroutines
5. **Fix suspension contexts** - Wrap in proper coroutine scopes
6. **Fix type inference** - Add explicit types
7. **Test each file** - Incrementally verify fixes

## Testing Strategy

Once fixes are applied:

```bash
# Test compilation only
just check

# Test specific source sets
./gradlew compileKotlinJvm
./gradlew compileKotlinJs
./gradlew compileKotlinLinuxX64

# Run tests
just test-jvm
just test-js
just test-linux

# Full build
just build
```

## Impact on Project

- ⚠️ **Cannot build** library artifacts
- ⚠️ **Cannot run** tests
- ⚠️ **Cannot publish** to GitHub Packages
- ✅ **Can manage** project (git, docs, workflows)
- ✅ **Can develop** documentation
- ✅ **Can prepare** infrastructure

## Next Steps

1. **Immediate:** Document all issues (this file) ✓
2. **Short term:** Fix critical errors (syntax, TVar, @Synchronized)
3. **Medium term:** Fix type inference and imports
4. **Long term:** Add comprehensive tests and examples

## References

- [Arrow STM Documentation](https://arrow-kt.io/docs/fx/stm/)
- [Kotlin Multiplatform Expected/Actual](https://kotlinlang.org/docs/multiplatform-connect-to-apis.html)
- [Kotlinx Coroutines](https://kotlinlang.org/docs/coroutines-guide.html)

## Contributing

If you want to help fix these issues:

1. Pick an issue from above
2. Create a branch: `git checkout -b fix/issue-name`
3. Make the fix
4. Test: `just check`
5. Commit: `just commit "fix: description"`
6. Push: `just push`
7. Create PR

See CONTRIBUTING.md for full guidelines.

---

**Status:** Documented - Awaiting fixes
**Priority:** High - Blocks release
**Last Updated:** 2025-01-26