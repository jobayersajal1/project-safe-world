// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "SafeWorldCore",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "SafeWorldCore", targets: ["SafeWorldCore"]),
    ],
    targets: [
        .target(
            name: "SafeWorldCore",
            // `.process`, not `.copy`. `.copy` preserves the directory, leaving a
            // `Resources/` folder inside the generated resource bundle — which a
            // macOS bundle tolerates (its layout has one) but an iOS flat bundle
            // does not, so `codesign` rejects the whole thing with "bundle format
            // unrecognized, invalid, or unsuitable" and the app target won't
            // build. `.process` flattens the JSON to the bundle root instead;
            // look them up with no `subdirectory:` argument.
            resources: [.process("Resources")]
        ),
        .testTarget(
            name: "SafeWorldCoreTests",
            dependencies: ["SafeWorldCore"]
        ),
    ]
)
