package com.example.testdatabase

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
//import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile

@Suppress("DEPRECATION")
class SettingsActivity : AppCompatActivity() {

    private lateinit var selectDirectoryButton: Button
    private lateinit var selectedDirectoryTextView: TextView
    private lateinit var androidIdTextView: TextView // New TextView for Android ID
    private val DIRECTORY_REQUEST_CODE = 1003

    @SuppressLint("HardwareIds", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setting)

        selectDirectoryButton = findViewById(R.id.select_directory_button)
        selectedDirectoryTextView = findViewById(R.id.selected_directory_text_view)
        androidIdTextView = findViewById(R.id.android_id_text_view) // Reference to the Android ID TextView

        loadSelectedDirectory()

        // Retrieve and display Android ID
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        androidIdTextView.text = "Android ID: $androidId"

        selectDirectoryButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            startActivityForResult(intent, DIRECTORY_REQUEST_CODE)
        }
    }

    private fun loadSelectedDirectory() {
        val sharedPreferences = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val uriString = sharedPreferences.getString("selected_directory_uri", "")
        selectedDirectoryTextView.text = if (uriString.isNullOrEmpty()) {
            "No directory selected"
        } else {
            Uri.parse(uriString).lastPathSegment
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DIRECTORY_REQUEST_CODE && resultCode == RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                // Persist the URI permission so the app can access it later
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                // Save the selected directory URI in SharedPreferences
                val sharedPreferences = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                sharedPreferences.edit().putString("selected_directory_uri", uri.toString()).apply()

                // Display the directory name
                val documentFile = DocumentFile.fromTreeUri(this, uri)
                selectedDirectoryTextView.text = documentFile?.name ?: "Unknown Directory"
            }
        }
    }

}
