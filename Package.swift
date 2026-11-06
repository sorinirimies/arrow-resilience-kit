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
            url: "https://github.com/sorinirimies/arrow-resilience-kit/releases/download/0.10.4/ArrowResilienceKit.xcframework.zip",
            checksum: "08d5366039672c580f70bae88cf2b069c0bd52ddd463ec40b7bf1be9d7a59c7c"
        ),
    ]
)
