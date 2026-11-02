package de.hermi.voice.config

/**
 * Bündelt alle manuell einstellbaren Aufnahme-Parameter.
 * Werte werden in der Audio-Schicht (MicrophoneSource) beim Start übernommen.
 * Defaults entsprechen den bewährten Werten (siehe AppConfig).
 */
data class RecordingParams(
    val gain: Float,
    val minTailBlocks: Int,
    val silenceBlocks: Int,
    val maxTailBlocks: Int,
    val silenceFactor: Float,
    val silenceFloorAbs: Float
)