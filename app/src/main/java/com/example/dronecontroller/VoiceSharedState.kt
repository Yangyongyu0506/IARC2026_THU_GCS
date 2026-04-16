package com.example.dronecontroller

import java.util.concurrent.atomic.AtomicReference

object VoiceSharedState {
    val status: AtomicReference<String> = AtomicReference("Ready")
    val rawText: AtomicReference<String> = AtomicReference("-")
}
