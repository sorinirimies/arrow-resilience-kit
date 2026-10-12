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
            url: "https://github.com/sorinirimies/arrow-resilience-kit/releases/download/0.9.0/ArrowResilienceKit.xcframework.zip",
            checksum: "39c58fa0eb5749a4859a00ef0d701587d8fe7b334274ff36b6029c510b4e7353"
        ),
    ]
)
