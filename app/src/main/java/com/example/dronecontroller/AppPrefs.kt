package com.example.dronecontroller

object AppPrefs {
    const val PREFS_NAME = "controller_prefs"

    const val KEY_DX = "dx"
    const val KEY_DY = "dy"
    const val KEY_DZ = "dz"

    const val KEY_BROADCAST_IP = "broadcast_ip"
    const val KEY_BROADCAST_PORT = "broadcast_port"
    const val KEY_LISTEN_IP = "listen_ip"
    const val KEY_LISTEN_PORT = "listen_port"

    const val DEFAULT_STEP = 1
    const val DEFAULT_BROADCAST_IP = "255.255.255.255"
    const val DEFAULT_BROADCAST_PORT = 5005
    const val DEFAULT_LISTEN_IP = "0.0.0.0"
    const val DEFAULT_LISTEN_PORT = 6006
}
