package de.doem.kompass

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.gms.location.*
import de.doem.kompass.databinding.ActivityMainBinding
import java.util.Calendar
import kotlin.math.*

class MainActivity : AppCompatActivity(), SensorEventListener {

    private val DOM_LAT = 50.94136
    private val DOM_LNG = 6.95827

    private lateinit var binding: ActivityMainBinding

    // ── Theme ─────────────────────────────────────────────────────────────
    private lateinit var colors: ThemeHelper.Colors
    private var activeTheme = ThemeHelper.AppTheme.NIGHT
    private val themeCheckHandler = Handler(Looper.getMainLooper())
    private val themeCheckRunnable = object : Runnable {
        override fun run() {
            if (ThemeHelper.currentTheme() != activeTheme) recreate()
            themeCheckHandler.postDelayed(this, 60_000L)
        }
    }

    // ── Sensors ───────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager

    // Primary: TYPE_ROTATION_VECTOR (system-fused, always responsive)
    private var rotationVectorSensor: Sensor? = null
    private var useRotationVector = false

    // Fallback for old devices without rotation vector
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private val accelReading = FloatArray(3)
    private val magReading   = FloatArray(3)
    private var hasAccel = false
    private var hasMag   = false

    private val rotationMat  = FloatArray(9)
    private val orientation  = FloatArray(3)
    private var smoothAzimuth   = 0f
    private var currentAzimuth  = 0f
    private var bearingToDom    = 0f
    private var distanceKm      = 0.0
    private var lastNeedleAngle = 0f
    private var lastRoseAngle   = 0f

    // ── Location ──────────────────────────────────────────────────────────
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var hasLocation = false

    // ── Sayings ───────────────────────────────────────────────────────────
    private var lastSayingBracket = ""
    private var cachedSaying      = ""

    // ── Permissions ───────────────────────────────────────────────────────
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                startLocationUpdates()
            } else {
                binding.tvStatus.text = getString(R.string.status_no_gps)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        activeTheme = ThemeHelper.currentTheme()
        setTheme(ThemeHelper.themeResId(activeTheme))
        colors = ThemeHelper.Colors(this, activeTheme)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom,
                               left = bars.left, right = bars.right)
            insets
        }

        applyThemeColors()
        setupSensors()
        setupLocation()
        setupWebcamButton()
        themeCheckHandler.postDelayed(themeCheckRunnable, 60_000L)
        checkBirthday()
    }

    // ── Geburtstag ────────────────────────────────────────────────────────
    private val BIRTHDAY_DAY   = 6
    private val BIRTHDAY_MONTH = 6

    private fun checkBirthday() {
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.DAY_OF_MONTH) == BIRTHDAY_DAY &&
            cal.get(Calendar.MONTH) + 1     == BIRTHDAY_MONTH) {
            Handler(Looper.getMainLooper()).postDelayed({ showBirthdayDialog() }, 1200)
        }
    }

    private fun showBirthdayDialog() {
        AlertDialog.Builder(this)
            .setTitle("Alles Jode zum Jebootsdaach!")
            .setMessage("Hück es ding Daach – loss et kraache!\n\nUn denk dran: Et hät noch immer jot jejange!")
            .setPositiveButton("Un jetz mal Butter bei de Fische:\nAlles joot – un jetz her met däm Geschenk!") { d, _ -> d.dismiss() }
            .setCancelable(true)
            .show()
            .also { it.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(colors.primary) }
    }

    // ── Theme colors ──────────────────────────────────────────────────────
    private fun applyThemeColors() {
        binding.root.setBackgroundColor(colors.bgDark)
        binding.viewGradient.setBackgroundColor(colors.bgMid)
        binding.tvAppTitle.setTextColor(colors.primaryLight)
        binding.tvSubtitle.setTextColor(colors.primaryDim)
        binding.dividerTop.setBackgroundColor(colors.divider)
        binding.tvCompassStatus.setTextColor(colors.textDim)
        binding.cardDistance.setCardBackgroundColor(colors.cardBg)
        binding.tvDistanceLabel.setTextColor(colors.primaryDim)
        binding.tvDistance.setTextColor(colors.primaryLight)
        binding.tvStatus.setTextColor(colors.textSecondary)
        binding.btnWebcam.setBackgroundColor(colors.btnBg)
        binding.btnWebcam.setTextColor(colors.btnText)
        binding.btnWebcam.icon?.setTint(colors.btnText)
        binding.imgAppLogo.setImageResource(
            if (activeTheme == ThemeHelper.AppTheme.DAY) R.drawable.ic_app_logo_day
            else R.drawable.ic_app_logo_night
        )
        ObjectAnimator.ofFloat(binding.imgAppLogo, "alpha", 1f, 0.85f, 1f).apply {
            duration = 2800; repeatCount = ObjectAnimator.INFINITE; start()
        }
    }

    // ── Sensors ───────────────────────────────────────────────────────────
    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVectorSensor != null) {
            useRotationVector = true
        } else {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            magnetometer  = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            if (accelerometer == null || magnetometer == null)
                binding.tvCompassStatus.text = getString(R.string.no_compass_sensor)
        }
    }

    override fun onResume() {
        super.onResume()
        if (useRotationVector) {
            rotationVectorSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        } else {
            accelerometer?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magnetometer?.also  { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        themeCheckHandler.removeCallbacks(themeCheckRunnable)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMat, event.values)
                SensorManager.getOrientation(rotationMat, orientation)
                val raw = ((Math.toDegrees(orientation[0].toDouble()).toFloat()) + 360f) % 360f
                currentAzimuth = smoothAngle(smoothAzimuth, raw).also { smoothAzimuth = it }
                updateUI()
            }
            Sensor.TYPE_ACCELEROMETER -> { lowPass(event.values, accelReading); hasAccel = true; if (hasAccel && hasMag) updateFromRaw() }
            Sensor.TYPE_MAGNETIC_FIELD -> { lowPass(event.values, magReading); hasMag = true; if (hasAccel && hasMag) updateFromRaw() }
        }
    }

    private fun updateFromRaw() {
        if (!SensorManager.getRotationMatrix(rotationMat, null, accelReading, magReading)) return
        SensorManager.getOrientation(rotationMat, orientation)
        val raw = ((Math.toDegrees(orientation[0].toDouble()).toFloat()) + 360f) % 360f
        currentAzimuth = smoothAngle(smoothAzimuth, raw).also { smoothAzimuth = it }
        updateUI()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun lowPass(input: FloatArray, output: FloatArray) {
        val alpha = 0.12f
        for (i in input.indices) output[i] = output[i] + alpha * (input[i] - output[i])
    }

    private fun smoothAngle(current: Float, target: Float): Float {
        val alpha = 0.15f
        var diff = target - current
        while (diff > 180f)  diff -= 360f
        while (diff < -180f) diff += 360f
        return (current + alpha * diff + 360f) % 360f
    }

    // ── Location ──────────────────────────────────────────────────────────
    private fun setupLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) { result.lastLocation?.let { onNewLocation(it) } }
        }
        checkAndRequestLocationPermission()
    }

    private fun checkAndRequestLocationPermission() {
        val fine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) startLocationUpdates()
        else {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            binding.tvStatus.text = resources.getStringArray(R.array.sayings_gps_searching).random()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val fine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L).setMinUpdateIntervalMillis(1500L).build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        fusedLocationClient.lastLocation.addOnSuccessListener { it?.let { loc -> onNewLocation(loc) } }
    }

    private fun onNewLocation(location: Location) {
        hasLocation  = true
        bearingToDom = calcBearing(location.latitude, location.longitude, DOM_LAT, DOM_LNG).toFloat()
        distanceKm   = calcDistance(location.latitude, location.longitude, DOM_LAT, DOM_LNG)
        updateUI()
    }

    private fun calcBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val φ1 = Math.toRadians(lat1); val φ2 = Math.toRadians(lat2); val Δλ = Math.toRadians(lon2 - lon1)
        return (Math.toDegrees(atan2(sin(Δλ)*cos(φ2), cos(φ1)*sin(φ2)-sin(φ1)*cos(φ2)*cos(Δλ))) + 360) % 360
    }

    private fun calcDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2-lat1); val dLon = Math.toRadians(lon2-lon1)
        val a = sin(dLat/2).pow(2) + cos(Math.toRadians(lat1))*cos(Math.toRadians(lat2))*sin(dLon/2).pow(2)
        return 6371.0 * 2 * atan2(sqrt(a), sqrt(1-a))
    }

    private fun bracketFor(km: Double) = when {
        km < 0.1 -> "at_dom"; km < 0.5 -> "almost_there"; km < 2.0 -> "very_close"
        km < 10.0 -> "close"; km < 50.0 -> "getting_closer"; km < 200.0 -> "medium_far"
        km < 500.0 -> "far"; else -> "very_far"
    }

    private fun sayingForDistance(km: Double): String {
        val bracket = bracketFor(km)
        if (bracket != lastSayingBracket || cachedSaying.isEmpty()) {
            val resId = resources.getIdentifier("sayings_$bracket", "array", packageName)
            cachedSaying = if (resId != 0) resources.getStringArray(resId).random() else "Kölle Alaaf!"
            lastSayingBracket = bracket
        }
        return cachedSaying
    }

    private fun updateUI() {
        rotateTo(binding.imgCompassRose, -currentAzimuth, lastRoseAngle) { lastRoseAngle = it }
        if (hasLocation) {
            rotateTo(binding.imgDomArrow, bearingToDom - currentAzimuth, lastNeedleAngle) { lastNeedleAngle = it }
            binding.tvDistance.text      = formatDistance(distanceKm)
            binding.tvStatus.text        = sayingForDistance(distanceKm)
            binding.tvCompassStatus.text = getString(R.string.compass_heading, currentAzimuth.toInt(), bearingToDom.toInt())
        } else {
            binding.tvStatus.text        = resources.getStringArray(R.array.sayings_gps_searching).random()
            binding.tvCompassStatus.text = getString(R.string.compass_heading_only, currentAzimuth.toInt())
        }
    }

    private fun rotateTo(view: android.view.View, target: Float, last: Float, onUpdate: (Float) -> Unit) {
        var delta = ((target - last + 180f) % 360f - 180f + 360f) % 360f
        if (delta > 180f) delta -= 360f
        val newAngle = last + delta
        onUpdate(newAngle)
        ObjectAnimator.ofFloat(view, "rotation", view.rotation, newAngle).apply {
            duration = 200; interpolator = DecelerateInterpolator(); start()
        }
    }

    private fun formatDistance(km: Double) = when {
        km < 1.0 -> getString(R.string.distance_meters, (km * 1000).toInt())
        else     -> getString(R.string.distance_km, km).replace(".", ",")
    }

    private fun setupWebcamButton() {
        binding.btnWebcam.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/live/kodtgRure8Y")))
        }
    }
}
