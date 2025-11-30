
## Dokka Optimization Warnings

### Symptom
When running `./gradlew dokkaHtml`, you see warnings like:
```
Execution optimizations have been disabled for task ':dokkaHtml' to ensure correctness...
Type 'java.net.URL' is not supported on properties annotated with @Input...
```

### Explanation
These are **informational warnings** from Gradle's build cache system, not errors in your code.

The Dokka plugin uses `java.net.URL` types internally for configuration, which Gradle cannot reliably cache. As a result, Gradle disables certain optimizations for the Dokka task to ensure correctness.

### Impact
- ✅ Documentation builds successfully
- ✅ Output is correct and complete
- ⚠️ Dokka task cannot use Gradle's build cache
- ⚠️ Documentation generation might be slightly slower

### Resolution
**None needed.** These warnings are expected and harmless.

This is a known issue in the Dokka plugin:
- GitHub Issue: https://github.com/Kotlin/dokka/issues/1933
- The Dokka team is aware and working on it
- Many projects see these same warnings

### What You Should Do
**Ignore these warnings.** They don't indicate any problem with your project.

Focus on:
- ✅ `BUILD SUCCESSFUL` - The important indicator
- ✅ Documentation output in `build/docs/`
- ✅ No actual compilation errors

### If Documentation Build Fails
These warnings are NOT the cause. Look for actual error messages like:
- `BUILD FAILED`
- `Compilation error`
- `Wrong AST Tree`
- `Unexpected classifier`

Those indicate real problems that need fixing.
