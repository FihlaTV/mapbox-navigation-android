package com.mapbox.navigation

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.google.gson.reflect.TypeToken
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineRequest
import com.mapbox.annotation.navigation.module.MapboxNavigationModuleType
import com.mapbox.annotation.navigation.module.MapboxNavigationModuleType.DirectionsSession as DirectionsSessionModule
import com.mapbox.annotation.navigation.module.MapboxNavigationModuleType.HybridRouter
import com.mapbox.annotation.navigation.module.MapboxNavigationModuleType.Logger as LoggerModule
import com.mapbox.annotation.navigation.module.MapboxNavigationModuleType.OffboardRouter
import com.mapbox.annotation.navigation.module.MapboxNavigationModuleType.OnboardRouter
import com.mapbox.geojson.Point
import com.mapbox.annotation.navigation.module.MapboxNavigationModuleType.TripNotification as TripNotificationModule
import com.mapbox.annotation.navigation.module.MapboxNavigationModuleType.TripService as TripServiceModule
import com.mapbox.annotation.navigation.module.MapboxNavigationModuleType.TripSession as TripSessionModule
import com.mapbox.navigation.base.logger.Logger
import com.mapbox.navigation.base.route.DirectionsSession
import com.mapbox.navigation.base.route.Router
import com.mapbox.navigation.base.route.model.Route
import com.mapbox.navigation.base.trip.TripNotification
import com.mapbox.navigation.base.trip.TripService
import com.mapbox.navigation.base.trip.TripSession
import com.mapbox.navigation.module.NavigationModuleProvider
import com.mapbox.navigation.navigator.MapboxNativeNavigator

class NavigationController(
    private val application: Application,
    private val mapboxToken: String,
    private val navigator: MapboxNativeNavigator,
    private val locationEngine: LocationEngine,
    private val locationEngineRequest: LocationEngineRequest
) {

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val workerHandler: Handler by lazy { Handler(workerThread.looper) }
    private val workerThread: HandlerThread by lazy {
        HandlerThread("NavigationController").apply { start() }
    }

    private val origin by lazy { Point.fromLngLat(0.0, 0.0) }
    private val waypoints by lazy { arrayListOf<Point>() }
    private val destination by lazy { Point.fromLngLat(0.0, 0.0) }

    private val logger: Logger
    private val directionsSession: DirectionsSession
    private val tripSession: TripSession

    init {
        logger = NavigationModuleProvider.createModule(LoggerModule, ::paramsProvider)
        directionsSession = NavigationModuleProvider.createModule(DirectionsSessionModule, ::paramsProvider)
        tripSession = NavigationModuleProvider.createModule(TripSessionModule, ::paramsProvider)
    }

    /**
     * Provides parameters for Mapbox default modules, recursively if a module depends on other Mapbox modules.
     */
    private fun paramsProvider(type: MapboxNavigationModuleType): Array<Pair<Class<*>?, Any?>> {
        return when (type) {
            HybridRouter -> arrayOf(
                Router::class.java to NavigationModuleProvider.createModule(OnboardRouter, ::paramsProvider),
                Router::class.java to NavigationModuleProvider.createModule(OffboardRouter, ::paramsProvider)
            )
            OffboardRouter -> arrayOf(
                Context::class.java to application,
                String::class.java to mapboxToken
            )
            OnboardRouter -> arrayOf(
                MapboxNativeNavigator::class.java to navigator
            )
            DirectionsSessionModule -> arrayOf(
                Router::class.java to NavigationModuleProvider.createModule(HybridRouter, ::paramsProvider),
                Point::class.java to origin,
                // List::class.java to waypoints,
                Point::class.java to destination,
                DirectionsSession.RouteObserver::class.java to object : DirectionsSession.RouteObserver{
                    override fun onRouteChanged(route: Route?) = Unit

                    override fun onFailure(throwable: Throwable) = Unit
                }
            )
            TripNotificationModule -> arrayOf()
            TripServiceModule -> arrayOf(
                TripNotification::class.java to NavigationModuleProvider.createModule(TripNotificationModule, ::paramsProvider)
            )
            TripSessionModule -> arrayOf(
                TripService::class.java to NavigationModuleProvider.createModule(TripServiceModule, ::paramsProvider),
                LocationEngine::class.java to locationEngine,
                LocationEngineRequest::class.java to locationEngineRequest,
                MapboxNativeNavigator::class.java to navigator,
                Handler::class.java to mainHandler,
                Handler::class.java to workerHandler
            )
            LoggerModule -> arrayOf()
        }
    }

    internal fun onDestroy() {
        workerThread.quit()
    }
}
