package dev.dublinbustracker.server

import dev.dublinbustracker.core.Polyline
import dev.dublinbustracker.core.StopSearch
import dev.dublinbustracker.data.GtfsStaticData
import dev.dublinbustracker.data.headingsAtStop
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 500
private const val DEFAULT_NEARBY_RADIUS_METERS = 500.0

fun Routing.configureRoutes(services: AppServices) {
    val staticData = services.staticData

    get("/health") { call.respondText("ok") }

    // Kept unversioned and unchanged: the Android app already ships against these.
    get("/arrivals") {
        val stopId = call.request.queryParameters["stopId"]
        if (stopId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("stopId query parameter is required"))
            return@get
        }
        call.respond(services.arrivals.arrivalsForStop(stopId).map { it.toResponse() })
    }

    get("/stops") {
        val data = staticData.current
        val query = call.request.queryParameters["query"].orEmpty()
        call.respond(StopSearch.search(data.allStops(), query).map { it.toResponse() })
    }

    route("/v1") {
        get("/status") {
            call.respond(services.buildStatus())
        }

        get("/stops") {
            val data = staticData.current
            val query = call.request.queryParameters["query"].orEmpty()
            val limit = call.limit()
            val matches = StopSearch.search(data.allStops(), query).take(limit)
            call.respond(matches.map { it.toResponse(data.routesServingStop(it.id)) })
        }

        get("/stops/nearby") {
            val data = staticData.current
            val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
            val lon = call.request.queryParameters["lon"]?.toDoubleOrNull()
            if (lat == null || lon == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("lat and lon query parameters are required"))
                return@get
            }
            val radius = call.request.queryParameters["radius"]?.toDoubleOrNull() ?: DEFAULT_NEARBY_RADIUS_METERS
            val nearby = StopSearch.stopsWithinRadius(lat, lon, data.allStops(), radius, call.limit())
            call.respond(nearby.map { it.toResponse(data.routesServingStop(it.id)) })
        }

        get("/stops/{stopId}") {
            val data = staticData.current
            val stop = data.stopByIdOrCode(call.parameters["stopId"].orEmpty()) ?: return@get call.notFound("unknown stop")
            call.respond(
                stop.toResponse(data.routesServingStop(stop.id))
                    .copy(headings = data.headingsAtStop(stop).map { RouteHeadingResponse(it.route, it.compass) }),
            )
        }

        get("/stops/{stopId}/arrivals") {
            val data = staticData.current
            val stop = data.stopByIdOrCode(call.parameters["stopId"].orEmpty()) ?: return@get call.notFound("unknown stop")
            val route = call.request.queryParameters["route"]
            call.respond(services.arrivals.arrivalsForStop(stop.id, route, call.limit()).map { it.toResponse() })
        }

        get("/routes") {
            val data = staticData.current
            val query = call.request.queryParameters["query"]?.trim().orEmpty()
            val matches = data.allRoutes().filter {
                query.isEmpty() ||
                    it.shortName.contains(query, ignoreCase = true) ||
                    it.longName.contains(query, ignoreCase = true)
            }
            call.respond(matches.take(call.limit()).map { it.toResponse() })
        }

        get("/routes/{route}") {
            val data = staticData.current
            val routes = data.resolveRoutes(call.parameters["route"].orEmpty())
            if (routes.isEmpty()) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown route"))
                return@get
            }
            call.respond(routes.map { it.toResponse() })
        }

        get("/routes/{route}/stops") {
            val data = staticData.current
            val route = data.resolveRoutes(call.parameters["route"].orEmpty()).firstOrNull() ?: return@get call.notFound("unknown route")
            val wantedDirection = call.request.queryParameters["direction"]
            val directions = data.patternsForRoute(route.id)
                .filterKeys { wantedDirection == null || it == wantedDirection }
                .mapValues { (_, stopIds) ->
                    stopIds.mapNotNull { data.stopById(it) }.map { it.toResponse() }
                }
            call.respond(RouteStopsResponse(route.id, route.shortName, directions))
        }

        // Every stop on the route in one request, so a client labelling a whole route doesn't ask stop by stop.
        // Stops with nothing coming are left out.
        get("/routes/{route}/arrivals") {
            val data = staticData.current
            val identifier = call.parameters["route"].orEmpty()
            val route = data.resolveRoutes(identifier).firstOrNull() ?: return@get call.notFound("unknown route")
            val limit = call.limit()
            val stopIds = data.patternsForRoute(route.id).values.flatten().distinct()
            val byStop = services.routeArrivalsCache.getOrCompute("$identifier|$limit", services.arrivals.feedStamp(), data) {
                services.arrivals.arrivalsForRoute(identifier, stopIds, limit).mapValues { (_, v) -> v.map { it.toResponse() } }
            }
            call.respond(byStop)
        }

        get("/routes/{route}/shape") {
            val data = staticData.current
            val route = data.resolveRoutes(call.parameters["route"].orEmpty()).firstOrNull() ?: return@get call.notFound("unknown route")
            val shapeId = data.shapeIdForRoute(route.id, call.request.queryParameters["direction"]) ?: return@get call.notFound("no shape for this route")
            call.respondShape(shapeId, data)
        }

        get("/routes/{route}/vehicles") {
            val data = staticData.current
            val identifier = call.parameters["route"].orEmpty()
            if (data.resolveRoutes(identifier).isEmpty()) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown route"))
                return@get
            }
            call.respond(services.vehicles.vehiclesForRoute(identifier).map { it.toResponse() })
        }

        get("/vehicles") {
            val routeFilter = call.request.queryParameters["route"]
            val rawBox = call.request.queryParameters["bbox"]
            val box = rawBox?.let { BoundingBox.parse(it) }
            if (rawBox != null && box == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("bbox must be minLat,minLon,maxLat,maxLon"))
                return@get
            }
            var vehicles = if (routeFilter != null) {
                services.vehicles.vehiclesForRoute(routeFilter)
            } else {
                services.vehicles.allVehicles()
            }
            if (box != null) vehicles = vehicles.filter { box.contains(it.position) }
            call.respond(vehicles.take(call.limit()).map { it.toResponse() })
        }

        get("/vehicles/{vehicleId}") {
            val vehicle = services.vehicles.vehicleById(call.parameters["vehicleId"].orEmpty()) ?: return@get call.notFound("unknown or inactive vehicle")
            call.respond(vehicle.toResponse())
        }

        get("/vehicles/{vehicleId}/history") {
            val vehicleId = call.parameters["vehicleId"].orEmpty()
            val fixes = services.vehicles.vehicleHistory(vehicleId)
            if (fixes.isEmpty()) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no retained history for this vehicle"))
                return@get
            }
            call.respond(
                VehicleHistoryResponse(
                    vehicleId = vehicleId,
                    fixCount = fixes.size,
                    fixes = fixes.map { it.toResponse() },
                    encodedPolyline = Polyline.encode(fixes.map { it.position }),
                ),
            )
        }

        get("/trips/{tripId}") {
            val data = staticData.current
            val tripId = call.parameters["tripId"].orEmpty()
            val trip = data.trip(tripId) ?: return@get call.notFound("unknown trip")
            call.respond(
                TripResponse(
                    tripId = trip.id,
                    routeId = trip.routeId,
                    routeShortName = data.routeShortName(trip.routeId),
                    headsign = trip.headsign,
                    directionId = trip.directionId,
                    shapeId = trip.shapeId,
                    vehicle = services.vehicles.vehicleForTrip(tripId)?.toResponse(),
                    stopCalls = services.arrivals.tripStopCalls(tripId).map { it.toResponse() },
                ),
            )
        }

        get("/trips/{tripId}/shape") {
            val data = staticData.current
            val trip = data.trip(call.parameters["tripId"].orEmpty())
            if (trip == null || trip.shapeId.isEmpty()) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown trip or no shape"))
                return@get
            }
            call.respondShape(trip.shapeId, data)
        }

        get("/alerts") {
            val data = staticData.current
            val routeFilter = call.request.queryParameters["route"]
            val stopFilter = call.request.queryParameters["stop"]
            val alerts = when {
                routeFilter != null ->
                    services.alerts.alertsForRoute(data.resolveRoutes(routeFilter).map { it.id }.toSet())
                stopFilter != null -> services.alerts.alertsForStop(stopFilter)
                else -> services.alerts.alerts()
            }
            call.respond(alerts.map { it.toResponse() })
        }
    }
}

private suspend fun ApplicationCall.respondShape(shapeId: String, data: GtfsStaticData) {
    val encoded = data.encodedShape(shapeId).orEmpty()
    val points = data.shapePoints(shapeId)
    val includePoints = request.queryParameters["format"] == "points"
    respond(
        ShapeResponse(
            shapeId = shapeId,
            pointCount = points.size,
            encodedPolyline = encoded,
            points = if (includePoints) points.toPointPairs() else null,
        ),
    )
}

private suspend fun ApplicationCall.notFound(message: String) =
    respond(HttpStatusCode.NotFound, ErrorResponse(message))

private fun ApplicationCall.limit(): Int =
    request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT

private fun AppServices.buildStatus(): StatusResponse {
    val now = System.currentTimeMillis()
    return StatusResponse(
        pollIntervalMs = config.pollIntervalMs,
        minimumSafePollIntervalMs = config.minimumSafePollIntervalMs,
        upstreamCallsPerMinute = config.upstreamCallsPerMinute,
        historyDepth = config.historyDepth,
        apiKeyCount = client.keyCount,
        configuredFeeds = config.feeds.map { it.name },
        upstreamRequests = client.totalRequests,
        refreshSuccesses = poller.successCount,
        refreshFailures = poller.failureCount,
        staticData = staticData.current.stats(),
        feeds = config.feeds.map { kind ->
            val latest = store.latest(kind)
            FeedStatusResponse(
                feed = kind.name,
                lastFetchedEpochMs = latest?.fetchedAtEpochMs,
                ageSeconds = latest?.let { (now - it.fetchedAtEpochMs) / 1000 },
                entityCount = latest?.feed?.entityCount ?: 0,
                retainedSnapshots = store.depth(kind),
            )
        },
    )
}
