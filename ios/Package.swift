// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "Farosos",
    platforms: [
        .iOS(.v13),
        .macOS(.v12) // permite `swift test` en CLI sin necesidad de un simulador de iOS
    ],
    products: [
        .library(name: "PacketCodec", targets: ["PacketCodec"]),
        .library(name: "PersonStateMachine", targets: ["PersonStateMachine"]),
        .library(name: "BeaconRadio", targets: ["BeaconRadio"])
    ],
    targets: [
        .target(name: "PacketCodec"),
        .testTarget(name: "PacketCodecTests", dependencies: ["PacketCodec"]),
        .target(name: "PersonStateMachine", dependencies: ["PacketCodec"]),
        .target(name: "TestSupport", dependencies: ["PersonStateMachine"]),
        .testTarget(name: "PersonStateMachineTests", dependencies: ["PersonStateMachine", "TestSupport"]),
        .target(name: "BeaconRadio", dependencies: ["PacketCodec", "PersonStateMachine"]),
        .testTarget(name: "BeaconRadioTests", dependencies: ["BeaconRadio", "TestSupport"])
    ]
)
