import Foundation
import AppKit
import CoreGraphics

let inputPath = "/Users/benattobb/.gemini/antigravity/brain/62540650-7aee-4f9b-b2e8-2f68f0de6485/.user_uploaded/media_1790975577170.png"
let baseDir = "/Users/benattobb/Documents/AirPlayTV/app/src/main/res"

guard let inputImage = NSImage(contentsOfFile: inputPath) else {
    print("Error: Could not load input image at \(inputPath)")
    exit(1)
}

func renderFrostedIcon(size: Int) -> NSImage {
    let s = CGFloat(size)
    let img = NSImage(size: NSSize(width: s, height: s))
    img.lockFocus()
    guard let ctx = NSGraphicsContext.current?.cgContext else { return img }

    let rect = CGRect(x: 0, y: 0, width: s, height: s)

    // 1. Colorful Violet to Blue Gradient Background
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let colors = [
        NSColor(red: 0.49, green: 0.23, blue: 0.93, alpha: 1.0).cgColor, // #7C3AED (Electric Violet)
        NSColor(red: 0.15, green: 0.39, blue: 0.92, alpha: 1.0).cgColor, // #2563EB (Vibrant Cobalt Blue)
        NSColor(red: 0.02, green: 0.71, blue: 0.83, alpha: 1.0).cgColor  // #06B6D4 (Cyan accent)
    ] as CFArray

    var locations: [CGFloat] = [0.0, 0.65, 1.0]
    if let gradient = CGGradient(colorsSpace: colorSpace, colors: colors, locations: &locations) {
        ctx.saveGState()
        let squirclePath = CGPath(roundedRect: rect, cornerWidth: s * 0.22, cornerHeight: s * 0.22, transform: nil)
        ctx.addPath(squirclePath)
        ctx.clip()
        ctx.drawLinearGradient(gradient, start: CGPoint(x: 0, y: s), end: CGPoint(x: s, y: 0), options: [])
        ctx.restoreGState()
    }

    // 2. Frosted Glass Inner Container
    let margin = s * 0.10
    let innerRect = rect.insetBy(dx: margin, dy: margin)
    let innerPath = CGPath(roundedRect: innerRect, cornerWidth: s * 0.18, cornerHeight: s * 0.18, transform: nil)

    ctx.saveGState()
    ctx.addPath(innerPath)
    ctx.clip()

    // Glass translucency fill
    ctx.setFillColor(NSColor(white: 1.0, alpha: 0.38).cgColor)
    ctx.fill(innerRect)

    // Subtle glass specular top highlight
    let highlightRect = CGRect(x: innerRect.minX, y: innerRect.midY, width: innerRect.width, height: innerRect.height / 2)
    ctx.setFillColor(NSColor(white: 1.0, alpha: 0.18).cgColor)
    ctx.fill(highlightRect)
    ctx.restoreGState()

    // Glass frosted border (semi-transparent white stroke)
    ctx.saveGState()
    ctx.setStrokeColor(NSColor(white: 1.0, alpha: 0.75).cgColor)
    ctx.setLineWidth(max(1.5, s * 0.02))
    ctx.addPath(innerPath)
    ctx.strokePath()
    ctx.restoreGState()

    // 3. Black Main Icon from user's upload (centered crisply inside glass)
    let iconPadding = s * 0.22
    let iconRect = rect.insetBy(dx: iconPadding, dy: iconPadding)
    inputImage.draw(in: iconRect, from: NSRect(origin: .zero, size: inputImage.size), operation: .sourceOver, fraction: 1.0)

    img.unlockFocus()
    return img
}

func renderTVBanner(width: Int, height: Int) -> NSImage {
    let w = CGFloat(width)
    let h = CGFloat(height)
    let img = NSImage(size: NSSize(width: w, height: h))
    img.lockFocus()
    guard let ctx = NSGraphicsContext.current?.cgContext else { return img }

    let rect = CGRect(x: 0, y: 0, width: w, height: h)

    // Dark sleek background with violet-blue ambient mesh
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let bgColors = [
        NSColor(red: 0.06, green: 0.04, blue: 0.12, alpha: 1.0).cgColor, // Deep Dark Violet
        NSColor(red: 0.05, green: 0.09, blue: 0.20, alpha: 1.0).cgColor  // Deep Midnight Blue
    ] as CFArray
    var bgLocations: [CGFloat] = [0.0, 1.0]
    if let bgGradient = CGGradient(colorsSpace: colorSpace, colors: bgColors, locations: &bgLocations) {
        ctx.drawLinearGradient(bgGradient, start: CGPoint(x: 0, y: h), end: CGPoint(x: w, y: 0), options: [])
    }

    // Frosted Glass Icon Badge on Left
    let iconSize = h * 0.70
    let iconX = h * 0.15
    let iconY = (h - iconSize) / 2.0
    let frostedIcon = renderFrostedIcon(size: Int(iconSize))
    frostedIcon.draw(in: CGRect(x: iconX, y: iconY, width: iconSize, height: iconSize))

    // App Name & Tagline Text
    let textX = iconX + iconSize + h * 0.15
    let titleAttributes: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: h * 0.20, weight: .bold),
        .foregroundColor: NSColor.white
    ]
    let subtitleAttributes: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: h * 0.11, weight: .medium),
        .foregroundColor: NSColor(red: 0.70, green: 0.75, blue: 0.90, alpha: 1.0)
    ]

    "AuraCast".draw(at: NSPoint(x: textX, y: h * 0.52), withAttributes: titleAttributes)
    "Ultra-Low Latency AirPlay TV".draw(at: NSPoint(x: textX, y: h * 0.32), withAttributes: subtitleAttributes)

    img.unlockFocus()
    return img
}

func savePNG(image: NSImage, path: String) {
    guard let tiff = image.tiffRepresentation,
          let rep = NSBitmapImageRep(data: tiff),
          let png = rep.representation(using: .png, properties: [:]) else {
        print("Failed to encode PNG for \(path)")
        return
    }
    let url = URL(fileURLWithPath: path)
    try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try? png.write(to: url)
    print("Saved: \(path)")
}

// Generate all standard Android TV & mobile icon sizes
let sizes = [
    ("mipmap-mdpi", 48),
    ("mipmap-hdpi", 72),
    ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144),
    ("mipmap-xxxhdpi", 192),
    ("drawable", 512)
]

for (dir, size) in sizes {
    let img = renderFrostedIcon(size: size)
    let fileName = (dir == "drawable") ? "ic_launcher_512.png" : "ic_launcher.png"
    savePNG(image: img, path: "\(baseDir)/\(dir)/\(fileName)")
    if dir != "drawable" {
        savePNG(image: img, path: "\(baseDir)/\(dir)/ic_launcher_round.png")
    }
}

// Generate Android TV Leanback Banner (320x180 px and 640x360 px for xhdpi)
let banner = renderTVBanner(width: 640, height: 360)
savePNG(image: banner, path: "\(baseDir)/drawable-xhdpi/banner.png")
savePNG(image: banner, path: "\(baseDir)/drawable/banner.png")

print("All icons and banners successfully generated!")
