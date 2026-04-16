package com.example.dronecontroller

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

    private val stateLock = Any()
    private var seqId: Long = 0L
    private var x: Int = 0
    private var y: Int = 0
    private var z: Int = 0
    private var yaw: Int = 0

    private var dx: Int = DEFAULT_STEP
    private var dy: Int = DEFAULT_STEP
    private var dz: Int = DEFAULT_STEP

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
    private val voiceProcessor = VoiceCommandProcessor()
    private var switchingToMap = false

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP_VOICE -> {
                    stopVoiceRecognition()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        registerReceiver(controlReceiver, IntentFilter(ACTION_STOP_VOICE))
        setupNetworkInputs()
        loadStepSettings()
        updateStateUi(getString(R.string.status_ready))
        VoiceSharedState.status.set(getString(R.string.status_ready))
        VoiceSharedState.rawText.set("-")
        initVoskModel()

        findViewById<Button>(R.id.btnForward).setOnClickListener { applyCommand(Command.FORWARD) }
        findViewById<Button>(R.id.btnBackward).setOnClickListener { applyCommand(Command.BACKWARD) }
        findViewById<Button>(R.id.btnLeft).setOnClickListener { applyCommand(Command.LEFT) }
        findViewById<Button>(R.id.btnRight).setOnClickListener { applyCommand(Command.RIGHT) }
        findViewById<Button>(R.id.btnUp).setOnClickListener { applyCommand(Command.UP) }
        findViewById<Button>(R.id.btnDown).setOnClickListener { applyCommand(Command.DOWN) }
        findViewById<Button>(R.id.btnArm).setOnClickListener { applyCommand(Command.ARM) }
        findViewById<Button>(R.id.btnDisarm).setOnClickListener { applyCommand(Command.DISARM) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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
        switchingToMap = false
        loadStepSettings()
        startSendingLoop()
    }

    override fun onPause() {
        if (!switchingToMap) {
            sendHoldCommand()
            stopSendingLoop()
            stopVoiceRecognition()
        }
        super.onPause()
    }

    override fun onDestroy() {
        sendHoldCommand()
        stopSendingLoop()
        stopVoiceRecognition()
        closeUdpSocket()
        voskModel?.close()
        voskModel = null
        unregisterReceiver(controlReceiver)
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
    }

    private fun setupNetworkInputs() {
        val prefs = getSharedPreferences(AppPrefs.PREFS_NAME, MODE_PRIVATE)
        val broadcastIp = prefs.getString(AppPrefs.KEY_BROADCAST_IP, AppPrefs.DEFAULT_BROADCAST_IP)
            ?: AppPrefs.DEFAULT_BROADCAST_IP
        val broadcastPort = prefs.getString(
            AppPrefs.KEY_BROADCAST_PORT,
            AppPrefs.DEFAULT_BROADCAST_PORT.toString()
        )?.toIntOrNull() ?: AppPrefs.DEFAULT_BROADCAST_PORT

        etIp.setText(broadcastIp)
        etPort.setText(broadcastPort.toString())

        etIp.doAfterTextChanged { editable ->
            val value = editable?.toString()?.trim().orEmpty()
            targetIp = if (value.isEmpty()) AppPrefs.DEFAULT_BROADCAST_IP else value
        }

        etPort.doAfterTextChanged { editable ->
            val parsed = editable?.toString()?.trim()?.toIntOrNull()
            if (parsed != null && parsed in 1..65535) {
                targetPort = parsed
            }
        }

        targetIp = broadcastIp
        targetPort = broadcastPort
    }

    private fun loadStepSettings() {
        val prefs = getSharedPreferences(AppPrefs.PREFS_NAME, MODE_PRIVATE)
        dx = prefs.getString(AppPrefs.KEY_DX, DEFAULT_STEP.toString())?.toIntOrNull() ?: DEFAULT_STEP
        dy = prefs.getString(AppPrefs.KEY_DY, DEFAULT_STEP.toString())?.toIntOrNull() ?: DEFAULT_STEP
        dz = prefs.getString(AppPrefs.KEY_DZ, DEFAULT_STEP.toString())?.toIntOrNull() ?: DEFAULT_STEP
    }

    private fun startSendingLoop() {
        if (sendingFuture?.isCancelled == false) {
            return
        }
        if (senderExecutor.isShutdown) {
            senderExecutor = Executors.newSingleThreadScheduledExecutor()
        }
        sendingFuture = senderExecutor.scheduleAtFixedRate(
            { sendMoveJson() },
            0L,
            SEND_PERIOD_MS,
            TimeUnit.MILLISECONDS
        )
        Log.d(TAG, "20Hz move loop started")
    }

    private fun stopSendingLoop() {
        sendingFuture?.cancel(false)
        sendingFuture = null
        Log.d(TAG, "move loop stopped")
    }

    private fun sendMoveJson() {
        val payload = synchronized(stateLock) {
            JSONObject().apply {
                put("s", seqId)
                put("c", "m")
                put("x", x)
                put("y", y)
                put("z", z)
                put("yaw", yaw)
            }.toString()
        }
        sendJsonCommand(payload)
    }

    private fun sendHoldCommand() {
        synchronized(stateLock) {
            seqId++
        }
        sendImmediateJson("hold")
    }

    private fun sendImmediateJson(cmd: String) {
        val payload = synchronized(stateLock) {
            JSONObject().apply {
                put("s", seqId)
                put("c", cmd)
            }.toString()
        }
        sendJsonCommand(payload)
    }

    private fun sendJsonCommand(payload: String) {
        if (senderExecutor.isShutdown) {
            return
        }
        senderExecutor.execute {
            try {
                val ip = targetIp
                val port = targetPort
                val address = InetAddress.getByName(ip)
                val bytes = payload.toByteArray(StandardCharsets.UTF_8)
                val packet = DatagramPacket(bytes, bytes.size, address, port)
                val socket = getOrCreateSocket()
                socket.send(packet)
                Log.d(TAG, "UDP: $payload -> $ip:$port")
            } catch (e: Exception) {
                Log.d(TAG, "sendJsonCommand failed: ${e.message}")
            }
        }
    }

    private fun getOrCreateSocket(): DatagramSocket {
        val current = udpSocket
        if (current != null && !current.isClosed) {
            return current
        }
        val created = DatagramSocket().apply {
            broadcast = true
        }
        udpSocket = created
        return created
    }

    private fun closeUdpSocket() {
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        } finally {
            udpSocket = null
        }
    }

    private fun applyCommand(command: Command) {
        when (command) {
            Command.ARM -> {
                synchronized(stateLock) { seqId++ }
                sendImmediateJson("a")
            }
            Command.DISARM -> {
                synchronized(stateLock) { seqId++ }
                sendImmediateJson("d")
            }
            Command.STOP -> {
                synchronized(stateLock) { seqId++ }
                sendImmediateJson("h")
            }
            else -> {
                synchronized(stateLock) {
                    when (command) {
                        Command.FORWARD -> x += dx
                        Command.BACKWARD -> x -= dx
                        Command.LEFT -> y += dy
                        Command.RIGHT -> y -= dy
                        Command.UP -> z += dz
                        Command.DOWN -> z -= dz
                        else -> Unit
                    }
                    seqId++
                }
            }
        }

        updateStateUi(getString(R.string.state_button_applied))
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
        voiceProcessor.reset()
        btnMic.text = getString(R.string.voice_stop)
        updateStateUi(getString(R.string.state_voice_listening))
        switchingToMap = true
        startActivity(Intent(this, MapActivity::class.java))
    }

    private fun stopVoiceRecognition() {
        voiceEnabled = false
        voiceProcessor.reset()
        btnMic.text = getString(R.string.voice_start)
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        if (!isFinishing && !isDestroyed && this::btnMic.isInitialized) {
            finishMapIfVisible()
        }
    }

    private fun createVoskListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                val text = extractField(hypothesis, "partial")
                if (text.isNotEmpty()) {
                    runOnUiThread {
                        tvVoiceText.text = getString(R.string.voice_text_raw, text)
                        VoiceSharedState.rawText.set(text)
                    }
                }
            }

            override fun onResult(hypothesis: String?) {
                val text = extractField(hypothesis, "text")
                if (text.isNotEmpty()) {
                    runOnUiThread {
                        tvVoiceText.text = getString(R.string.voice_text_raw, text)
                        VoiceSharedState.rawText.set(text)
                        handleVoiceInput(text)
                    }
                }
            }

            override fun onFinalResult(hypothesis: String?) {
                val text = extractField(hypothesis, "text")
                if (text.isNotEmpty()) {
                    runOnUiThread {
                        tvVoiceText.text = getString(R.string.voice_text_raw, text)
                        VoiceSharedState.rawText.set(text)
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
            }
        }
    }

    private fun handleVoiceInput(text: String) {
        val executedCommand = voiceProcessor.process(text)

        if (executedCommand == null) {
            if (voiceProcessor.state == VoiceCommandProcessor.State.WAIT_CONFIRM) {
                updateStateUi(getString(R.string.state_voice_confirm))
            } else if (matchCommand(text) == null) {
                updateStateUi(getString(R.string.state_voice_unknown))
            } else {
                updateStateUi(getString(R.string.state_voice_mismatch))
            }
            return
        }

        val command = toCommand(executedCommand) ?: run {
            updateStateUi(getString(R.string.state_voice_unknown))
            updateStateUi(getString(R.string.state_voice_confirm))
            return
        }

        applyCommand(command)
        updateStateUi(getString(R.string.state_voice_confirmed))
    }

    private fun toCommand(cmd: String): Command? {
        return when (cmd) {
            "forward" -> Command.FORWARD
            "back" -> Command.BACKWARD
            "left" -> Command.LEFT
            "right" -> Command.RIGHT
            "up" -> Command.UP
            "down" -> Command.DOWN
            "arm" -> Command.ARM
            "lock" -> Command.DISARM
            "stop" -> Command.STOP
            else -> null
        }
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
            } catch (e: Exception) {
                isModelReady = false
                Log.d(TAG, "initVoskModel failed: ${e.message}")
                runOnUiThread {
                    updateStateUi(getString(R.string.state_voice_model_failed))
                }
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
        val entries = assets.list(assetPath) ?: emptyArray()
        if (entries.isEmpty()) {
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
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
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

    private fun extractField(hypothesis: String?, key: String): String {
        if (hypothesis.isNullOrBlank()) {
            return ""
        }
        return try {
            JSONObject(hypothesis).optString(key, "").trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun updateStateUi(status: String) {
        val s = synchronized(stateLock) {
            StateSnapshot(seqId, x, y, z, yaw)
        }
        tvPosition.text = getString(
            R.string.position_value,
            s.x,
            s.y,
            s.z,
            s.yaw,
            s.seqId
        )
        tvStatus.text = getString(R.string.status_value, status)
        VoiceSharedState.status.set(status)
    }

    private fun finishMapIfVisible() {
        sendBroadcast(Intent(ACTION_CLOSE_MAP))
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

    private data class StateSnapshot(
        val seqId: Long,
        val x: Int,
        val y: Int,
        val z: Int,
        val yaw: Int,
    )

    private enum class Command {
        FORWARD,
        BACKWARD,
        LEFT,
        RIGHT,
        UP,
        DOWN,
        ARM,
        DISARM,
        STOP,
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val SEND_PERIOD_MS = 50L
        private const val REQUEST_AUDIO_PERMISSION = 1001
        private const val DEFAULT_STEP = 1
        const val ACTION_CLOSE_MAP = "com.example.dronecontroller.ACTION_CLOSE_MAP"
        const val ACTION_STOP_VOICE = "com.example.dronecontroller.ACTION_STOP_VOICE"

        private const val VOSK_SAMPLE_RATE = 16000.0f
        private const val VOSK_ASSET_MODEL_NAME = "model"
        private const val VOSK_TARGET_MODEL_NAME = "vosk-model"
        private const val DEFAULT_BROADCAST_IP = AppPrefs.DEFAULT_BROADCAST_IP
        private const val DEFAULT_PORT = AppPrefs.DEFAULT_BROADCAST_PORT
    }
}
