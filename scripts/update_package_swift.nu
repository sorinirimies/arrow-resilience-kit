#!/usr/bin/env nu
# Update Package.swift's binaryTarget url + checksum for a release tag.
#
# Called by the `xcframework` job in .github/workflows/release.yml after
# building, zipping, and uploading ArrowResilienceKit.xcframework.zip as a
# release asset. Not meant to be run standalone except for local testing.
#
# Usage:
#   nu scripts/update_package_swift.nu --tag 0.5.0 --checksum <sha256>

def main [
    --tag: string     # release tag, e.g. "0.5.0"
    --checksum: string # sha256 checksum from `swift package compute-checksum`
] {
    if ($tag | is-empty) {
        print "❌ --tag is required"
        exit 1
    }
    if ($checksum | is-empty) {
        print "❌ --checksum is required"
        exit 1
    }

    let content = (open Package.swift)
    let updated = (
        $content
        | str replace --regex 'releases/download/[^/]+/ArrowResilienceKit\.xcframework\.zip' $"releases/download/($tag)/ArrowResilienceKit.xcframework.zip"
        | str replace --regex 'checksum: "[a-f0-9]+"' $'checksum: "($checksum)"'
    )
    $updated | save -f Package.swift

    print $"✅ Package.swift updated for ($tag) \(checksum: ($checksum)\)"
}
