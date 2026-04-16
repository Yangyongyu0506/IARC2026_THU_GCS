package com.example.dronecontroller

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit

class SettingsActivity : AppCompatActivity() {

    private lateinit var etDx: EditText
    private lateinit var etDy: EditText
    private lateinit var etDz: EditText
    private lateinit var etBroadcastIp: EditText
    private lateinit var etBroadcastPort: EditText
    private lateinit var etListenIp: EditText
    private lateinit var etListenPort: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etDx = findViewById(R.id.etDx)
        etDy = findViewById(R.id.etDy)
        etDz = findViewById(R.id.etDz)
        etBroadcastIp = findViewById(R.id.etBroadcastIp)
        etBroadcastPort = findViewById(R.id.etBroadcastPort)
        etListenIp = findViewById(R.id.etListenIp)
        etListenPort = findViewById(R.id.etListenPort)

        val btnSave: Button = findViewById(R.id.btnSave)

        val prefs = getSharedPreferences(AppPrefs.PREFS_NAME, MODE_PRIVATE)
        etDx.setText(prefs.getString(AppPrefs.KEY_DX, AppPrefs.DEFAULT_STEP.toString()))
        etDy.setText(prefs.getString(AppPrefs.KEY_DY, AppPrefs.DEFAULT_STEP.toString()))
        etDz.setText(prefs.getString(AppPrefs.KEY_DZ, AppPrefs.DEFAULT_STEP.toString()))

        etBroadcastIp.setText(
            prefs.getString(AppPrefs.KEY_BROADCAST_IP, AppPrefs.DEFAULT_BROADCAST_IP)
                ?: AppPrefs.DEFAULT_BROADCAST_IP
        )
        etBroadcastPort.setText(
            prefs.getString(AppPrefs.KEY_BROADCAST_PORT, AppPrefs.DEFAULT_BROADCAST_PORT.toString())
                ?: AppPrefs.DEFAULT_BROADCAST_PORT.toString()
        )
        etListenIp.setText(
            prefs.getString(AppPrefs.KEY_LISTEN_IP, AppPrefs.DEFAULT_LISTEN_IP)
                ?: AppPrefs.DEFAULT_LISTEN_IP
        )
        etListenPort.setText(
            prefs.getString(AppPrefs.KEY_LISTEN_PORT, AppPrefs.DEFAULT_LISTEN_PORT.toString())
                ?: AppPrefs.DEFAULT_LISTEN_PORT.toString()
        )

        btnSave.setOnClickListener {
            val dx = etDx.text.toString().trim().toIntOrNull()
            val dy = etDy.text.toString().trim().toIntOrNull()
            val dz = etDz.text.toString().trim().toIntOrNull()
            val broadcastIp = etBroadcastIp.text.toString().trim()
            val broadcastPort = etBroadcastPort.text.toString().trim().toIntOrNull()
            val listenIp = etListenIp.text.toString().trim()
            val listenPort = etListenPort.text.toString().trim().toIntOrNull()

            if (dx == null || dy == null || dz == null || dx <= 0 || dy <= 0 || dz <= 0) {
                Toast.makeText(this, R.string.settings_invalid_values, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (broadcastIp.isEmpty() || listenIp.isEmpty()) {
                Toast.makeText(this, R.string.settings_invalid_ip, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (broadcastPort == null || broadcastPort !in 1..65535 || listenPort == null || listenPort !in 1..65535) {
                Toast.makeText(this, R.string.settings_invalid_port, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit {
                putString(AppPrefs.KEY_DX, dx.toString())
                putString(AppPrefs.KEY_DY, dy.toString())
                putString(AppPrefs.KEY_DZ, dz.toString())
                putString(AppPrefs.KEY_BROADCAST_IP, broadcastIp)
                putString(AppPrefs.KEY_BROADCAST_PORT, broadcastPort.toString())
                putString(AppPrefs.KEY_LISTEN_IP, listenIp)
                putString(AppPrefs.KEY_LISTEN_PORT, listenPort.toString())
            }

            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
