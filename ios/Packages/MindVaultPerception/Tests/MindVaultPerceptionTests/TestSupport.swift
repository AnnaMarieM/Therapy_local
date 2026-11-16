import XCTest

/// One-element helpers the golden tests share.
extension Array {
    /// The single element, or nil when the array does not have exactly one (Kotlin's singleOrNull).
    var only: Element? { count == 1 ? first : nil }
}
