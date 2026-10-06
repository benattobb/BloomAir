import Foundation

// Keep the legacy Swift entry point, but route it through the canonical asset
// generator so Android icons and banners always share one design source.
let scriptURL = URL(fileURLWithPath: #filePath)
let repoRoot = scriptURL.deletingLastPathComponent().deletingLastPathComponent()
let generatorURL = repoRoot.appendingPathComponent("scripts/make_icons.py")
let defaultIconURL = repoRoot.appendingPathComponent("app/src/main/res/drawable/ic_logo_black.png")

let inputURL: URL
if CommandLine.arguments.count > 2 {
    fputs("Usage: swift generate_icons.swift [source-icon.png]\n", stderr)
    exit(2)
} else if let argument = CommandLine.arguments.dropFirst().first {
    let candidate = URL(fileURLWithPath: argument, relativeTo: URL(fileURLWithPath: FileManager.default.currentDirectoryPath))
    inputURL = candidate.standardizedFileURL
} else {
    inputURL = defaultIconURL
}

let process = Process()
process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
process.arguments = ["python3", generatorURL.path, inputURL.path]
process.currentDirectoryURL = repoRoot

do {
    try process.run()
    process.waitUntilExit()
    exit(process.terminationStatus)
} catch {
    fputs("Could not run canonical icon generator: \(error)\n", stderr)
    exit(1)
}
