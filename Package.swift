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
            url: "https://github.com/sorinirimies/arrow-resilience-kit/releases/download/0.8.0/ArrowResilienceKit.xcframework.zip",
            checksum: "1ab820eb3796039333bdafe1e6983e1c158367ec5844c9c2b568c18b3bb4f89d"
        ),
    ]
)
