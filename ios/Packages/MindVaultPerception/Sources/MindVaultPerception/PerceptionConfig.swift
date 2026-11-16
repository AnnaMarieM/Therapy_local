import Foundation

/// The constants the perception port shares with the Android app (app/config/AppConfig.kt). One
/// place, so the numbers never drift between the ports.
public enum PerceptionConfig {
    /// 16 kHz mono; every timing below is in milliseconds from the start of the recording.
    public static let sampleRate = 16_000

    /// Length of a chunk (`ChunkConfig.CHUNK_SECONDS` = 20): also the longest piece a turn is split into.
    public static let chunkMs: Int64 = 20_000

    /// Audio shared by two consecutive chunks (`ChunkConfig.OVERLAP_SECONDS` = 5).
    public static let overlapMs: Int64 = 5_000

    /// Samples per millisecond at [sampleRate].
    public static let samplesPerMs = sampleRate / 1000

    /// How many speakers the diarizer is asked to find in a recording (AD-07): always two, a
    /// therapist and a patient. 0 would let the clustering threshold decide.
    public static let diarizeSpeakersHint = 2

    /// Two hours of 16 kHz audio as PCM16 (230 MB; twice that as float in the runtime). A longer
    /// recording takes the chunk path (`SpeakerDiarizer.MAX_SAMPLES`).
    public static let diarizeMaxSamples = 2 * 3600 * sampleRate

    /// A diarizer runtime that dies this many times under one recording's transcribe job stops
    /// being retried: the job takes the chunk path (`MemoryConfig.DIARIZE_DEATHS_BEFORE_FALLBACK`).
    public static let diarizeDeathsBeforeFallback = 2

    /// False when a recording of [durationMs] cannot be taken by the diarizer at all.
    public static func isTooLong(durationMs: Int64) -> Bool {
        durationMs * Int64(sampleRate) / 1000 > Int64(diarizeMaxSamples)
    }

    /// DiarizeRunner's sherpa-onnx tuning (DiarizeRunner.kt companion).
    public static let diarizeThreads = 4
    /// Runs [diarizeSelfTestSeconds] of silence at open: a provider can construct fine and fail at
    /// the first run, and silence must give no turns.
    public static let diarizeSelfTestSeconds = 2
    /// Agglomerative clustering cut when the speaker count is unknown: the value sherpa-onnx's own
    /// diarization examples use (a lower value splits one voice into several).
    public static let clusterThreshold: Float = 0.5
    /// Turns shorter than this are dropped; a gap shorter than [minDurationOffS] between two turns
    /// of one speaker joins them.
    public static let minDurationOnS: Float = 0.2
    public static let minDurationOffS: Float = 0.5
}
