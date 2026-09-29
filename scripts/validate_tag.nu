#!/usr/bin/env nu
# Validate that a tag follows the vX.Y.Z pattern, and that it actually
# matches the version currently in build.gradle.kts at that ref.
#
# The latter check exists because a tag can be pushed (and a bump commit
# made) without build.gradle.kts's own `version = "..."` line actually
# having been updated to match — e.g. a hand-run changelog/commit that
# skipped scripts/bump_version.nu, or a re-tag of an already-released
# version. Left unchecked, the release workflow runs the full quality
# gate and only fails much later, deep in the publish step, with a
# confusing registry-side "409 Conflict" (Maven registries refuse to
# republish an existing version) instead of a clear message naming the
# actual mismatch.
export def validate [tag: string, gradle_version?: string]: nothing -> record<tag: string, version: string> {
    # Only accept bare version numbers like "0.3.0", reject "v0.3.0"
    if ($tag | str starts-with "v") {
        error make {msg: $"Version '($tag)' should not have a 'v' prefix. Use '($tag | str substring 1..)' instead."}
    }
    let parts = ($tag | split row '.')
    if ($parts | length) != 3 {
        error make {msg: $"Version '($tag)' does not match X.Y.Z pattern"}
    }
    for part in $parts {
        try {
            $part | into int | ignore
        } catch {
            error make {msg: $"Version '($tag)' contains non-numeric part: ($part)"}
        }
    }

    # $gradle_version is only ever passed explicitly by tests (to check the
    # comparison logic itself without depending on this checkout's own
    # build.gradle.kts); real callers always hit the `nu scripts/version.nu`
    # branch, reading the live file.
    let actual = ($gradle_version | default (nu scripts/version.nu | str trim))
    if $actual != $tag {
        error make {msg: $"Tag '($tag)' does not match build.gradle.kts's version '($actual)' -- did scripts/bump_version.nu actually run and get committed before this tag was pushed? Publishing would otherwise re-attempt an already-released version and fail with a registry 409 Conflict."}
    }

    {tag: $tag, version: $tag}
}

def main [tag: string] {
    let result = (validate $tag)
    print $"tag=($result.tag)"
    print $"version=($result.version)"
}
