#!/usr/bin/env nu
# Create a Gitea release with release notes and assets
def main [tag: string, --gitea-url: string, --repo: string, --token: string] {
    print $"🎉 Creating Gitea release for ($tag)..."

    # Generate release notes
    git-cliff --current --strip header -o RELEASE_NOTES.md
    git-cliff -o CHANGELOG.md

    print "── Release Notes ──"
    open RELEASE_NOTES.md | print

    # Create release via API
    let body = (open RELEASE_NOTES.md)
    let payload = {
        tag_name: $tag,
        name: $tag,
        body: $body,
        draft: false,
        prerelease: false
    }

    let response = (http post
        $"($gitea_url)/api/v1/repos/($repo)/releases"
        $payload
        --content-type application/json
        --headers [Authorization $"token ($token)"]
    )

    let release_id = ($response | get id)
    print $"✅ Release created \(id: ($release_id)\)"

    # Upload assets
    for file in [LICENSE README.md CHANGELOG.md] {
        if ($file | path exists) {
            print $"📦 Uploading ($file)..."
            http post $"($gitea_url)/api/v1/repos/($repo)/releases/($release_id)/assets?name=($file)" (open --raw $file) --content-type application/octet-stream --headers [Authorization $"token ($token)"]
            print $"✅ Uploaded ($file)"
        }
    }

    print $"🎉 Release ($tag) published!"
}
