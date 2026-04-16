package com.example.dronecontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class MapActivity : AppCompatActivity() {

    private lateinit var mapView: MapRenderView
    private lateinit var tvVoiceRaw: TextView
    private lateinit var tvVoiceStatus: TextView

    private var receiverExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var voiceUiExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var voiceUiFuture: ScheduledFuture<*>? = null

    @Volatile
    private var running = false

    private var listenSocket: DatagramSocket? = null

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MainActivity.ACTION_CLOSE_MAP) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        mapView = findViewById(R.id.mapView)
        tvVoiceRaw = findViewById(R.id.tvMapVoiceRaw)
        tvVoiceStatus = findViewById(R.id.tvMapVoiceStatus)
        registerReceiver(closeReceiver, IntentFilter(MainActivity.ACTION_CLOSE_MAP))
    }

    override fun onResume() {
        super.onResume()
        running = true
        startUdpListener()
        startVoiceUiSync()
    }

    override fun onPause() {
        running = false
        stopVoiceUiSync()
        stopUdpListener()
        super.onPause()
    }

    override fun onDestroy() {
        running = false
        stopVoiceUiSync()
        stopUdpListener()
        unregisterReceiver(closeReceiver)
        receiverExecutor.shutdownNow()
        voiceUiExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBackPressed() {
        sendBroadcast(Intent(MainActivity.ACTION_STOP_VOICE))
        super.onBackPressed()
    }

    private fun startUdpListener() {
        if (receiverExecutor.isShutdown) {
            receiverExecutor = Executors.newSingleThreadScheduledExecutor()
        }
        receiverExecutor.execute {
            try {
                val prefs = getSharedPreferences(AppPrefs.PREFS_NAME, MODE_PRIVATE)
                val listenIp = prefs.getString(AppPrefs.KEY_LISTEN_IP, AppPrefs.DEFAULT_LISTEN_IP)
                    ?: AppPrefs.DEFAULT_LISTEN_IP
                val listenPort = prefs.getString(
                    AppPrefs.KEY_LISTEN_PORT,
                    AppPrefs.DEFAULT_LISTEN_PORT.toString()
                )?.toIntOrNull() ?: AppPrefs.DEFAULT_LISTEN_PORT

                val bindAddress = if (listenIp == "0.0.0.0") {
                    null
                } else {
                    InetAddress.getByName(listenIp)
                }

                listenSocket = if (bindAddress == null) {
                    DatagramSocket(listenPort)
                } else {
                    DatagramSocket(listenPort, bindAddress)
                }.apply {
                    soTimeout = UDP_TIMEOUT_MS
                }

                Log.d(TAG, "Map UDP listener started on $listenIp:$listenPort")

                val buffer = ByteArray(2048)
                while (running) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        listenSocket?.receive(packet)
                        val payload = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                        handleUdpPayload(payload)
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "startUdpListener failed: ${e.message}")
            }
        }
    }

    private fun stopUdpListener() {
        try {
            listenSocket?.close()
        } catch (_: Exception) {
        } finally {
            listenSocket = null
        }
    }

    private fun handleUdpPayload(payload: String) {
        try {
            val obj = JSONObject(payload)
            if (!obj.has("x") || !obj.has("y")) {
                return
            }
            val x = obj.optInt("x", -1)
            val y = obj.optInt("y", -1)
            if (x < 0 || y < 0) {
                return
            }
            runOnUiThread {
                mapView.addPoint(x, y)
            }
        } catch (_: Exception) {
        }
    }

    private fun startVoiceUiSync() {
        if (voiceUiExecutor.isShutdown) {
            voiceUiExecutor = Executors.newSingleThreadScheduledExecutor()
        }
        voiceUiFuture = voiceUiExecutor.scheduleAtFixedRate(
            {
                val raw = VoiceSharedState.rawText.get()
                val status = VoiceSharedState.status.get()
                runOnUiThread {
                    tvVoiceRaw.text = getString(R.string.map_voice_raw, raw)
                    tvVoiceStatus.text = getString(R.string.map_voice_status, status)
                }
            },
            0L,
            200L,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopVoiceUiSync() {
        voiceUiFuture?.cancel(false)
        voiceUiFuture = null
    }

    companion object {
        private const val TAG = "MapActivity"
        private const val UDP_TIMEOUT_MS = 300
    }
}
