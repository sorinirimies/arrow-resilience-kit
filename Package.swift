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
            url: "https://github.com/sorinirimies/arrow-resilience-kit/releases/download/0.5.9/ArrowResilienceKit.xcframework.zip",
            checksum: "6f913db7075fb572d7636c1f085e9054b6d9ec6666725795ea89a5dc108349c6"
        ),
    ]
)
