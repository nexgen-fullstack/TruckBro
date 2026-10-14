package com.truckbro.app

import com.truckbro.app.road.AvoidKind
import com.truckbro.app.road.BypassPlanner
import com.truckbro.app.road.Decision
import com.truckbro.app.road.GeoMath
import com.truckbro.app.road.LatLon
import com.truckbro.app.road.METERS_PER_MILE
import com.truckbro.app.road.Maneuver
import com.truckbro.app.road.NavEngine
import com.truckbro.app.road.NavEvent
import com.truckbro.app.road.NavRoute
import com.truckbro.app.road.PlanResult
import com.truckbro.app.road.Routing
import com.truckbro.app.road.StopRater
import com.truckbro.app.road.Town
import com.truckbro.app.road.TruckStop
import com.truckbro.app.road.TruckStopParser
import com.truckbro.app.road.WeighParser
import com.truckbro.app.road.WeighStation
import com.truckbro.app.road.WeighStatus
import com.truckbro.app.road.WeighStatusParser
import com.truckbro.app.road.WeighWatcher
import com.truckbro.app.road.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadTest {
    private val mi = METERS_PER_MILE

    @Test fun polyline() {
        // Приклад із документації Google (точність 5).
        val p = GeoMath.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, p.size)
        assertEquals(38.5, p[0].lat, 1e-6); assertEquals(-120.2, p[0].lon, 1e-6)
        assertEquals(43.252, p[2].lat, 1e-6); assertEquals(-126.453, p[2].lon, 1e-6)
    }

    @Test fun moveAndBearing() {
        val a = LatLon(41.0, -88.0)
        val b = GeoMath.move(a, 0.0, 10_000.0)
        assertEquals(10_000.0, GeoMath.distance(a, b), 5.0)
        assertEquals(0.0, GeoMath.bearing(a, b), 0.5)
        assertEquals(10.0, GeoMath.angleDiff(355.0, 5.0), 1e-9)
    }

    @Test fun weighParserDropsPrivateScales() {
        val json = """{"elements":[
            {"type":"node","id":1,"lat":41.36,"lon":-87.80,"tags":{"amenity":"weighbridge"}},
            {"type":"node","id":2,"lat":41.75,"lon":-87.79,"tags":{"amenity":"weighbridge","brand":"CAT Scale","name":"CAT Scale"}},
            {"type":"node","id":3,"lat":41.50,"lon":-87.55,"tags":{"amenity":"weighbridge","name":"Chicago Heights Weigh Station","operator":"Illinois Department of Transportation"}},
            {"type":"way","id":4,"center":{"lat":40.0,"lon":-89.0},"tags":{"highway":"weigh_station","name":"Port of Entry"}},
            {"type":"node","id":5,"lat":41.0,"lon":-88.0,"tags":{"amenity":"weighbridge","name":"Smith Grain Elevator"}},
            {"type":"node","id":6,"lat":41.3601,"lon":-87.8001,"tags":{"amenity":"weighbridge"}}
        ]}"""
        val r = WeighParser.parse(json)
        assertEquals(listOf("node/1", "node/3", "way/4"), r.map { it.id })
    }

    @Test fun weighAheadPicksRightSideAndFiresThresholds() {
        // Їдемо на північ. Пара станцій: східна (праворуч) і західна (ліворуч).
        val east = WeighStation("e", 41.20, -87.799, "NB")
        val west = WeighStation("w", 41.20, -87.801, "SB")
        val behind = WeighStation("b", 40.90, -87.80, "behind")
        val w = WeighWatcher()
        val here = GeoMath.move(LatLon(41.20, -87.80), 180.0, 9.8 * mi)
        val a = w.ahead(listOf(east, west, behind), here, 0.0)
        assertEquals("e", a!!.station.id)
        assertEquals(10, w.step(a))
        assertNull(w.step(a))
        val here5 = GeoMath.move(LatLon(41.20, -87.80), 180.0, 4.9 * mi)
        assertEquals(5, w.step(w.ahead(listOf(east, west), here5, 0.0)))
        // Перескочили одразу до 1 милі — кажемо тільки «1 миля».
        val here1 = GeoMath.move(LatLon(41.20, -87.80), 180.0, 0.9 * mi)
        assertEquals(1, w.step(w.ahead(listOf(east, west), here1, 0.0)))
        // Їдемо на південь — станції позаду.
        assertNull(w.ahead(listOf(east, west), here1, 180.0))
    }

    @Test fun weighStatusFromScreenText() {
        assertEquals(WeighStatus.OPEN, WeighStatusParser.parse(listOf("Weigh station open", "I-57 N")))
        assertEquals(WeighStatus.CLOSED, WeighStatusParser.parse(listOf("Scales closed")))
        assertNull(WeighStatusParser.parse(listOf("Lane closed", "Store open now")))
    }

    /** Пряма лінія на північ із точками кожні ~110 м. */
    private fun line(from: LatLon, km: Double): List<LatLon> = (0..(km * 9).toInt()).map { GeoMath.move(from, 0.0, it * 111.0) }

    @Test fun bypassDecision() {
        val start = LatLon(41.0, -88.0)
        val main = line(start, 10.0)
        val station = GeoMath.move(start, 0.0, 5_000.0)
        // Об'їзд: з'їжджаємо на 3 км, відходимо на 1 км убік, повертаємось на 7 км.
        val detour = line(start, 3.0) +
            (1..10).map { GeoMath.move(GeoMath.move(start, 0.0, 3_000.0), 90.0, it * 100.0) } +
            line(GeoMath.move(GeoMath.move(start, 0.0, 3_000.0), 90.0, 1_000.0), 4.0) +
            (9 downTo 0).map { GeoMath.move(GeoMath.move(start, 0.0, 7_000.0), 90.0, it * 100.0) } +
            line(GeoMath.move(start, 0.0, 7_100.0), 2.8)
        val m = NavRoute(main, listOf(Maneuver(0, "Go north", "Go north", "Go north", 1)), 600.0, 10_000.0)
        fun b(sec: Double) = NavRoute(detour, listOf(Maneuver(0, "Go", "Go", "Go", 1), Maneuver(27, "Turn right", "Turn right", "Turn right now", 10)), sec, 12_000.0)
        val auto = BypassPlanner.analyze(AvoidKind.WEIGH, station, "W", main.last(), m, b(600.0 + 4 * 60), 10)
        assertTrue(auto is PlanResult.Ok)
        auto as PlanResult.Ok
        assertEquals(Decision.AUTO, auto.plan.decision)
        assertEquals(3_000.0, auto.plan.exitMeters, 250.0)
        val ask = BypassPlanner.analyze(AvoidKind.WEIGH, station, "W", main.last(), m, b(600.0 + 15 * 60), 10) as PlanResult.Ok
        assertEquals(Decision.ASK, ask.plan.decision)
        // Поліція — завжди з підтвердженням.
        assertEquals(Decision.ASK, (BypassPlanner.analyze(AvoidKind.POLICE, station, "P", main.last(), m, b(660.0), 10) as PlanResult.Ok).plan.decision)
        // «Об'їзд», що все одно проходить повз станцію, — не об'їзд.
        assertEquals(PlanResult.NoBypass, BypassPlanner.analyze(AvoidKind.WEIGH, station, "W", main.last(), m, m, 10))
    }

    @Test fun navEngineSpeaksAndRejoins() {
        val start = LatLon(41.0, -88.0)
        val pts = line(start, 5.0)
        val r = NavRoute(pts, listOf(Maneuver(0, "Go north", "Go north", "Go north", 1),
            Maneuver(30, "Turn right onto Main", "Turn right onto Main Street.", "Turn right onto Main Street now.", 10),
            Maneuver(pts.size - 1, "Arrive", "Arrive", "You have arrived", 4)), 300.0, 5_000.0)
        val e = NavEngine(r, 40, uk = false)
        val said = ArrayList<String>()
        var rejoined = false
        var d = 0.0
        while (d < 4_800 && !rejoined) {
            for (ev in e.onLocation(GeoMath.move(start, 0.0, d), 25f)) when (ev) {
                is NavEvent.Speak -> said += ev.text
                NavEvent.Rejoined -> rejoined = true
                NavEvent.OffRoute -> error("off route")
            }
            d += 50
        }
        assertEquals(listOf("In 1 mile, turn right onto Main Street.", "Turn right onto Main Street now."), said)
        assertTrue(rejoined)
    }

    @Test fun navEngineOffRoute() {
        val start = LatLon(41.0, -88.0)
        val r = NavRoute(line(start, 3.0), listOf(Maneuver(0, "Go", "Go", "Go", 1)), 200.0, 3_000.0)
        val e = NavEngine(r, 25, uk = true)
        e.onLocation(start, 20f)
        val far = GeoMath.move(GeoMath.move(start, 0.0, 300.0), 90.0, 200.0)
        val evs = (1..3).flatMap { e.onLocation(far, 20f) }
        assertTrue(NavEvent.OffRoute in evs)
    }

    @Test fun valhallaParse() {
        val json = """{"trip":{"legs":[{"shape":"_p~iF~ps|U_ulLnnqC_mqNvxq`@","maneuvers":[
            {"type":1,"instruction":"Drive north.","verbal_pre_transition_instruction":"Drive north.","begin_shape_index":0},
            {"type":10,"instruction":"Turn right.","verbal_transition_alert_instruction":"Turn right.","verbal_pre_transition_instruction":"Turn right now.","begin_shape_index":1}]}],
            "summary":{"time":120.5,"length":1.5}}}"""
        val r = Routing.parse(json)
        assertEquals(2, r.maneuvers.size)
        assertEquals("Turn right.", r.maneuvers[1].alert)
        assertEquals(1.5 * mi, r.meters, 0.1)
        val body = Routing.request(LatLon(1.0, 2.0), LatLon(3.0, 4.0), com.truckbro.app.road.Vehicle(13.5f, 30000, 53f),
            listOf(GeoMath.square(LatLon(2.0, 3.0), 100.0)), "uk-UA", 90.0)
        assertTrue(body.contains("\"costing\":\"truck\""))
        assertTrue(body.contains("exclude_polygons"))
        assertTrue(body.contains("\"heading\":90"))
    }

    @Test fun stopRating() {
        val towns = TruckStopParser.parseTowns("# c\ntown\tstate\tlat\tlon\tpopulation\trate\tradius_km\nGary\tIN\t41.59\t-87.34\t69000\t1200\t4.0\nNaperville\tIL\t41.75\t-88.15\t149000\t80\t5.6\n")
        assertEquals(2, towns.size)
        val gary = TruckStop("n/1", 41.60, -87.35, "Pilot", "Gary")
        assertEquals(Zone.RED, StopRater.rate(gary, towns, null, null).zone)
        assertEquals(Zone.GREEN, StopRater.rate(TruckStop("n/2", 41.75, -88.14, "Love's", ""), towns, null, null).zone)
        assertEquals(Zone.UNKNOWN, StopRater.rate(TruckStop("n/3", 40.0, -95.0, "Middle of nowhere", ""), towns, null, null).zone)
        // Власна позначка важливіша за статистику.
        val own = StopRater.rate(gary, towns, Zone.GREEN, "охорона є")
        assertEquals(Zone.GREEN, own.zone); assertEquals("охорона є", own.note)
        assertNotNull(Town("x", "IL", 0.0, 0.0, 1, 1, 1.0))
    }
}
