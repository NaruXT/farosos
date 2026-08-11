// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "PacketCodec",
    platforms: [
        .iOS(.v13),
        .macOS(.v12) // permite `swift test` en CLI sin necesidad de un simulador de iOS
    ],
    products: [
        .library(name: "PacketCodec", targets: ["PacketCodec"])
    ],
    targets: [
        .target(name: "PacketCodec"),
        .testTarget(name: "PacketCodecTests", dependencies: ["PacketCodec"])
    ]
)
