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
        // The privileged half of macOS blocking. A GUI app runs as the user and
        // cannot bind port 53, so the resolver lives in a separate binary that
        // launchd starts as root.
        .executable(name: "safeworld-dnsd", targets: ["safeworld-dnsd"]),
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
        .executableTarget(
            name: "safeworld-dnsd",
            dependencies: ["SafeWorldCore"]
        ),
        .testTarget(
            name: "SafeWorldCoreTests",
            dependencies: ["SafeWorldCore"]
        ),
    ]
)
