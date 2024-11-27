package com.example.testdatabase

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.gms.location.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.*
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import android.widget.ProgressBar
import android.view.View


@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private var checksending = true
    private var wifiRSSIJob: Job? = null
    private var headerCollectionJob: Job? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private lateinit var wifiManager: WifiManager

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var coordinatesTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var floorEditText: EditText

    //private lateinit var deviceNameEditText: EditText  // Added for device name input

    private var collectingData = false
    private var csvFileWriter: OutputStreamWriter? = null
    private var networkColumnsMap = LinkedHashMap<String, Int>()
    private var headersWritten = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private val headerCollectionDuration = 10000L // 10 seconds for header collection
    private var isBlinking = false
    private val API_URL = "https://8bwwgpdru9.execute-api.us-east-1.amazonaws.com/ips/upload"
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)
        coordinatesTextView = findViewById(R.id.coordinates_text_view)
        statusTextView = findViewById(R.id.status_text_view)
        floorEditText = findViewById(R.id.floor_edit_text)

        //deviceNameEditText = findViewById(R.id.device_name_edit_text)  // Initialize the device name EditText

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = 5000 // 5 seconds
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    updateCoordinatesTextView(location.latitude, location.longitude)
                    updateCSVFile(location.latitude, location.longitude)
                }
            }
        }

        startButton.setOnClickListener {
            startCSVFile()
            startHeaderCollection()
        }

        stopButton.setOnClickListener {
            // Hide the loading spinner
            findViewById<ProgressBar>(R.id.loading_spinner).visibility = View.GONE
            stopLocationUpdates()
            stopCSVFile()
            collectingData = false
            wifiRSSIJob?.cancel()
            headerCollectionJob?.cancel()
            releaseWakeLock()
            handler.removeCallbacksAndMessages(null)
            statusTextView.text = ""

            // Post the CSV file to the API after stopping data collection
            postCSVFileToAPI()
        }

        // Initialize the settings button
        val settingsButton: FloatingActionButton = findViewById(R.id.settings_button)
        settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        checkPermissions()
        //loadSelectedDirectory()
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        // Check for location and WiFi permissions
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.ACCESS_WIFI_STATE)
        }

        if (permissionsToRequest.isNotEmpty()) {
            // Request permissions if necessary
            requestPermissions(permissionsToRequest.toTypedArray(), WIFI_PERMISSION_REQUEST_CODE)
        }
    }

    private fun sendDataToAPI(dateTime: String, latitude: Double, longitude: Double, floor: String, rssiValues: List<Int>) {
        val jsonObject = JSONObject()
        jsonObject.put("date_time", dateTime)
        jsonObject.put("latitude", latitude)
        jsonObject.put("longitude", longitude)
        jsonObject.put("floor", floor)
        jsonObject.put("rssi_values", rssiValues)

        val requestBody = RequestBody.create("application/json; charset=utf-8".toMediaType(), jsonObject.toString())

        val request = Request.Builder()
            .url(API_URL)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace() // Log any errors
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
//                    if (!response.isSuccessful) {
//                        //println("Failed to send data: ${response.code}")
//                    } else {
//                        //println("Data sent successfully: ${response.body?.string()}")
//                    }
                }
            }
        })
    }

    // Function to collect and push data every 5 seconds
    private fun startDataUpload(dateTime: String, latitude: Double, longitude: Double, floor: String, rssiValues: List<Int>) {
        wifiRSSIJob?.cancel() // Cancel any previous job if it's still running

        wifiRSSIJob = CoroutineScope(Dispatchers.Main).launch {
            // Send data to the API
            sendDataToAPI(dateTime, latitude, longitude, floor, rssiValues)

            delay(5000)
            checksending = true
            println("done")

            // Once the data is sent, stop the job
            this.cancel() // Cancel the coroutine after sending data once
            wifiRSSIJob = null // Set the job to null to avoid memory leaks

        }
    }

    private fun postCSVFileToAPI() {
        val sharedPreferences = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val uriString = sharedPreferences.getString("selected_directory_uri", "")
        if (uriString.isNullOrEmpty()) {
            Toast.makeText(this, "Please select a save directory first.", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = Uri.parse(uriString)
        val documentFile = DocumentFile.fromTreeUri(this, uri)
        if (documentFile == null || !documentFile.isDirectory) {
            Toast.makeText(this, "Invalid directory selected.", Toast.LENGTH_SHORT).show()
            return
        }

        // Find the last CSV file created (or whichever file you want to upload)
        val csvFile = documentFile.listFiles().lastOrNull { it.name?.endsWith(".csv") == true }
        if (csvFile == null) {
            Toast.makeText(this, "No CSV file found.", Toast.LENGTH_SHORT).show()
            return
        }

        // Get the file name from the CSV file
        val fileName = csvFile.name ?: "default_name.csv"  // Use a default if the name is null

        try {
            val inputStream = contentResolver.openInputStream(csvFile.uri)
            val csvContent = inputStream?.bufferedReader().use { it?.readText() }

            // Send the CSV file content to the API
            csvContent?.let {
                sendCSVToAPI(it, fileName)  // Pass the fileName to the function
            }

        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Error reading CSV file", Toast.LENGTH_SHORT).show()
        }
    }


    private fun sendCSVToAPI(csvContent: String, fileName: String) {
        val client = OkHttpClient()

        // Prepare the API request
        val requestBody = RequestBody.create("text/csv".toMediaType(), csvContent.toByteArray())

        // Add the file name as a query parameter
        val request = Request.Builder()
            .url(API_URL + "?file_name=$fileName")  // Replace with your actual API Gateway endpoint
            .post(requestBody)
            .addHeader("Content-Type", "text/csv")  // Make sure to send the correct content type
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to upload CSV file", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@MainActivity, "CSV file uploaded successfully", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to upload CSV file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }



    @SuppressLint("SetTextI18n")
    private fun startHeaderCollection() {
        statusTextView.text = "Network Scanning..."
        headerCollectionJob = CoroutineScope(Dispatchers.Main).launch {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < headerCollectionDuration) {
                collectNetworkHeaders()
                delay(1000) // Collect headers every second for 10 seconds
            }
            startLocationUpdates()
            startDataCollection()
        }
    }

    private fun collectNetworkHeaders() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) {
            val wifiScanResults = wifiManager.scanResults
            for (result in wifiScanResults) {
                val networkName = "${result.SSID}_${result.BSSID}"
                if (!networkColumnsMap.containsKey(networkName)) {
                    networkColumnsMap[networkName] = networkColumnsMap.size + 4
                }
            }
        } else {
            // Permissions already handled in checkPermissions()
            Toast.makeText(this, "WiFi permissions are required to collect network headers", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLocationUpdates() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
        } else {
            // Permissions already handled in checkPermissions()
            Toast.makeText(this, "Location permissions are required to collect location data", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDataCollection() {
        collectingData = true
        acquireWakeLock()

        // Show the loading spinner
        findViewById<ProgressBar>(R.id.loading_spinner).visibility = View.VISIBLE

        statusTextView.text = ""
        startBlinkingText()

        wifiRSSIJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(5000) // Update WiFi RSSI every 5 seconds
                updateWifiRSSI()
            }
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
    @SuppressLint("HardwareIds")
    private fun getAndroidId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }


    private fun startCSVFile() {
        //val deviceName = deviceNameEditText.text.toString().ifEmpty { "device" } // Get the device name or use "device" as default
        val sharedPreferences = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val uriString = sharedPreferences.getString("selected_directory_uri", "")
        if (uriString.isNullOrEmpty()) {
            Toast.makeText(this, "Please select a save directory first.", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = Uri.parse(uriString)
        val documentFile = DocumentFile.fromTreeUri(this, uri)
        if (documentFile == null || !documentFile.isDirectory) {
            Toast.makeText(this, "Invalid directory selected.", Toast.LENGTH_SHORT).show()
            return
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "${getAndroidId()}_$timeStamp.csv" // Include the device name in the file name
        val newFile = documentFile.createFile("text/csv", fileName)

        if (newFile != null) {
            try {
                val outputStream = contentResolver.openOutputStream(newFile.uri)
                csvFileWriter = outputStream?.let { OutputStreamWriter(it) }
                csvFileWriter?.append("Date & Time, Latitude, Longitude, Floor")
                headersWritten = false
                csvFileWriter?.flush()
                Toast.makeText(this, "CSV file created: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                e.printStackTrace()
                Toast.makeText(this, "Error creating CSV file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopCSVFile() {
        try {
            csvFileWriter?.close()
            Toast.makeText(this, "CSV file closed", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Error closing CSV file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateWifiRSSI() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) {
            val wifiInfo = wifiManager.connectionInfo
            val rssi: Int = wifiInfo.rssi // Default value if RSSI is not available
            val wifiSSID: String = wifiInfo.ssid ?: "No WiFi"
            val wifiRSSIString = "WiFi: $wifiSSID, RSSI: $rssi dBm"
            runOnUiThread {
                coordinatesTextView.text = wifiRSSIString // Assuming you have a separate TextView for WiFi info
            }
        } else {
            Toast.makeText(this, "WiFi permissions are required to access WiFi information", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == WIFI_PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (wifiRSSIJob == null) {
                    wifiRSSIJob = CoroutineScope(Dispatchers.Main).launch {
                        while (isActive) {
                            delay(5000) // Update WiFi RSSI every 5 seconds
                            updateWifiRSSI()
                        }
                    }
                }
            } else {
                Toast.makeText(this, "WiFi permissions are required to access WiFi information", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateCoordinatesTextView(latitude: Double, longitude: Double) {
        val floor = floorEditText.text.toString()
        coordinatesTextView.text = "Latitude: $latitude, Longitude: $longitude, Floor: $floor"
    }

    private fun updateCSVFile(latitude: Double, longitude: Double) {
        if (!headersWritten) {
            val headerLine = "Date & Time, Latitude, Longitude, Floor" + networkColumnsMap.keys.joinToString(", ", prefix = ", ")
            csvFileWriter?.append("\n")
            csvFileWriter?.append(headerLine)
            csvFileWriter?.append("\n")
            headersWritten = true
        }

        val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val floor = floorEditText.text.toString()
        var line = "\n$dateTime, $latitude, $longitude, $floor"

        // Declare rssiValues outside the if block
        val rssiValues = Array(networkColumnsMap.size) { "-100" }

        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val wifiScanResults = wifiManager.scanResults

            for ((networkKey, columnIndex) in networkColumnsMap) {
                val foundResult = wifiScanResults.find { it.SSID + "_" + it.BSSID == networkKey }
                val rssi = foundResult?.level ?: -100
                rssiValues[columnIndex - 4] = rssi.toString()
            }

            // Add RSSI values to the line
            line += rssiValues.joinToString(", ", prefix = ", ")
        }

        try {
            csvFileWriter?.append(line)
            csvFileWriter?.flush()

            // Push data to API
//            if (checksending) {
//                checksending = false
//                println("Welcome" )
//                startDataUpload(dateTime,
//                    latitude,
//                    longitude,
//                    floor,
//                    rssiValues.map { it.toIntOrNull() ?: -100 })
//                println("Hello: $checksending" )
//            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Error writing to CSV file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::WakeLockTag")
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.release()
        wakeLock = null
    }

    private fun startBlinkingText() {
        val runnable = object : Runnable {
            @SuppressLint("SetTextI18n")
            override fun run() {
                if (isBlinking) {
                    statusTextView.text = ""
                } else {
                    statusTextView.text = "Data Collecting in Process..."
                    statusTextView.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_light))
                }
                isBlinking = !isBlinking
                handler.postDelayed(this, 500)
            }
        }
        handler.post(runnable)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu) // Add the settings icon to the action bar if you have a menu
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val WIFI_PERMISSION_REQUEST_CODE = 1002
    }
}
