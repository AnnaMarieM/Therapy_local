import Foundation

/// EmbeddingGemma 2's identity and vector size, one place. Ported from
/// `com.therapylocal.app.config.EmbeddingConfig`; EmbeddingGemma 2 maps text and audio into one
/// 768-d space.
public enum EmbeddingSpec {
    /// Stored on every row; vectors are only compared within one model id.
    public static let modelID = "embeddinggemma-2-740m"

    /// Native size of the model. Matryoshka truncation to 512, 256 or 128 is possible by changing
    /// this one value.
    public static let dim = 768

    /// Input length the embedder asks for: the model's full context (one of its LiteRT input sizes
    /// 128..8192), lowered to the context it reports if that is smaller.
    public static let maxTokens = 8192

    /// Audio fills the shared context at this rate (model card, "Context Limits": 25 tokens per
    /// second, ~327 s at 8,192).
    public static let audioTokensPerSecond = 25

    /// Kept free for the model's own special tokens around the audio.
    public static let audioMarginTokens = 16

    /// The input sizes the LiteRT build of the model has (128..8192, powers of two; the engine is
    /// opened with one of them and its buffers are sized for it, see AD-08). The smallest one that
    /// holds what is needed is used, not the full 8192.
    public static let inputSizes = [128, 256, 512, 1024, 2048, 4096, 8192]

    public static func inputSize(for tokens: Int) -> Int {
        inputSizes.first { $0 >= tokens } ?? maxTokens
    }

    /// Tokens [seconds] of audio take, with the margin for the special tokens: the inverse of
    /// [maxAudioSeconds].
    public static func audioTokens(seconds: Int) -> Int {
        seconds * audioTokensPerSecond + audioMarginTokens
    }

    /// The live (audio) embedder's input size for a lookback of [lookbackS] seconds: it must also
    /// hold a voice search query, which is up to [PerceptionConfig] 20 s long. The default 20 s
    /// needs 516 tokens, so 1024.
    public static func liveInputTokens(lookbackS: Int) -> Int {
        inputSize(for: audioTokens(seconds: max(lookbackS, voiceQueryMaxSeconds)))
    }

    /// Voice search: one clip, no overlap (`ChunkConfig.QUERY_MAX_SECONDS`).
    public static let voiceQueryMaxSeconds = 20

    /// The pipeline's text embedder (batch): a transcript window is ~1000 estimated tokens plus 50
    /// of overlap, the estimate can be a few percent low (Czech more) and a fact or a search query
    /// is a sentence. 2048 holds all of them; a window that still does not fit is split by the
    /// caller, so this is a size, not a limit (`EmbeddingConfig.PIPELINE_INPUT_TOKENS`).
    public static let pipelineInputTokens = 2048

    /// Longest audio clip that fits [tokens] of context.
    public static func maxAudioSeconds(tokens: Int) -> Int {
        max(0, (tokens - audioMarginTokens) / audioTokensPerSecond)
    }

    /// Task prefixes (the model card): queries and documents must use them, so their vectors rank right.
    public static let queryPrefix = "task: search query | text: "
    public static let docPrefix = "task: search result | text: "

    /// The prefix for [role]: a query embeds under [queryPrefix], a document under [docPrefix]
    /// (EmbedRunner.runText).
    public static func prefix(query: Bool) -> String {
        query ? queryPrefix : docPrefix
    }

    /// Defensive re-normalise so cosine == dot product whatever the runtime did (EmbedRunner.finish).
    public static func normalized(_ v: [Float]) -> [Float] {
        let norm = v.reduce(0.0) { $0 + Double($1) * Double($1) }.squareRoot()
        guard norm > 0, abs(norm - 1.0) > 1e-3 else { return v }
        return v.map { Float(Double($0) / norm) }
    }
}
