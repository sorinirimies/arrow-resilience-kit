#!/usr/bin/env nu
# Validate that a tag follows the vX.Y.Z pattern
export def validate [tag: string]: nothing -> record<tag: string, version: string> {
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
    {tag: $tag, version: $tag}
}

def main [tag: string] {
    let result = (validate $tag)
    print $"tag=($result.tag)"
    print $"version=($result.version)"
}
