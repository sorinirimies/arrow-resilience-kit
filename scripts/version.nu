#!/usr/bin/env nu
# Print the current project version from build.gradle.kts

export def get-version []: nothing -> string {
    let content = (open build.gradle.kts)
    $content | lines | where {|l| $l | str contains 'version = "'} | first | str trim | parse 'version = "{version}"' | get version | first
}

def main [] {
    print (get-version)
}
