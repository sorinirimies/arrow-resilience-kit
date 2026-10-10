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

    # -e/--allow-errors + -f/--full: capture the full response (status + body)
    # even on a non-2xx status, instead of nu throwing a bare "HTTP Error 422"
    # with no detail -- that's all a prior failure here ever showed, making it
    # undiagnosable from the Actions log alone.
    let response = (http post
        $"($gitea_url)/api/v1/repos/($repo)/releases"
        $payload
        --content-type application/json
        --headers [Authorization $"token ($token)"]
        --allow-errors
        --full
    )

    if $response.status >= 300 {
        print $"❌ Gitea release creation failed \(HTTP ($response.status)\):"
        print ($response.body | to text)
        exit 1
    }

    let release_id = ($response.body | get id)
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
