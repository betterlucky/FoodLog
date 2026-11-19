import UIKit
import Vision

/// On-device label text recognition with Apple's Vision framework (ML Kit's role on Android).
/// Returns recognised lines top-to-bottom, left-to-right, joined with newlines, which is the
/// shape `LabelNutritionParser` expects from OCR.
enum LabelTextReader {
    enum ReadError: LocalizedError {
        case unreadableImage
        case noText

        var errorDescription: String? {
            switch self {
            case .unreadableImage: return "That image couldn't be read."
            case .noText: return "No text was found on the label. Try a closer, sharper photo."
            }
        }
    }

    static func read(_ image: UIImage) async throws -> String {
        guard let cgImage = image.cgImage else { throw ReadError.unreadableImage }
        let orientation = CGImagePropertyOrientation(image.imageOrientation)
        // Perform synchronously off the main thread and read results afterwards, so the
        // continuation can only ever resume once.
        return try await Task.detached(priority: .userInitiated) {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = false
            request.recognitionLanguages = ["en-GB", "en-US"]
            try VNImageRequestHandler(cgImage: cgImage, orientation: orientation).perform([request])
            let text = lines(from: request.results ?? []).joined(separator: "\n")
            guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw ReadError.noText }
            return text
        }.value
    }

    /// Groups observations into visual rows so table cells like "Energy 420kcal" stay on one line.
    static func lines(from observations: [VNRecognizedTextObservation]) -> [String] {
        let items = observations.compactMap { observation -> (box: CGRect, text: String)? in
            guard let text = observation.topCandidates(1).first?.string else { return nil }
            return (observation.boundingBox, text)
        }
        // Vision boxes are normalised with the origin at bottom-left.
        let sorted = items.sorted { $0.box.midY > $1.box.midY }
        var rows: [[(box: CGRect, text: String)]] = []
        for item in sorted {
            if let last = rows.last?.first, abs(last.box.midY - item.box.midY) < max(last.box.height, item.box.height) * 0.5 {
                rows[rows.count - 1].append(item)
            } else {
                rows.append([item])
            }
        }
        return rows.map { row in row.sorted { $0.box.minX < $1.box.minX }.map(\.text).joined(separator: " ") }
    }
}

private extension CGImagePropertyOrientation {
    init(_ orientation: UIImage.Orientation) {
        switch orientation {
        case .up: self = .up
        case .upMirrored: self = .upMirrored
        case .down: self = .down
        case .downMirrored: self = .downMirrored
        case .left: self = .left
        case .leftMirrored: self = .leftMirrored
        case .right: self = .right
        case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}
