// Prints, per image path given, one JSON line: {"path":…, "width":…, "height":…, "lines":[{"text":…, "conf":…, "h":…}]}
// using Apple's Vision text recognizer. Used by scripts/survey_artwork.py to tell posters with text from textless art.
import Foundation
import Vision
import ImageIO

for path in CommandLine.arguments.dropFirst() {
    let url = URL(fileURLWithPath: path)
    guard let src = CGImageSourceCreateWithURL(url as CFURL, nil), let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else {
        print("{\"path\":\(String(reflecting: path)),\"error\":\"unreadable\"}"); continue
    }
    let req = VNRecognizeTextRequest()
    req.recognitionLevel = .accurate
    req.usesLanguageCorrection = false
    req.automaticallyDetectsLanguage = true
    let handler = VNImageRequestHandler(cgImage: img, options: [:])
    var lines: [String] = []
    do {
        try handler.perform([req])
        for obs in req.results ?? [] {
            guard let top = obs.topCandidates(1).first else { continue }
            let t = top.string.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"").replacingOccurrences(of: "\n", with: " ")
            lines.append("{\"text\":\"\(t)\",\"conf\":\(top.confidence),\"h\":\(obs.boundingBox.height)}")
        }
    } catch {
        print("{\"path\":\(String(reflecting: path)),\"error\":\"\(error)\"}"); continue
    }
    print("{\"path\":\(String(reflecting: path)),\"width\":\(img.width),\"height\":\(img.height),\"lines\":[\(lines.joined(separator: ","))]}")
}
