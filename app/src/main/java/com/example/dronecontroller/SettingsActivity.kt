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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etDx = findViewById(R.id.etDx)
        etDy = findViewById(R.id.etDy)
        etDz = findViewById(R.id.etDz)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        etDx.setText(prefs.getString(KEY_DX, DEFAULT_STEP.toString()))
        etDy.setText(prefs.getString(KEY_DY, DEFAULT_STEP.toString()))
        etDz.setText(prefs.getString(KEY_DZ, DEFAULT_STEP.toString()))

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val dx = etDx.text.toString().trim().toIntOrNull()
            val dy = etDy.text.toString().trim().toIntOrNull()
            val dz = etDz.text.toString().trim().toIntOrNull()

            if (dx == null || dy == null || dz == null || dx <= 0 || dy <= 0 || dz <= 0) {
                Toast.makeText(this, R.string.settings_invalid_values, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit {
                putString(KEY_DX, dx.toString())
                putString(KEY_DY, dy.toString())
                putString(KEY_DZ, dz.toString())
            }

            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        private const val PREFS_NAME = "controller_prefs"
        private const val KEY_DX = "dx"
        private const val KEY_DY = "dy"
        private const val KEY_DZ = "dz"
        private const val DEFAULT_STEP = 1
    }
}
