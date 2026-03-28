package com.example.dronecontroller

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

class MainActivity : AppCompatActivity() {

    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var tvPosition: TextView
    private lateinit var tvVoiceText: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnMic: Button
    private lateinit var btnTakeoffLand: Button

    private val stateLock = Any()
    private var seqId: Long = 0L
    private var x: Int = 0
    private var y: Int = 0
    private var z: Int = 0
    private var yaw: Int = 0

    private var isAirborne = false

    @Volatile
    private var targetIp: String = DEFAULT_BROADCAST_IP

    @Volatile
    private var targetPort: Int = DEFAULT_PORT

    private var senderExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var sendingFuture: ScheduledFuture<*>? = null
    private var udpSocket: DatagramSocket? = null

    private var voskModel: Model? = null
    private var speechService: SpeechService? = null
    private var isModelReady = false
    private val modelExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var voiceEnabled = false
    private var voiceState = VoiceState.IDLE
    private var pendingCommand: Command? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupNetworkInputs()
        updateStateUi("Ready")
        initVoskModel()

        findViewById<Button>(R.id.btnForward).setOnClickListener { applyCommand(Command.FORWARD) }
        findViewById<Button>(R.id.btnBackward).setOnClickListener { applyCommand(Command.BACKWARD) }
        findViewById<Button>(R.id.btnLeft).setOnClickListener { applyCommand(Command.LEFT) }
        findViewById<Button>(R.id.btnRight).setOnClickListener { applyCommand(Command.RIGHT) }
        findViewById<Button>(R.id.btnUp).setOnClickListener { applyCommand(Command.UP) }
        findViewById<Button>(R.id.btnDown).setOnClickListener { applyCommand(Command.DOWN) }

        btnTakeoffLand.setOnClickListener {
            sendTakeoffLandCommand()
        }

        btnMic.setOnClickListener {
            if (voiceEnabled) {
                stopVoiceRecognition()
                updateStateUi(getString(R.string.state_voice_stopped))
            } else {
                startVoiceRecognition()
            }
        }

    }

    override fun onResume() {
        super.onResume()
        startSendingLoop()
    }

    override fun onPause() {
        sendFailsafeState()
        stopSendingLoop()
        stopVoiceRecognition()
        super.onPause()
    }

    override fun onDestroy() {
        sendFailsafeState()
        stopSendingLoop()
        stopVoiceRecognition()
        closeUdpSocket()
        voskModel?.close()
        voskModel = null
        senderExecutor.shutdownNow()
        modelExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        etIp = findViewById(R.id.etIp)
        etPort = findViewById(R.id.etPort)
        tvPosition = findViewById(R.id.tvPosition)
        tvVoiceText = findViewById(R.id.tvVoiceText)
        tvStatus = findViewById(R.id.tvStatus)
        btnMic = findViewById(R.id.btnMic)
        btnTakeoffLand = findViewById(R.id.btnTakeoffLand)
    }

    private fun setupNetworkInputs() {
        etIp.setText(DEFAULT_BROADCAST_IP)
        etPort.setText(DEFAULT_PORT.toString())
        targetIp = DEFAULT_BROADCAST_IP
        targetPort = DEFAULT_PORT

        etIp.doAfterTextChanged { editable ->
            val text = editable?.toString()?.trim().orEmpty()
            targetIp = if (text.isEmpty()) DEFAULT_BROADCAST_IP else text
        }

        etPort.doAfterTextChanged { editable ->
            val parsed = editable?.toString()?.trim()?.toIntOrNull()
            if (parsed != null && parsed in 1..65535) {
                targetPort = parsed
            }
        }
    }

    private fun sendFailsafeState() {
        synchronized(stateLock) {
            seqId++
        }
        if (senderExecutor.isShutdown) {
            return
        }
        senderExecutor.execute {
            sendState()
        }
        Log.d(TAG, "Failsafe hover state sent")
    }

    private fun sendState() {
        val snapshot: StateSnapshot = synchronized(stateLock) {
            StateSnapshot(seqId, x, y, z, yaw)
        }

        val payload = "${snapshot.seqId},${snapshot.x},${snapshot.y},${snapshot.z},${snapshot.yaw}"

        try {
            val ip = targetIp
            val port = targetPort
            val address = InetAddress.getByName(ip)
            val packetBytes = payload.toByteArray(StandardCharsets.UTF_8)
            val packet = DatagramPacket(packetBytes, packetBytes.size, address, port)
            val socket = getOrCreateSocket()
            socket.send(packet)
            Log.d(TAG, "sendState: $payload -> $ip:$port")
        } catch (e: Exception) {
            Log.d(TAG, "UDP send failed: ${e.message}")
        }
    }

    private fun getOrCreateSocket(): DatagramSocket {
        val current = udpSocket
        if (current != null && !current.isClosed) {
            return current
        }
        val newSocket = DatagramSocket().apply {
            broadcast = true
        }
        udpSocket = newSocket
        return newSocket
    }

    private fun closeUdpSocket() {
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        } finally {
            udpSocket = null
        }
    }

    private fun startSendingLoop() {
        if (sendingFuture?.isCancelled == false) {
            return
        }
        if (senderExecutor.isShutdown) {
            senderExecutor = Executors.newSingleThreadScheduledExecutor()
        }
        sendingFuture = senderExecutor.scheduleAtFixedRate(
            { sendState() },
            0L,
            SEND_PERIOD_MS,
            TimeUnit.MILLISECONDS
        )
        Log.d(TAG, "20Hz sending loop started")
    }

    private fun stopSendingLoop() {
        sendingFuture?.cancel(false)
        sendingFuture = null
        Log.d(TAG, "Sending loop stopped")
    }

    private fun startVoiceRecognition() {
        if (!isModelReady || voskModel == null) {
            Toast.makeText(this, R.string.voice_model_not_ready, Toast.LENGTH_SHORT).show()
            updateStateUi(getString(R.string.state_voice_model_not_ready))
            return
        }

        if (!hasRecordPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO_PERMISSION
            )
            return
        }

        try {
            if (speechService == null) {
                val recognizer = Recognizer(voskModel, VOSK_SAMPLE_RATE)
                speechService = SpeechService(recognizer, VOSK_SAMPLE_RATE)
            }
            speechService?.startListening(createVoskListener())
        } catch (e: Exception) {
            Toast.makeText(this, R.string.voice_not_available, Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Vosk start failed: ${e.message}")
            return
        }

        voiceEnabled = true
        btnMic.text = getString(R.string.voice_stop)
        voiceState = VoiceState.IDLE
        pendingCommand = null
        updateStateUi(getString(R.string.state_voice_listening))
        Log.d(TAG, "Vosk voice recognition started")
    }

    private fun stopVoiceRecognition() {
        voiceEnabled = false
        voiceState = VoiceState.IDLE
        pendingCommand = null
        btnMic.text = getString(R.string.voice_start)

        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        Log.d(TAG, "Voice recognition stopped")
    }

    private fun initVoskModel() {
        updateStateUi(getString(R.string.state_voice_model_loading))
        modelExecutor.execute {
            try {
                val targetDir = File(filesDir, VOSK_TARGET_MODEL_NAME)
                if (!isModelCopied(targetDir)) {
                    targetDir.deleteRecursively()
                    targetDir.mkdirs()
                    copyAssetFolder(VOSK_ASSET_MODEL_NAME, targetDir)
                }

                val model = Model(targetDir.absolutePath)
                voskModel = model
                isModelReady = true
                runOnUiThread {
                    updateStateUi(getString(R.string.state_voice_model_ready))
                }
                Log.d(TAG, "Vosk model loaded from ${targetDir.absolutePath}")
            } catch (exception: Exception) {
                isModelReady = false
                runOnUiThread {
                    updateStateUi(getString(R.string.state_voice_model_failed))
                }
                Log.d(TAG, "Vosk model load failed: ${exception.message}")
                exception.printStackTrace()
            }
        }
    }

    private fun isModelCopied(targetDir: File): Boolean {
        return targetDir.exists() &&
            File(targetDir, "am").exists() &&
            File(targetDir, "conf").exists() &&
            File(targetDir, "graph").exists()
    }

    private fun copyAssetFolder(assetPath: String, targetDir: File) {
        val assetEntries = assets.list(assetPath) ?: emptyArray()
        if (assetEntries.isEmpty()) {
            assets.open(assetPath).use { input ->
                FileOutputStream(targetDir).use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        for (entry in assetEntries) {
            val childAssetPath = if (assetPath.isEmpty()) entry else "$assetPath/$entry"
            val childEntries = assets.list(childAssetPath) ?: emptyArray()
            if (childEntries.isEmpty()) {
                val outFile = File(targetDir, entry)
                assets.open(childAssetPath).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                copyAssetFolder(childAssetPath, File(targetDir, entry))
            }
        }
    }

    private fun createVoskListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                val partialText = extractField(hypothesis, "partial")

                if (partialText.isNotEmpty()) {
                    runOnUiThread {
                        tvVoiceText.text = getString(R.string.voice_text_raw, partialText)
                    }
                }
            }

            override fun onResult(hypothesis: String?) {
                val text = extractField(hypothesis, "text")
                if (text.isNotEmpty()) {
                    runOnUiThread {
                        tvVoiceText.text = getString(R.string.voice_text_raw, text)
                        handleVoiceInput(text)
                    }
                }
            }

            override fun onFinalResult(hypothesis: String?) {
                val text = extractField(hypothesis, "text")
                if (text.isNotEmpty()) {
                    runOnUiThread {
                        tvVoiceText.text = getString(R.string.voice_text_raw, text)
                    }
                }
            }

            override fun onError(exception: Exception?) {
                Log.d(TAG, "Vosk error=${exception?.message}")
                runOnUiThread {
                    updateStateUi(getString(R.string.state_voice_retrying))
                }
            }

            override fun onTimeout() {
                Log.d(TAG, "Vosk timeout")
                runOnUiThread {
                    updateStateUi(getString(R.string.state_voice_retrying))
                }
            }
        }
    }

    private fun extractField(hypothesis: String?, key: String): String {
        if (hypothesis.isNullOrBlank()) {
            return ""
        }
        return try {
            JSONObject(hypothesis).optString(key, "").trim()
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse Vosk JSON: ${e.message}")
            ""
        }
    }

    private fun handleVoiceInput(recognizedText: String) {
        val command = parseVoiceCommand(recognizedText)
        if (command == null) {
            updateStateUi(getString(R.string.state_voice_unknown))
            voiceState = VoiceState.IDLE
            pendingCommand = null
            return
        }

        if (voiceState == VoiceState.IDLE) {
            pendingCommand = command
            voiceState = VoiceState.WAIT_CONFIRM
            updateStateUi(getString(R.string.state_voice_confirm))
            return
        }

        val waiting = pendingCommand
        if (voiceState == VoiceState.WAIT_CONFIRM && waiting == command) {
            applyCommand(command)
            voiceState = VoiceState.IDLE
            pendingCommand = null
            updateStateUi(getString(R.string.state_voice_confirmed))
        } else {
            updateStateUi(getString(R.string.state_voice_mismatch))
            voiceState = VoiceState.IDLE
            pendingCommand = null
        }
    }

    private fun parseVoiceCommand(recognizedText: String): Command? {
        val text = recognizedText.lowercase(Locale.getDefault()).trim()
        if (text.isEmpty()) {
            return null
        }

        return when {
            text.contains("forward") || text.contains("前进") -> Command.FORWARD
            text.contains("back") || text.contains("后退") -> Command.BACKWARD
            text.contains("left") || text.contains("左") -> Command.LEFT
            text.contains("right") || text.contains("右") -> Command.RIGHT
            text.contains("up") || text.contains("上升") -> Command.UP
            text.contains("down") || text.contains("下降") -> Command.DOWN
            text.contains("takeoff") || text.contains("起飞") -> Command.TAKEOFF
            text.contains("land") || text.contains("降落") -> Command.LAND
            text.contains("stop") || text.contains("停止") -> Command.STOP
            else -> null
        }
    }

    private fun applyCommand(command: Command) {
        var immediateCommand: String? = null
        synchronized(stateLock) {
            when (command) {
                Command.FORWARD -> x += STEP
                Command.BACKWARD -> x -= STEP
                Command.LEFT -> y += STEP
                Command.RIGHT -> y -= STEP
                Command.UP -> z += STEP
                Command.DOWN -> z -= STEP
                Command.TAKEOFF -> {
                    isAirborne = true
                    immediateCommand = "TAKEOFF"
                }
                Command.LAND -> {
                    isAirborne = false
                    immediateCommand = "LAND"
                }
                Command.STOP -> {
                }
            }
            seqId++
        }

        immediateCommand?.let { sendImmediateCommand(it) }

        Log.d(TAG, "Command applied: $command")
        updateStateUi(getString(R.string.state_button_applied))
    }

    private fun updateStateUi(status: String) {
        val snapshot = synchronized(stateLock) {
            StateSnapshot(seqId, x, y, z, yaw)
        }
        tvPosition.text = getString(
            R.string.position_value,
            snapshot.x,
            snapshot.y,
            snapshot.z,
            snapshot.yaw,
            snapshot.seqId
        )
        tvStatus.text = getString(R.string.status_value, status)
    }

    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecognition()
        } else if (requestCode == REQUEST_AUDIO_PERMISSION) {
            Toast.makeText(this, R.string.voice_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendTakeoffLandCommand() {
        val command = synchronized(stateLock) {
            seqId++
            if (isAirborne) {
                isAirborne = false
                "LAND"
            } else {
                isAirborne = true
                "TAKEOFF"
            }
        }

        sendImmediateCommand(command)

        updateStateUi(getString(R.string.state_button_applied))
    }

    private fun sendImmediateCommand(command: String) {
        if (senderExecutor.isShutdown) {
            return
        }
        senderExecutor.execute {
            try {
                val ip = targetIp
                val port = targetPort
                val address = InetAddress.getByName(ip)
                val packetBytes = command.toByteArray(StandardCharsets.UTF_8)
                val packet = DatagramPacket(packetBytes, packetBytes.size, address, port)
                val socket = getOrCreateSocket()
                socket.send(packet)
                Log.d(TAG, "sendCommand: $command -> $ip:$port")
            } catch (e: Exception) {
                Log.d(TAG, "Command send failed: ${e.message}")
            }
        }
    }

    private data class StateSnapshot(
        val seqId: Long,
        val x: Int,
        val y: Int,
        val z: Int,
        val yaw: Int,
    )

    private enum class VoiceState {
        IDLE,
        WAIT_CONFIRM,
    }

    private enum class Command {
        FORWARD,
        BACKWARD,
        LEFT,
        RIGHT,
        UP,
        DOWN,
        TAKEOFF,
        LAND,
        STOP,
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_BROADCAST_IP = "255.255.255.255"
        private const val DEFAULT_PORT = 5005
        private const val SEND_PERIOD_MS = 50L
        private const val REQUEST_AUDIO_PERMISSION = 1001
        private const val STEP = 1
        private const val VOSK_SAMPLE_RATE = 16000.0f
        private const val VOSK_ASSET_MODEL_NAME = "model"
        private const val VOSK_TARGET_MODEL_NAME = "vosk-model"
    }
}
