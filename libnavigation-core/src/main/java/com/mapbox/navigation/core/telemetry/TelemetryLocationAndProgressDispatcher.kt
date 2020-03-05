package com.mapbox.navigation.core.telemetry

import android.location.Location
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.utils.thread.ThreadController
import com.mapbox.navigation.utils.thread.monitorChannelWithException
import com.mapbox.navigation.utils.time.Time
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

internal typealias OffRouteBuffers = Pair<List<Location>, List<Location>>

internal class TelemetryLocationAndProgressDispatcher :
    RouteProgressObserver, LocationObserver {
    private var lastLocation: AtomicReference<Location> = AtomicReference(Location("Default"))
    private var routeProgress: AtomicReference<RouteProgressWithTimestamp> =
        AtomicReference(RouteProgressWithTimestamp(0, RouteProgress.Builder().build()))
    private val channelOnRouteProgress =
        Channel<RouteProgressWithTimestamp>(Channel.CONFLATED) // we want just the last notification
    private var channelLocation = Channel<Location>(Channel.CONFLATED)
    private var channelLastNSecondsOfLocations = Channel<Location>(Channel.CONFLATED)
    private var jobControl = ThreadController.getIOScopeAndRootJob()
    private var monitorJob: Job = Job()

    init {
        monitorJob = monitorLocationChannel()
    }

    /**
     * This method accumulates locations. The number of locations is limited by [MapboxNavigationTelemetry.LOCATION_BUFFER_MAX_SIZE].
     * Once this limit is reached, an item is removed before another is added. The method returns true if the queue reaches capacity,
     * false otherwise
     */
    private fun accumulateLocationAsync(location: Location, queue: ArrayDeque<Location>): Boolean {
        var result = false
        when (queue.count() >= MapboxNavigationTelemetry.LOCATION_BUFFER_MAX_SIZE) {
            true -> {
                queue.removeLast()
                queue.addFirst(location)
                result = true
            }
            false -> {
                queue.addFirst(location)
            }
        }
        return result
    }

    /**
     * This method returns a [Pair] of buffers. The first represents a fixed number of locations before an off route event,
     * while the second represents a fixed number of locations after the off route event
     */
    fun getLocationBuffersAsync() = accumulatePosEventLocationsAsync()

    /**
     * This method populates two location buffers. One with pre-offroute events and the other with post-offroute events
     */
    private fun accumulatePosEventLocationsAsync(): Deferred<OffRouteBuffers> {
        val result = CompletableDeferred<OffRouteBuffers>()
        jobControl.scope.launch {
            val monitorControl =
                CompletableDeferred<ArrayDeque<Location>>() // This variable will be signaled once enough location data is accumulated
            val preOffRoute = mutableListOf<Location>() // receiver for pre-offroute event locations
            preOffRoute.addAll(monitorControl.await()) // Once signaled, copy the locations
            monitorJob.cancelAndJoin() // Cancel the monitor before calling it again. This call suspends
            monitorLocationChannel(monitorControl) // Start accumulating post event locations
            val postOffRoute =
                mutableListOf<Location>() // receive buffer for post-offline event locations
            postOffRoute.addAll(monitorControl.await()) // copy post event locations
            monitorJob = monitorLocationChannel() // restart monitor
            result.complete(Pair(preOffRoute, postOffRoute)) // notify caller the job is complete
        }
        return result
    }

    /**
     * This method accumulates locations. The location objects are stored in a FIFO queue.
     * Once the queue size reaches a predefined limit, it becomes signaled and the caller is
     * notified via a deferred object
     */
    private fun monitorLocationChannel(result: CompletableDeferred<ArrayDeque<Location>>? = null): Job {
        val workLocationQueue =
            ArrayDeque<Location>() // Allocate work buffer to collect locations while the channel is receiving
        return jobControl.scope.monitorChannelWithException(channelLocation, { location ->
            // Listen to the location channel
            if (accumulateLocationAsync(
                    location,
                    workLocationQueue
                )
            ) { // Populate the work buffer with locations as they become available
                val locationQueue =
                    ArrayDeque<Location>() // Now that we have the desired number of locations, allocate the return buffer
                locationQueue.addAll(workLocationQueue) // Copy the collected data to the return buffer
                workLocationQueue.clear() // Clear the work area in preparation for more locations
                result?.complete(locationQueue) // Notify whomever is listening of the result
            }
        })
    }

    override fun onRouteProgressChanged(routeProgress: RouteProgress) {
        val data = RouteProgressWithTimestamp(Time.SystemImpl.millis(), routeProgress)
        this.routeProgress.set(data)
        channelOnRouteProgress.offer(data)
    }

    fun getRouteProgressChannel(): ReceiveChannel<RouteProgressWithTimestamp> =
        channelOnRouteProgress

    fun getLastLocation(): Location = lastLocation.get()
    fun getRouteProgress(): RouteProgressWithTimestamp = routeProgress.get()

    override fun onRawLocationChanged(rawLocation: Location) {
        // Do nothing
    }

    override fun onEnhancedLocationChanged(enhancedLocation: Location, keyPoints: List<Location>) {
        channelLocation.offer(enhancedLocation)
        channelLastNSecondsOfLocations.offer(enhancedLocation)
        lastLocation.set(enhancedLocation)
    }
}