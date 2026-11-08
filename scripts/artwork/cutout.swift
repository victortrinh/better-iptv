// The Title treatment, cut from a poster (ADR 0016). Prints one JSON line per poster:
//   cutout <poster.jpg> <out-prefix> [names...]
// 1. Runs Apple's Vision text recognizer and keeps the block of the tallest line (lines at least 45% as tall within
//    0.14 of it, plus a line a quarter as tall sitting right above or below it, for "THE").
// 2. Crops that block (1.2% padding) and writes <out-prefix>-crop.png.
// 3. Colour-keys the crop: the crop's border colour (the median of its outer 6%) is the background; each pixel's
//    distance from it is thresholded by Otsu's method and softened over 36 units; writes <out-prefix>-key.png (RGBA).
// 4. Reports what the app's checks read: the recognised text, how much of the crop was kept (coverage), how bright the
//    kept pixels are (luma, 0-1) and how colourful (chroma, 0-1), so the caller can reject dark titles and crops that
//    aren't the name. A saturated red or purple title has low luma but shows on the dark ground, hence chroma.
// The same rule runs on the device with ML Kit's bundled text recognizer; this is the Mac copy for surveys and mocks.
import Foundation
import CoreGraphics
import ImageIO
import Vision
import UniformTypeIdentifiers

func writePNG(_ img: CGImage, _ path: String) {
    guard let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: path) as CFURL, UTType.png.identifier as CFString, 1, nil) else { return }
    CGImageDestinationAddImage(dest, img, nil); CGImageDestinationFinalize(dest)
}
func esc(_ s: String) -> String { s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"") }

let a = CommandLine.arguments
guard a.count >= 3 else { print("{\"error\":\"usage: cutout <poster> <out-prefix>\"}"); exit(2) }
let url = URL(fileURLWithPath: a[1]), out = a[2]
guard let src = CGImageSourceCreateWithURL(url as CFURL, nil), let full = CGImageSourceCreateImageAtIndex(src, 0, nil) else {
    print("{\"path\":\"\(esc(a[1]))\",\"error\":\"unreadable\"}"); exit(1)
}
let W = Double(full.width), H = Double(full.height)

// 1. lines
struct Line { let text: String; let x: Double; let y: Double; let w: Double; let h: Double }
let req = VNRecognizeTextRequest(); req.recognitionLevel = .accurate; req.usesLanguageCorrection = false; req.automaticallyDetectsLanguage = true
try? VNImageRequestHandler(cgImage: full, options: [:]).perform([req])
var lines: [Line] = []
for obs in req.results ?? [] {
    guard let top = obs.topCandidates(1).first else { continue }
    let b = obs.boundingBox
    lines.append(Line(text: top.string, x: b.minX, y: 1 - b.maxY, w: b.width, h: b.height))
}
let wide = lines.filter { $0.w > 0.08 }
guard let tall = wide.max(by: { $0.h < $1.h }) else {
    print("{\"path\":\"\(esc(a[1]))\",\"text\":\"\",\"error\":\"no text\"}"); exit(0)
}
var sel = wide.filter { $0.h >= 0.45 * tall.h && abs($0.y - tall.y) < 0.14 }
var y0 = sel.map { $0.y }.min()!, y1 = sel.map { $0.y + $0.h }.max()!
for l in wide where !sel.contains(where: { $0.text == l.text && $0.y == l.y }) {
    if l.h >= 0.25 * tall.h && (abs(l.y + l.h - y0) < 0.6 * tall.h || abs(l.y - y1) < 0.6 * tall.h) { sel.append(l) }
}
let pad = 0.012
let x0 = max(0, sel.map { $0.x }.min()! - pad), x1 = min(1, sel.map { $0.x + $0.w }.max()! + pad)
y0 = max(0, sel.map { $0.y }.min()! - pad); y1 = min(1, sel.map { $0.y + $0.h }.max()! + pad)
let text = sel.sorted { $0.y < $1.y }.map { $0.text }.joined(separator: " | ")

// 2. crop
let rect = CGRect(x: x0 * W, y: y0 * H, width: (x1 - x0) * W, height: (y1 - y0) * H).integral
guard let crop = full.cropping(to: rect) else { print("{\"path\":\"\(esc(a[1]))\",\"error\":\"crop\"}"); exit(1) }
writePNG(crop, out + "-crop.png")

// 3. colour key
let cw = crop.width, ch = crop.height, n = cw * ch
var px = [UInt8](repeating: 0, count: n * 4)
let cs = CGColorSpaceCreateDeviceRGB()
let ctx = CGContext(data: &px, width: cw, height: ch, bitsPerComponent: 8, bytesPerRow: cw * 4, space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
ctx.draw(crop, in: CGRect(x: 0, y: 0, width: cw, height: ch))
let ring = max(2, Int(Double(min(cw, ch)) * 0.06))
var rs = [Int](), gs = [Int](), bs = [Int]()
for yy in 0..<ch { for xx in 0..<cw where xx < ring || xx >= cw - ring || yy < ring || yy >= ch - ring {
    let i = (yy * cw + xx) * 4; rs.append(Int(px[i])); gs.append(Int(px[i + 1])); bs.append(Int(px[i + 2])) } }
rs.sort(); gs.sort(); bs.sort()
let bg = (Double(rs[rs.count / 2]), Double(gs[gs.count / 2]), Double(bs[bs.count / 2]))
var dist = [Double](repeating: 0, count: n), hist = [Int](repeating: 0, count: 442)
for i in 0..<n {
    let r = Double(px[i * 4]) - bg.0, g = Double(px[i * 4 + 1]) - bg.1, b = Double(px[i * 4 + 2]) - bg.2
    let d = (r * r + g * g + b * b).squareRoot(); dist[i] = d; hist[min(441, Int(d))] += 1
}
let total = Double(n); var sum = 0.0; for i in 0..<442 { sum += Double(i) * Double(hist[i]) }
var sumB = 0.0, wB = 0.0, best = 0.0, thr = 0.0
for i in 0..<442 {
    wB += Double(hist[i]); if wB == 0 { continue }; let wF = total - wB; if wF == 0 { break }
    sumB += Double(i) * Double(hist[i]); let mB = sumB / wB, mF = (sum - sumB) / wF
    let v = wB * wF * (mB - mF) * (mB - mF); if v > best { best = v; thr = Double(i) }
}
var outPx = [UInt8](repeating: 0, count: n * 4)
let soft = 18.0
var kept = 0.0, luma = 0.0, chroma = 0.0
for i in 0..<n {
    let al = min(1, max(0, (dist[i] - (thr - soft)) / (2 * soft)))
    let r = Double(px[i * 4]), g = Double(px[i * 4 + 1]), b = Double(px[i * 4 + 2])
    outPx[i * 4] = UInt8(r * al); outPx[i * 4 + 1] = UInt8(g * al); outPx[i * 4 + 2] = UInt8(b * al); outPx[i * 4 + 3] = UInt8(al * 255)
    kept += al; luma += al * (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255; chroma += al * (max(r, g, b) - min(r, g, b)) / 255
}
let octx = CGContext(data: &outPx, width: cw, height: ch, bitsPerComponent: 8, bytesPerRow: cw * 4, space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
writePNG(octx.makeImage()!, out + "-key.png")

// 4. report
let coverage = kept / total, meanLuma = kept > 0 ? luma / kept : 0, meanChroma = kept > 0 ? chroma / kept : 0
print("{\"path\":\"\(esc(a[1]))\",\"text\":\"\(esc(text))\",\"box\":[\(x0),\(y0),\(x1 - x0),\(y1 - y0)],\"crop\":[\(cw),\(ch)],\"threshold\":\(thr),\"coverage\":\(String(format: "%.3f", coverage)),\"luma\":\(String(format: "%.3f", meanLuma)),\"chroma\":\(String(format: "%.3f", meanChroma))}")
