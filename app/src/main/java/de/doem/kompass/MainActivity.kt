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
import android.os.HandlerThread
import android.os.Looper
import android.view.Choreographer
import android.view.animation.LinearInterpolator
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

    // FIX 4: Dedicated background thread for sensor processing
    // Sensor callbacks no longer block the Main/UI thread
    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler

    private var rotationVectorSensor: Sensor? = null
    private var useRotationVector = false
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private val accelReading = FloatArray(3)
    private val magReading   = FloatArray(3)
    private var hasAccel = false
    private var hasMag   = false
    private val rotationMat = FloatArray(9)
    private val orientation = FloatArray(3)

    // FIX 1: Higher alpha = faster, more responsive compass
    // 0.15 (old) took ~580ms to reach target. 0.35 takes ~250ms.
    private val SMOOTH_ALPHA = 0.35f

    // FIX 3: Only update UI when angle changed more than threshold
    private val MIN_DELTA_DEG = 0.8f

    @Volatile private var smoothAzimuth   = 0f
    @Volatile private var currentAzimuth  = 0f
    private var bearingToDom    = 0f
    private var distanceKm      = 0.0
    private var lastRoseAngle   = 0f
    private var lastNeedleAngle = 0f

    // FIX 2: Reusable animators – created once, updated on each frame
    private var roseAnimator:   ObjectAnimator? = null
    private var needleAnimator: ObjectAnimator? = null

    // FIX 3: Choreographer-driven UI updates – synced to display refresh rate
    // instead of triggering on every sensor event (50Hz vs 60/120Hz mismatch)
    private var choreographerScheduled = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val frameCallback = Choreographer.FrameCallback {
        choreographerScheduled = false
        renderFrame()
    }

    // ── Location ──────────────────────────────────────────────────────────
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var hasLocation = false

    // ── Sayings cache ─────────────────────────────────────────────────────
    private var lastSayingBracket = ""
    private var cachedSaying      = ""
    // FIX 3: Cache last displayed values to skip redundant setText calls
    private var lastDisplayedAzimuth = -1
    private var lastDisplayedBearing = -1

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

    // ─────────────────────────────────────────────────────────────────────
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

        // FIX 2: Pre-create reusable animators with hardware layer
        // Hardware layer = GPU compositing, no CPU redraw on rotation
        binding.imgCompassRose.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        binding.imgDomArrow.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        roseAnimator = ObjectAnimator.ofFloat(binding.imgCompassRose, "rotation", 0f).apply {
            duration = 150
            interpolator = LinearInterpolator()
        }
        needleAnimator = ObjectAnimator.ofFloat(binding.imgDomArrow, "rotation", 0f).apply {
            duration = 150
            interpolator = LinearInterpolator()
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
            cal.get(Calendar.MONTH) + 1 == BIRTHDAY_MONTH) {
            mainHandler.postDelayed({ showBirthdayDialog() }, 1200)
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

        // FIX 4: Start background sensor thread
        sensorThread = HandlerThread("SensorThread", android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE).also { it.start() }
        sensorHandler = Handler(sensorThread.looper)

        if (useRotationVector) {
            rotationVectorSensor?.let {
                // FIX 4: Register on background thread, not Main thread
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
            }
        } else {
            accelerometer?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, sensorHandler) }
            magnetometer?.also  { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, sensorHandler) }
        }
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        sensorThread.quitSafely()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        themeCheckHandler.removeCallbacks(themeCheckRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        roseAnimator?.cancel()
        needleAnimator?.cancel()
        binding.imgCompassRose.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
        binding.imgDomArrow.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
    }

    // FIX 4: Called on background thread – pure math, no UI touches
    override fun onSensorChanged(event: SensorEvent) {
        val raw: Float = when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMat, event.values)
                SensorManager.getOrientation(rotationMat, orientation)
                ((Math.toDegrees(orientation[0].toDouble()).toFloat()) + 360f) % 360f
            }
            Sensor.TYPE_ACCELEROMETER -> {
                lowPass(event.values, accelReading); hasAccel = true
                if (!hasAccel || !hasMag) return
                if (!SensorManager.getRotationMatrix(rotationMat, null, accelReading, magReading)) return
                SensorManager.getOrientation(rotationMat, orientation)
                ((Math.toDegrees(orientation[0].toDouble()).toFloat()) + 360f) % 360f
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lowPass(event.values, magReading); hasMag = true
                if (!hasAccel || !hasMag) return
                if (!SensorManager.getRotationMatrix(rotationMat, null, accelReading, magReading)) return
                SensorManager.getOrientation(rotationMat, orientation)
                ((Math.toDegrees(orientation[0].toDouble()).toFloat()) + 360f) % 360f
            }
            else -> return
        }

        // FIX 1: Smooth on background thread with higher alpha
        val smoothed = smoothAngle(smoothAzimuth, raw)
        smoothAzimuth = smoothed

        // FIX 3: Only schedule a frame if angle changed meaningfully
        val delta = abs(smoothed - currentAzimuth)
        val wrappedDelta = if (delta > 180f) 360f - delta else delta
        if (wrappedDelta < MIN_DELTA_DEG) return

        currentAzimuth = smoothed

        // FIX 3: Post to Choreographer – batches all sensor updates
        // into exactly one UI update per display frame (60 or 120Hz)
        if (!choreographerScheduled) {
            choreographerScheduled = true
            mainHandler.post {
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun lowPass(input: FloatArray, output: FloatArray) {
        val alpha = 0.12f
        for (i in input.indices) output[i] = output[i] + alpha * (input[i] - output[i])
    }

    // FIX 1: alpha = 0.35 → much faster response on all devices
    private fun smoothAngle(current: Float, target: Float): Float {
        var diff = target - current
        while (diff > 180f)  diff -= 360f
        while (diff < -180f) diff += 360f
        return (current + SMOOTH_ALPHA * diff + 360f) % 360f
    }

    // ── FIX 3: Single render call per display frame via Choreographer ─────
    private fun renderFrame() {
        val azimuth = currentAzimuth

        // Compass rose counter-rotates to stay North-up
        rotateTo(binding.imgCompassRose, -azimuth, lastRoseAngle, roseAnimator) { lastRoseAngle = it }

        if (hasLocation) {
            rotateTo(binding.imgDomArrow, bearingToDom - azimuth, lastNeedleAngle, needleAnimator) { lastNeedleAngle = it }

            // FIX 3: Only update text if value actually changed
            val azInt = azimuth.toInt()
            val brInt = bearingToDom.toInt()
            if (azInt != lastDisplayedAzimuth || brInt != lastDisplayedBearing) {
                binding.tvCompassStatus.text = getString(R.string.compass_heading, azInt, brInt)
                lastDisplayedAzimuth = azInt
                lastDisplayedBearing = brInt
            }
        } else {
            binding.tvCompassStatus.text = getString(R.string.compass_heading_only, azimuth.toInt())
        }
    }

    // FIX 2: Reuse existing animator – just update target value
    private fun rotateTo(
        view: android.view.View,
        target: Float,
        last: Float,
        animator: ObjectAnimator?,
        onUpdate: (Float) -> Unit
    ) {
        var delta = ((target - last + 180f) % 360f - 180f + 360f) % 360f
        if (delta > 180f) delta -= 360f
        val newAngle = last + delta
        onUpdate(newAngle)

        if (animator != null) {
            animator.cancel()
            animator.setFloatValues(view.rotation, newAngle)
            animator.start()
        } else {
            view.rotation = newAngle
        }
    }

    // ── Location ──────────────────────────────────────────────────────────
    private fun setupLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onNewLocation(it) }
            }
        }
        checkAndRequestLocationPermission()
    }

    private fun checkAndRequestLocationPermission() {
        val fine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        } else {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
            binding.tvStatus.text = resources.getStringArray(R.array.sayings_gps_searching).random()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val fine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(1500L).build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        fusedLocationClient.lastLocation.addOnSuccessListener { it?.let { loc -> onNewLocation(loc) } }
    }

    private fun onNewLocation(location: Location) {
        hasLocation  = true
        bearingToDom = calcBearing(location.latitude, location.longitude, DOM_LAT, DOM_LNG).toFloat()
        distanceKm   = calcDistance(location.latitude, location.longitude, DOM_LAT, DOM_LNG)
        // Update distance text and sayings (low frequency – only on GPS update)
        binding.tvDistance.text = formatDistance(distanceKm)
        binding.tvStatus.text   = sayingForDistance(distanceKm)
    }

    private fun calcBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val φ1 = Math.toRadians(lat1); val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lon2 - lon1)
        return (Math.toDegrees(atan2(sin(Δλ)*cos(φ2), cos(φ1)*sin(φ2)-sin(φ1)*cos(φ2)*cos(Δλ))) + 360) % 360
    }

    private fun calcDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2-lat1); val dLon = Math.toRadians(lon2-lon1)
        val a = sin(dLat/2).pow(2) + cos(Math.toRadians(lat1))*cos(Math.toRadians(lat2))*sin(dLon/2).pow(2)
        return 6371.0 * 2 * atan2(sqrt(a), sqrt(1-a))
    }

    private fun bracketFor(km: Double) = when {
        km < 0.1   -> "at_dom";         km < 0.5   -> "almost_there"
        km < 2.0   -> "very_close";     km < 10.0  -> "close"
        km < 50.0  -> "getting_closer"; km < 200.0 -> "medium_far"
        km < 500.0 -> "far";            else        -> "very_far"
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

    private fun formatDistance(km: Double) = when {
        km < 1.0 -> getString(R.string.distance_meters, (km * 1000).toInt())
        else     -> getString(R.string.distance_km, km).replace(".", ",")
    }

    private fun setupWebcamButton() {
        binding.btnWebcam.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/live/kodtgRure8Y")))
        }
    }
}
