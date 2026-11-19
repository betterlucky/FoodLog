// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "FoodLogCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "FoodLogCore", targets: ["FoodLogCore"]),
    ],
    targets: [
        .target(name: "FoodLogCore", linkerSettings: [.linkedLibrary("sqlite3")]),
        .testTarget(name: "FoodLogCoreTests", dependencies: ["FoodLogCore"]),
    ]
)
