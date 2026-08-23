package com.mightykatun.speedometer.app.data.repository

import android.hardware.SensorEventListener
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import com.mightykatun.speedometer.app.domain.SpeedEstimator
import com.mightykatun.speedometer.app.domain.model.EstimateQuality
import com.mightykatun.speedometer.app.domain.model.GnssMeasurement
import com.mightykatun.speedometer.app.domain.model.SpeedEstimate
import com.mightykatun.speedometer.app.domain.model.TrackingMode
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SpeedRepositoryImplTest {
    private val estimate = SpeedEstimate(
        speedMetersPerSecond = null,
        uncertaintyMetersPerSecond = Double.POSITIVE_INFINITY,
        quality = EstimateQuality.ACQUIRING,
        timestampNanos = 1L
    )

    @Test
    fun `permission failure returns to stopped and a later start retries`() {
        val fixture = Fixture()
        fixture.location.permissionGranted = false
        val first = Recording()

        fixture.repository.start(TrackingMode.HANDHELD, first)
        fixture.worker.runAll()
        fixture.main.runAll()

        assertEquals(1, first.permissionRequests)
        assertTrue(first.errors.isEmpty())
        assertEquals(0, fixture.location.requestCount)

        fixture.location.permissionGranted = true
        val second = Recording()
        fixture.repository.start(TrackingMode.HANDHELD, second)
        fixture.worker.runAll()
        fixture.main.runAll()

        assertEquals(1, fixture.location.requestCount)
        assertEquals(listOf(TrackingMode.HANDHELD), second.modes)
    }

    @Test
    fun `GNSS registration failure removes location updates and permits retry`() {
        val fixture = Fixture()
        fixture.location.gnssRegistrationSucceeds = false

        fixture.repository.start(TrackingMode.HANDHELD, Recording())
        fixture.worker.runAll()
        fixture.main.runAll()

        assertEquals(1, fixture.location.removeCount)
        assertEquals(1, fixture.location.unregisterGnssCount)

        fixture.location.gnssRegistrationSucceeds = true
        val retry = Recording()
        fixture.repository.start(TrackingMode.HANDHELD, retry)
        fixture.worker.runAll()
        fixture.main.runAll()

        assertEquals(2, fixture.location.requestCount)
        assertEquals(listOf(TrackingMode.HANDHELD), retry.modes)
    }

    @Test
    fun `location registration exception cleans up and permits retry`() {
        val fixture = Fixture()
        fixture.location.requestFailure = IllegalStateException("provider unavailable")
        val first = Recording()

        fixture.repository.start(TrackingMode.HANDHELD, first)
        fixture.worker.runAll()
        fixture.main.runAll()

        assertEquals(listOf("Error starting GPS: provider unavailable"), first.errors)
        assertEquals(1, fixture.location.removeCount)

        fixture.location.requestFailure = null
        fixture.repository.start(TrackingMode.HANDHELD, Recording())
        fixture.worker.runAll()

        assertEquals(2, fixture.location.requestCount)
    }

    @Test
    fun `permission revoked during registration uses permission recovery callback`() {
        val fixture = Fixture()
        fixture.location.requestFailure = SecurityException("revoked")
        val recording = Recording()

        fixture.repository.start(TrackingMode.HANDHELD, recording)
        fixture.worker.runAll()
        fixture.main.runAll()

        assertEquals(1, recording.permissionRequests)
        assertTrue(recording.errors.isEmpty())
        assertEquals(1, fixture.location.removeCount)
    }

    @Test
    fun `fixed sensor failure explicitly falls back to handheld`() {
        val fixture = Fixture()
        fixture.motion.registrationSucceeds = false
        val recording = Recording()

        fixture.repository.start(TrackingMode.FIXED, recording)
        fixture.worker.runAll()
        fixture.main.runAll()

        assertEquals(listOf(TrackingMode.HANDHELD), recording.modes)
        assertTrue(recording.errors.isEmpty())
        assertEquals(1, fixture.motion.registerCount)
        assertTrue(fixture.motion.unregisterCount >= 1)
        verify(fixture.estimator).reset(TrackingMode.HANDHELD)
    }

    @Test
    fun `explicit fixed request retries after sensor fallback`() {
        val fixture = Fixture()
        fixture.motion.registrationSucceeds = false
        val recording = Recording()
        fixture.repository.start(TrackingMode.FIXED, recording)
        fixture.worker.runAll()
        fixture.main.runAll()

        fixture.motion.registrationSucceeds = true
        fixture.repository.setTrackingMode(TrackingMode.FIXED)
        fixture.worker.runAll()
        fixture.main.runAll()

        assertEquals(2, fixture.motion.registerCount)
        assertEquals(TrackingMode.FIXED, recording.modes.last())
        verify(fixture.estimator).setTrackingMode(TrackingMode.FIXED)
    }

    @Test
    fun `queued mode delivery cannot cross stop and restart generation`() {
        val fixture = Fixture()
        val oldRecording = Recording()
        fixture.repository.start(TrackingMode.HANDHELD, oldRecording)
        fixture.worker.runAll()
        fixture.main.runAll()

        fixture.repository.setTrackingMode(TrackingMode.FIXED)
        fixture.worker.runAll()
        assertTrue(fixture.main.hasPendingTasks())

        val newRecording = Recording()
        fixture.repository.stopUpdates()
        fixture.repository.start(TrackingMode.HANDHELD, newRecording)
        fixture.worker.runAll()
        fixture.main.runAll()

        assertEquals(emptyList<SpeedEstimate>(), oldRecording.estimates)
        assertEquals(listOf(TrackingMode.HANDHELD), oldRecording.modes)
        assertEquals(listOf(TrackingMode.HANDHELD), newRecording.modes)
    }

    @Test
    fun `duplicate start routes already queued estimate to replacement callbacks`() {
        val fixture = Fixture()
        val first = Recording()
        fixture.repository.start(TrackingMode.HANDHELD, first)
        fixture.worker.runAll()
        fixture.main.runAll()

        fixture.repository.setTrackingMode(TrackingMode.FIXED)
        fixture.worker.runAll()
        val replacement = Recording()
        fixture.repository.start(TrackingMode.FIXED, replacement)
        fixture.worker.runAll()
        fixture.main.runAll()

        assertTrue(first.estimates.isEmpty())
        assertEquals(listOf(estimate), replacement.estimates)
    }

    @Test
    fun `stop suppresses queued main callbacks before worker cleanup runs`() {
        val fixture = Fixture()
        val recording = Recording()
        fixture.repository.start(TrackingMode.HANDHELD, recording)
        fixture.worker.runAll()

        fixture.repository.stopUpdates()
        fixture.main.runAll()

        assertTrue(recording.modes.isEmpty())
        assertTrue(recording.satelliteCounts.isEmpty())
    }

    @Test
    fun `commands queued after close cannot access platform gateways`() {
        val fixture = Fixture()
        fixture.repository.start(TrackingMode.HANDHELD, Recording())
        fixture.worker.runAll()
        assertEquals(1, fixture.location.requestCount)

        fixture.repository.close()
        fixture.repository.start(TrackingMode.HANDHELD, Recording())
        fixture.repository.setTrackingMode(TrackingMode.FIXED)
        fixture.repository.stopUpdates()
        fixture.worker.runAll()

        assertEquals(1, fixture.location.requestCount)
        assertTrue(fixture.worker.closed)
    }

    @Test
    fun `satellite evidence within age bound and no newer than fix is attached`() {
        val fixture = Fixture()
        fixture.repository.start(TrackingMode.HANDHELD, Recording())
        fixture.worker.runAll()
        fixture.worker.nowNanos = 1_000_000_000L
        fixture.location.emitSatelliteCount(4)
        fixture.location.emitLocation(locationAt(3_000_000_000L))

        assertEquals(4, fixture.capturedMeasurement().satelliteCount)
    }

    @Test
    fun `stale satellite evidence is excluded from fix`() {
        val fixture = Fixture()
        fixture.repository.start(TrackingMode.HANDHELD, Recording())
        fixture.worker.runAll()
        fixture.worker.nowNanos = 1_000_000_000L
        fixture.location.emitSatelliteCount(5)
        fixture.location.emitLocation(locationAt(3_000_000_001L))

        assertEquals(0, fixture.capturedMeasurement().satelliteCount)
    }

    @Test
    fun `satellite evidence newer than fix is excluded`() {
        val fixture = Fixture()
        fixture.repository.start(TrackingMode.HANDHELD, Recording())
        fixture.worker.runAll()
        fixture.worker.nowNanos = 2_000_000_000L
        fixture.location.emitSatelliteCount(6)
        fixture.location.emitLocation(locationAt(1_999_999_999L))

        assertEquals(0, fixture.capturedMeasurement().satelliteCount)
    }

    private fun Fixture.capturedMeasurement(): GnssMeasurement {
        val captor = argumentCaptor<GnssMeasurement>()
        verify(estimator).ingestGnssMeasurement(captor.capture())
        return captor.firstValue
    }

    private fun locationAt(timestampNanos: Long): Location = mock<Location>().also { location ->
        whenever(location.elapsedRealtimeNanos).thenReturn(timestampNanos)
        whenever(location.hasSpeed()).thenReturn(true)
        whenever(location.speed).thenReturn(5f)
    }

    private class Fixture(supportsFixedMode: Boolean = true) {
        val worker = FakeWorker()
        val main = FakeMainDispatcher()
        val location = FakeLocationGateway()
        val motion = FakeMotionGateway(supportsFixedMode)
        val estimator = mock<SpeedEstimator>()
        val repository: SpeedRepositoryImpl

        init {
            whenever(estimator.snapshotAt(any())).thenReturn(estimate())
            repository = SpeedRepositoryImpl(estimator, worker, main, location, motion)
        }

        private fun estimate() = SpeedEstimate(
            speedMetersPerSecond = null,
            uncertaintyMetersPerSecond = Double.POSITIVE_INFINITY,
            quality = EstimateQuality.ACQUIRING,
            timestampNanos = 1L
        )
    }

    private class Recording {
        val estimates = mutableListOf<SpeedEstimate>()
        val satelliteCounts = mutableListOf<Int>()
        val errors = mutableListOf<String>()
        val modes = mutableListOf<TrackingMode>()
        var permissionRequests = 0
    }

    private fun SpeedRepositoryImpl.start(mode: TrackingMode, recording: Recording) {
        startUpdates(
            trackingMode = mode,
            onEstimate = recording.estimates::add,
            onSatelliteCount = recording.satelliteCounts::add,
            onGnssAvailable = {},
            onPermissionRequired = { recording.permissionRequests++ },
            onError = recording.errors::add,
            onTrackingModeChanged = recording.modes::add
        )
    }

    private class FakeWorker : RepositoryWorker {
        private val tasks = ArrayDeque<() -> Unit>()
        private val delayed = LinkedHashMap<Runnable, () -> Unit>()
        var nowNanos = 1L
        var closed = false

        override fun post(block: () -> Unit): Boolean {
            if (closed) return false
            tasks.addLast(block)
            return true
        }

        override fun postDelayed(runnable: Runnable, delayMillis: Long) {
            if (!closed) delayed[runnable] = { runnable.run() }
        }

        override fun removeCallbacks(runnable: Runnable) {
            delayed.remove(runnable)
        }

        override fun elapsedRealtimeNanos(): Long = nowNanos

        override fun close() {
            closed = true
            delayed.clear()
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        }
    }

    private class FakeMainDispatcher : RepositoryMainDispatcher {
        private val tasks = ArrayDeque<() -> Unit>()

        override fun post(block: () -> Unit) {
            tasks.addLast(block)
        }

        fun hasPendingTasks(): Boolean = tasks.isNotEmpty()

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        }
    }

    private class FakeLocationGateway : RepositoryLocationGateway {
        var permissionGranted = true
        var gnssRegistrationSucceeds = true
        var requestFailure: RuntimeException? = null
        var requestCount = 0
        var removeCount = 0
        var unregisterGnssCount = 0
        private var locationListener: LocationListener? = null
        private var gnssCallback: GnssStatus.Callback? = null

        override fun hasFineLocationPermission(): Boolean = permissionGranted

        override fun requestLocationUpdates(listener: LocationListener) {
            requestCount++
            locationListener = listener
            requestFailure?.let { throw it }
        }

        override fun registerGnssStatusCallback(callback: GnssStatus.Callback): Boolean {
            gnssCallback = callback
            return gnssRegistrationSucceeds
        }

        override fun removeLocationUpdates(listener: LocationListener) {
            removeCount++
            if (locationListener === listener) locationListener = null
        }

        override fun unregisterGnssStatusCallback(callback: GnssStatus.Callback) {
            unregisterGnssCount++
            if (gnssCallback === callback) gnssCallback = null
        }

        fun emitLocation(location: Location) {
            requireNotNull(locationListener).onLocationChanged(location)
        }

        fun emitSatelliteCount(count: Int) {
            val status = mock<GnssStatus>()
            whenever(status.satelliteCount).thenReturn(count)
            for (index in 0 until count) whenever(status.usedInFix(index)).thenReturn(true)
            requireNotNull(gnssCallback).onSatelliteStatusChanged(status)
        }
    }

    private class FakeMotionGateway(
        override val supportsFixedMode: Boolean
    ) : RepositoryMotionGateway {
        var registrationSucceeds = true
        var registerCount = 0
        var unregisterCount = 0

        override fun register(listener: SensorEventListener): Boolean {
            registerCount++
            return registrationSucceeds
        }

        override fun unregister(listener: SensorEventListener) {
            unregisterCount++
        }
    }
}
