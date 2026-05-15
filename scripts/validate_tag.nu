#!/usr/bin/env nu
# Validate that a tag follows the vX.Y.Z pattern
export def validate [tag: string]: nothing -> record<tag: string, version: string> {
    if not ($tag | str contains 'v') or ($tag | str length) < 6 {
        error make {msg: $"Tag '($tag)' does not match vX.Y.Z pattern"}
    }
    let version = ($tag | str substring 1..)
    let parts = ($version | split row '.')
    if ($parts | length) != 3 {
        error make {msg: $"Tag '($tag)' does not match vX.Y.Z pattern"}
    }
    # Validate each part is numeric
    for part in $parts {
        try {
            $part | into int | ignore
        } catch {
            error make {msg: $"Tag '($tag)' contains non-numeric part: ($part)"}
        }
    }
    {tag: $tag, version: $version}
}

def main [tag: string] {
    let result = (validate $tag)
    print $"tag=($result.tag)"
    print $"version=($result.version)"
}
