package com.example.samarium

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import com.google.android.gms.maps.model.Marker
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.json.JSONArray


@SuppressLint("MissingPermission")
class MapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<LinearLayout>
    private lateinit var analyzeButton: Button
    private lateinit var analysisResultsView: TextView

    //    private val client = OkHttpClient()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val HUGGING_FACE_API_KEY = "your_api_key"

    //    private val API_URL = "https://api-inference.huggingface.co/models/google/flan-t5-large"
//    private val API_URL = "https://api-inference.huggingface.co/models/mistralai/Mistral-7B-Instruct"
    private val API_URL = "https://api-inference.huggingface.co/models/tiiuae/falcon-7b-instruct"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        dbHelper = DatabaseHelper(this)

        // Initialize bottom sheet
        val bottomSheet = findViewById<LinearLayout>(R.id.bottom_sheet)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)

        analyzeButton = findViewById(R.id.analyze_button)
        analysisResultsView = findViewById(R.id.analysis_results)

        analyzeButton.setOnClickListener {
            analyzeCoverageData()
        }

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    private fun analyzeCoverageData() {
        val data = dbHelper.getAllData()
        if (data.isEmpty()) {
            analysisResultsView.text = "No data available for analysis"
            return
        }

        // Prepare data for analysis
        val analysisData = prepareDataForAnalysis()
        Log.d("MapActivity", " here u must see entry.latitude: ${data} entries")
        // Make API call
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = sendDataToAPI(analysisData)
                withContext(Dispatchers.Main) {
                    displayAnalysisResults(response)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    analysisResultsView.text = "Error analyzing data: ${e.message}"
                }
            }
        }
    }

    private fun prepareDataForAnalysis(): String {
        val data = dbHelper.getAllData()
        val totalPoints = data.size
        val technologyCounts = data.groupBy { it.technology }.mapValues { it.value.size }
        val poorSignalPoints = data.count { entry ->
            when (entry.technology) {
                "GSM" -> getGsmSignalQuality(entry.signalStrength) <= 1
                "CDMA" -> getCdmaSignalQuality(entry.signalStrength) <= 1
                "WCDMA" -> getWcdmaSignalQuality(entry.signalStrength) <= 1
                else -> false
            }
        }
        val poorCoveragePercentage =
            if (totalPoints > 0) (poorSignalPoints * 100) / totalPoints else 0

        return """
        Analyze the following cellular coverage data and provide a structured response:
        
        **Total Data Points:** $totalPoints  
        **Technologies Used:** ${technologyCounts.keys.joinToString()}  
        **Poor Coverage Points:** $poorSignalPoints  
        **Poor Coverage Percentage:** $poorCoveragePercentage%  

        ### Expected Analysis:
        1️⃣ **Coverage Breakdown**: Analyze the coverage distribution based on different generations (2G, 3G, 4G, 5G).  
        2️⃣ **Quality Assessment**: Evaluate signal strength, consistency, and reliability of coverage.  
        3️⃣ **Poor Coverage Areas**: Highlight zones with weak signals and provide percentage breakdown.  
        4️⃣ **Improvement Suggestions**: Recommend actions to improve network quality.  
        5️⃣ **Notable Trends**: Identify patterns, anomalies, and potential reasons for signal variations.  

        Provide a structured and detailed analysis.
    """.trimIndent()
    }


    private suspend fun sendDataToAPI(analysisData: String): String {
        Log.d("MapActivity", "Sending data to API: $analysisData")

        val json = JSONObject()
        json.put("inputs", analysisData)

        val mediaType = "application/json".toMediaType()
        val requestBody = json.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $HUGGING_FACE_API_KEY")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Unexpected code $response")
        }

        val responseData = response.body?.string() ?: throw IOException("Empty response body")
        Log.d("MapActivity", "Model's Inference: $responseData")

        // Parse the response to extract the generated text
        val jsonResponse = JSONArray(responseData)
        val generatedText = jsonResponse.getJSONObject(0).getString("generated_text")

        return generatedText
    }

    private fun displayAnalysisResults(analysisResponse: String) {
        analysisResultsView.text = analysisResponse
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onMapReady(googleMap: GoogleMap) {
        Log.d("MapActivity", "No data found in the database.")
        mMap = googleMap

        val data = dbHelper.getAllData()
        Log.d("MapActivity", "Data retrieved: ${data.size} entries")

        if (data.isEmpty()) {
            Log.d("MapActivity", "No data found in the database.")
            return
        }

        Log.d("MapActivity", " here u must see entry.latitude: ${data} entries")
        for (entry in data) {
            val location = LatLng(entry.latitude, entry.longitude)
            // Determine the signal quality and color based on the technology
            val signalQuality = when (entry.technology) {
                "GSM" -> getGsmSignalQuality(entry.signalStrength)
                "CDMA" -> getCdmaSignalQuality(entry.signalStrength)
                "WCDMA" -> getWcdmaSignalQuality(entry.signalStrength)
                else -> -1
            }
            val color = getColorForSignalQuality(signalQuality)

            // Format snippet with metrics
            val markerOptions = MarkerOptions()
                .position(location)
                .title("Signal Quality: ${entry.signalQuality}")
                .snippet(
                    """
            Location: (${entry.latitude}, ${entry.longitude})
            PLMN ID: ${entry.plmnId}
            LAC: ${entry.lac}
            RAC: ${entry.rac}
            TAC: ${entry.tac}
            Cell ID: ${entry.cellId}
            Band: ${entry.band}
            ARFCAN: ${entry.arfcan}
            Signal Strength: ${entry.signalStrength} [dBm] (${getSignalQualityText(signalQuality)})
            Technology: ${entry.technology}
            Node ID: ${entry.nodeId}
            Scan Tech: ${entry.scanTech}
            Scan Serving Signal Power: ${entry.scanServingSigPow} [dBm] (${
                        getSignalQualityText(
                            signalQuality
                        )
                    })
            Distance Walked: ${entry.distanceWalked} m
            Timestamp: ${formatTimestamp(entry.timestamp)}
        """.trimIndent()
                )
                .icon(BitmapDescriptorFactory.defaultMarker(color))

            mMap.addMarker(markerOptions)

            // Drawing a path
            val currentIndex = data.indexOf(entry)
            if (currentIndex > 0) {
                val previousLocation =
                    LatLng(data[currentIndex - 1].latitude, data[currentIndex - 1].longitude)
                mMap.addPolyline(
                    PolylineOptions().add(previousLocation, location).color(color.toInt())
                )
            }
        }

        // Move camera to the first location
        if (data.isNotEmpty()) {
            val firstLocation = LatLng(data[0].latitude, data[0].longitude)
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(firstLocation, 15f))
        }

        // Custom info window adapter
        mMap.setInfoWindowAdapter(object : GoogleMap.InfoWindowAdapter {
            override fun getInfoWindow(marker: Marker): View? {
                return null // Use default frame
            }

            override fun getInfoContents(marker: Marker): View? {
                val infoView = layoutInflater.inflate(R.layout.custom_info_window, null)
                val title = infoView.findViewById<TextView>(R.id.title)
                val snippet = infoView.findViewById<TextView>(R.id.snippet)

                title.text = marker.title
                snippet.text = marker.snippet

                return infoView
            }
        })

        mMap.setOnMarkerClickListener { marker ->
            marker.showInfoWindow()
            true
        }
    }

    fun formatTimestamp(timestamp: String): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val date = Date(timestamp.toLong())
        return sdf.format(date)
    }

    // Define signal quality ranges and corresponding colors
    fun getColorForSignalQuality(signalQuality: Int): Float {
        return when (signalQuality) {
            4 -> BitmapDescriptorFactory.HUE_BLUE   // Very good
            3 -> BitmapDescriptorFactory.HUE_GREEN  // Good
            2 -> BitmapDescriptorFactory.HUE_YELLOW // Fair
            1 -> BitmapDescriptorFactory.HUE_ORANGE // Poor
            else -> BitmapDescriptorFactory.HUE_RED // Very poor or unknown
        }
    }

    fun getGsmSignalQuality(signalStrength: Int): Int {
        return when {
            signalStrength >= -75 -> 4 // Very good
            signalStrength >= -80 -> 3  // Good
            signalStrength >= -90 -> 2  // Fair
            signalStrength > -100 -> 1   // Poor
            else -> -1 // Unknown or no signal
        }
    }

    fun getCdmaSignalQuality(signalStrength: Int): Int {
        return when {
            signalStrength >= -70 -> 4 // Very good
            signalStrength >= -80 -> 3 // Good
            signalStrength >= -90 -> 2 // Fair
            signalStrength > -100 -> 1  // Poor
            else -> -1 // Unknown or no signal
        }
    }

    fun getSignalQualityText(signalQuality: Int): String {
        return when (signalQuality) {
            4 -> "Very good"
            3 -> "Good"
            2 -> "Fair"
            1 -> "Poor"
            else -> "Very poor or unknown"
        }
    }

    fun getWcdmaSignalQuality(signalStrength: Int): Int {
        return when {
            signalStrength >= -70 -> 4 // Very good
            signalStrength >= -80 -> 3 // Good
            signalStrength >= -90 -> 2 // Fair
            signalStrength > -100 -> 1  // Poor
            else -> -1 // Unknown or no signal
        }
    }
}
