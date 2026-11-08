#!/usr/bin/env nu
# Generate self-hosted flat badges into .github/badges/*.svg.
# Served by GitHub itself (relative README paths), so no third-party badge
# service can be slow or return a broken image. Version badges are refreshed
# on every release by scripts/bump_version.nu.

# Approximate text width (px) of Verdana 11px for the flat style.
export def text-width [s: string]: nothing -> int {
    ($s | str length) * 7 + 10
}

export def badge-svg [label: string, value: string, color: string]: nothing -> string {
    let lw = (text-width $label)
    let vw = (text-width $value)
    let w = $lw + $vw
    let lx = ($lw * 5)
    let vx = ($lw * 10 + $vw * 5)
    $"<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"($w)\" height=\"20\" role=\"img\" aria-label=\"($label): ($value)\"><title>($label): ($value)</title><linearGradient id=\"s\" x2=\"0\" y2=\"100%\"><stop offset=\"0\" stop-color=\"#bbb\" stop-opacity=\".1\"/><stop offset=\"1\" stop-opacity=\".1\"/></linearGradient><clipPath id=\"r\"><rect width=\"($w)\" height=\"20\" rx=\"3\" fill=\"#fff\"/></clipPath><g clip-path=\"url\(#r\)\"><rect width=\"($lw)\" height=\"20\" fill=\"#555\"/><rect x=\"($lw)\" width=\"($vw)\" height=\"20\" fill=\"($color)\"/><rect width=\"($w)\" height=\"20\" fill=\"url\(#s\)\"/></g><g fill=\"#fff\" text-anchor=\"middle\" font-family=\"Verdana,Geneva,DejaVu Sans,sans-serif\" text-rendering=\"geometricPrecision\" font-size=\"110\"><text x=\"($lx)\" y=\"150\" fill=\"#010101\" fill-opacity=\".3\" transform=\"scale\(.1\)\">($label)</text><text x=\"($lx)\" y=\"140\" transform=\"scale\(.1\)\">($label)</text><text x=\"($vx)\" y=\"150\" fill=\"#010101\" fill-opacity=\".3\" transform=\"scale\(.1\)\">($value)</text><text x=\"($vx)\" y=\"140\" transform=\"scale\(.1\)\">($value)</text></g></svg>\n"
}

# Major.x of a version from gradle/libs.versions.toml, e.g. "4.1.2" -> "4.x".
export def catalog-major [catalog: string, key: string]: nothing -> string {
    let v = ($catalog | from toml | get versions | get $key)
    $"(($v | split row '.' | first)).x"
}

def main [version?: string] {
    let v = if $version == null { (nu scripts/version.nu | str trim) } else { $version }
    mkdir .github/badges
    let catalog = (open --raw gradle/libs.versions.toml)
    let badges = [
        [file label value color];
        [release "release" $"v($v)" "#007ec6"]
        [maven "Maven Central" $"v($v)" "#007ec6"]
        [npm "npm" $"v($v)" "#cb3837"]
        [license "License" "MIT" "#dfb317"]
        [swift "Swift Package Manager" "compatible" "#F05138"]
        [kmp "Kotlin" "Multiplatform" "#7F52FF"]
        [docs "docs" "API reference" "#007ec6"]
        [spring "Spring Boot" (catalog-major $catalog "spring-boot") "#6DB33F"]
        [redis "Redis (Lettuce)" (catalog-major $catalog "lettuce") "#DC382D"]
    ]
    for b in $badges {
        badge-svg $b.label $b.value $b.color | save -f $".github/badges/($b.file).svg"
    }
    print $"✅ Generated ($badges | length) badges for ($v)"
}
