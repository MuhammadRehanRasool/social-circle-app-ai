package dev.braindump.splitter.domain.model

sealed interface CaptureOutcome {
    data class Split(val title: String) : CaptureOutcome

    /** Saved; Gemini Nano wasn't ready, so it'll be split next time the app is open. */
    data class Queued(val reason: String?) : CaptureOutcome

    data class Failed(val reason: String?) : CaptureOutcome

    data object Empty : CaptureOutcome
}

sealed interface RefineOutcome {
    /** The idea was rewritten; [summary] says what changed. */
    data class Updated(val summary: String) : RefineOutcome

    /** Gemini Nano couldn't apply the request; the idea is untouched. */
    data class Failed(val reason: String) : RefineOutcome
}
