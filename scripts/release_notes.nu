#!/usr/bin/env nu
# Generate RELEASE_NOTES.md and CHANGELOG.md for a release
def main [tag: string] {
    print $"▸ Generating release notes for ($tag)..."

    run-external "git-cliff" "--current" "--strip" "header" "-o" "RELEASE_NOTES.md"
    run-external "git-cliff" "-o" "CHANGELOG.md"

    print "── Release Notes ──"
    open RELEASE_NOTES.md | print
    print "✅ Release notes generated"
}
