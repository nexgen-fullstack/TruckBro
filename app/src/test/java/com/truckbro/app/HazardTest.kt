package com.truckbro.app

import com.truckbro.app.data.VoiceLang
import com.truckbro.app.road.HazardAlert
import com.truckbro.app.road.HazardParser
import com.truckbro.app.road.HazardTracker
import com.truckbro.app.road.HazardType
import com.truckbro.app.road.METERS_PER_MILE
import com.truckbro.app.road.ScreenText
import com.truckbro.app.service.Phrases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardTest {
    private fun t(vararg s: String) = s.map { ScreenText(it) }
    private val mi = METERS_PER_MILE

    @Test fun wazePoliceCard() {
        val r = HazardParser.parse(t("Police", "1.2 mi", "Reported 6 min ago", "Thanks"))
        assertEquals(HazardType.POLICE, r.single().type)
        assertEquals(1.2 * mi, r.single().meters!!, 2.0)
    }

    @Test fun specificTypesBeatGenericHazard() {
        assertEquals(HazardType.OBJECT, HazardParser.parse(t("Hazard: object on road", "0.8 mi")).single().type)
        assertEquals(HazardType.STOPPED_VEHICLE, HazardParser.parse(t("Vehicle stopped on shoulder", "0.5 mi")).single().type)
        assertEquals(HazardType.POTHOLE, HazardParser.parse(t("Pothole", "1,200 ft")).single().type)
        assertEquals(HazardType.CRASH, HazardParser.parse(t("Crash reported ahead")).single().type)
    }

    @Test fun laneIsRead() {
        val s = HazardParser.parse(t("Object on road", "Left lane · 0.8 mi")).single()
        assertEquals("left lane", s.lane)
        assertEquals(0.8 * mi, s.meters!!, 2.0)
    }

    @Test fun ignoresReportMenuAndStillThere() {
        assertTrue(HazardParser.parse(t("Report", "Traffic jam", "Police", "Crash", "Hazard", "Closure")).isEmpty())
        assertTrue(HazardParser.parse(t("Police", "Still there?", "Not there")).isEmpty())
        // «Police» без власної відстані, поруч тільки довжина всього маршруту — не попередження.
        assertTrue(HazardParser.parse(t("Police", "12.4 mi")).isEmpty())
    }

    @Test fun policeTwoMilesThenOneMile() {
        val tr = HazardTracker()
        val a1 = tr.onSightings(HazardParser.parse(t("Police", "2.0 mi")), 0, 0.0)
        assertEquals(1, a1.size); assertEquals(false, a1[0].repeat)
        // Ближче, але ще не 1 миля — мовчимо.
        assertTrue(tr.onSightings(HazardParser.parse(t("Police", "1.4 mi")), 5_000, 0.6 * mi).isEmpty())
        // Картка Waze зникла, але машина проїхала ще — друге попередження за GPS.
        val a2 = tr.onTravel(1.05 * mi, 40_000)
        assertEquals(1, a2.size); assertEquals(true, a2[0].repeat)
        assertTrue(tr.onTravel(1.5 * mi, 50_000).isEmpty())
        assertTrue(tr.onSightings(HazardParser.parse(t("Police", "0.4 mi")), 55_000, 1.6 * mi).isEmpty())
    }

    @Test fun firstSightingCloseGivesSingleAlert() {
        val tr = HazardTracker()
        val a = tr.onSightings(HazardParser.parse(t("Police", "0.7 mi")), 0, 0.0)
        assertEquals(1, a.size)
        assertTrue(tr.onTravel(500.0, 5_000).isEmpty())
    }

    @Test fun obstacleOneMileAndThird() {
        val tr = HazardTracker()
        assertEquals(1, tr.onSightings(HazardParser.parse(t("Object on road", "1.0 mi")), 0, 0.0).size)
        assertTrue(tr.onTravel(0.5 * mi, 10_000).isEmpty())
        assertEquals(1, tr.onTravel(0.72 * mi, 20_000).size)
    }

    @Test fun noDistanceAnnouncedOnce() {
        val tr = HazardTracker()
        assertEquals(1, tr.onSightings(HazardParser.parse(t("Police reported ahead")), 0, 0.0).size)
        assertTrue(tr.onSightings(HazardParser.parse(t("Police reported ahead")), 3_000, 100.0).isEmpty())
    }

    @Test fun newEventAfterForget() {
        val tr = HazardTracker()
        tr.onSightings(HazardParser.parse(t("Police", "2.0 mi")), 0, 0.0)
        assertEquals(1, tr.onSightings(HazardParser.parse(t("Police", "2.0 mi")), HazardTracker.FORGET_MS + 1, 10 * mi).size)
    }

    @Test fun phrases() {
        assertEquals("Увага! Поліція попереду, за 2 милі.",
            Phrases.hazard(HazardAlert(HazardType.POLICE, 2.0 * mi, null, false), VoiceLang.UK))
        assertEquals("Object on road ahead, half a mile. Left lane. Slow down.",
            Phrases.hazard(HazardAlert(HazardType.OBJECT, 0.5 * mi, "left lane", false), VoiceLang.EN))
        assertEquals("Police ahead, 1 mile.", Phrases.hazard(HazardAlert(HazardType.POLICE, 1.0 * mi, null, true), VoiceLang.EN))
        assertNull(HazardParser.typeOf("Thanks"))
    }
}
