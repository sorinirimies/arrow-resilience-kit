// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "ArrowResilienceKit",
    platforms: [
        .iOS(.v13),
    ],
    products: [
        .library(name: "ArrowResilienceKit", targets: ["ArrowResilienceKit"]),
    ],
    targets: [
        // Prebuilt Kotlin/Native XCFramework, rebuilt and re-attached to each
        // GitHub Release by the `xcframework` job in
        // .github/workflows/release.yml (macOS-only; Kotlin/Native's iOS
        // targets can only be compiled on a Mac with Xcode installed).
        //
        // url/checksum below are updated automatically for every release by
        // scripts/update_package_swift.nu — do not edit them by hand.
        .binaryTarget(
            name: "ArrowResilienceKit",
            url: "https://github.com/sorinirimies/arrow-resilience-kit/releases/download/0.6.1/ArrowResilienceKit.xcframework.zip",
            checksum: "b769052a39448de31212297595b634775c3128cabb56545d106e9f0baccfae20"
        ),
    ]
)
