package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionCoordinateMapperTest {
    @Test
    fun viewRegionToDisplayRegion_addsActualViewOrigin() {
        val region = RegionEntry("Drawn", top = 0.25f, bottom = 0.75f, left = 0.1f, right = 0.9f)

        val mapped = RegionCoordinateMapper.viewRegionToDisplayRegion(
            region = region,
            viewLeft = 0,
            viewTop = 48,
            viewWidth = 1080,
            viewHeight = 2352,
            displayWidth = 1080,
            displayHeight = 2400,
        )

        assertEquals(0.265f, mapped.top, 0.0001f)
        assertEquals(0.755f, mapped.bottom, 0.0001f)
        assertEquals(0.1f, mapped.left, 0.0001f)
        assertEquals(0.9f, mapped.right, 0.0001f)
    }

    @Test
    fun viewRegionToDisplayRegion_usesHorizontalOriginToo() {
        val region = RegionEntry("Drawn", top = 0.2f, bottom = 0.8f, left = 0.25f, right = 0.75f)

        val mapped = RegionCoordinateMapper.viewRegionToDisplayRegion(
            region = region,
            viewLeft = 100,
            viewTop = 50,
            viewWidth = 800,
            viewHeight = 1800,
            displayWidth = 1000,
            displayHeight = 2000,
        )

        assertEquals(0.205f, mapped.top, 0.0001f)
        assertEquals(0.745f, mapped.bottom, 0.0001f)
        assertEquals(0.3f, mapped.left, 0.0001f)
        assertEquals(0.7f, mapped.right, 0.0001f)
    }

    @Test
    fun displayRegionToViewRegion_reversesViewToDisplayMapping() {
        val viewRegion = RegionEntry("Drawn", top = 0.25f, bottom = 0.75f, left = 0.1f, right = 0.9f)
        val displayRegion = RegionCoordinateMapper.viewRegionToDisplayRegion(
            region = viewRegion,
            viewLeft = 0,
            viewTop = 48,
            viewWidth = 1080,
            viewHeight = 2352,
            displayWidth = 1080,
            displayHeight = 2400,
        )

        val roundTrip = RegionCoordinateMapper.displayRegionToViewRegion(
            region = displayRegion,
            viewLeft = 0,
            viewTop = 48,
            viewWidth = 1080,
            viewHeight = 2352,
            displayWidth = 1080,
            displayHeight = 2400,
        )

        assertEquals(viewRegion.top, roundTrip.top, 0.0001f)
        assertEquals(viewRegion.bottom, roundTrip.bottom, 0.0001f)
        assertEquals(viewRegion.left, roundTrip.left, 0.0001f)
        assertEquals(viewRegion.right, roundTrip.right, 0.0001f)
    }

    @Test
    fun viewRegionToDisplayRegion_identityWhenViewCoversDisplay() {
        val region = RegionEntry("Drawn", top = 0.2f, bottom = 0.8f, left = 0.15f, right = 0.85f)

        val mapped = RegionCoordinateMapper.viewRegionToDisplayRegion(
            region = region,
            viewLeft = 0,
            viewTop = 0,
            viewWidth = 1000,
            viewHeight = 2000,
            displayWidth = 1000,
            displayHeight = 2000,
        )

        assertEquals(region.top, mapped.top, 0.0001f)
        assertEquals(region.bottom, mapped.bottom, 0.0001f)
        assertEquals(region.left, mapped.left, 0.0001f)
        assertEquals(region.right, mapped.right, 0.0001f)
    }
}
