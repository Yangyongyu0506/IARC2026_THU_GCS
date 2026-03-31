package com.example.dronecontroller

import android.util.Log
import kotlin.math.min

private const val VOICE_TAG = "VoiceCommand"

private val STANDARD_COMMANDS = listOf(
    "forward",
    "back",
    "left",
    "right",
    "up",
    "down",
    "arm",
    "lock",
    "stop",
)

private data class CommandMatch(
    val command: String,
    val distance: Int,
)

private fun preprocess(input: String): String {
    return input
        .lowercase()
        .trim()
        .replace(Regex("[^a-z]"), "")
}

fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) dp[i][0] = i
    for (j in 0..b.length) dp[0][j] = j

    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            dp[i][j] = min(
                min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                dp[i - 1][j - 1] + cost
            )
        }
    }
    return dp[a.length][b.length]
}

private fun bestCommandMatch(input: String): CommandMatch? {
    val normalized = preprocess(input)
    if (normalized.isEmpty()) return null

    var best: CommandMatch? = null
    for (cmd in STANDARD_COMMANDS) {
        val d = levenshtein(normalized, cmd)
        if (best == null || d < best.distance) {
            best = CommandMatch(cmd, d)
        }
    }

    val threshold = if (normalized.length <= 3) 1 else 2
    return if (best != null && best.distance <= threshold) best else null
}

fun matchCommand(input: String): String? {
    return bestCommandMatch(input)?.command
}

fun parseVoiceCommand(input: String): String? {
    return matchCommand(input)
}

class VoiceCommandProcessor {

    enum class State {
        IDLE,
        WAIT_CONFIRM,
    }

    var state: State = State.IDLE
        private set

    var tempCmd: String? = null
        private set

    fun reset() {
        state = State.IDLE
        tempCmd = null
    }

    fun process(input: String): String? {
        val match = bestCommandMatch(input)
        val matchedCmd = match?.command
        val distance = match?.distance ?: -1

        val result = when (state) {
            State.IDLE -> {
                if (matchedCmd != null) {
                    tempCmd = matchedCmd
                    state = State.WAIT_CONFIRM
                }
                null
            }

            State.WAIT_CONFIRM -> {
                val expected = tempCmd
                if (matchedCmd != null && matchedCmd == expected) {
                    state = State.IDLE
                    tempCmd = null
                    matchedCmd
                } else {
                    state = State.IDLE
                    tempCmd = null
                    null
                }
            }
        }

        Log.d(
            VOICE_TAG,
            "Raw Input: $input, Matched: ${matchedCmd ?: "null"}, Distance: $distance, State: $state"
        )

        return result
    }
}
