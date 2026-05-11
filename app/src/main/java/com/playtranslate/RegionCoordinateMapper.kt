package com.playtranslate

object RegionCoordinateMapper {
    fun viewRegionToDisplayRegion(
        region: RegionEntry,
        viewLeft: Int,
        viewTop: Int,
        viewWidth: Int,
        viewHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
    ): RegionEntry {
        if (viewWidth <= 0 || viewHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) return region
        val left = ((viewLeft + region.left * viewWidth) / displayWidth).coerceIn(0f, 1f)
        val right = ((viewLeft + region.right * viewWidth) / displayWidth).coerceIn(0f, 1f)
        val top = ((viewTop + region.top * viewHeight) / displayHeight).coerceIn(0f, 1f)
        val bottom = ((viewTop + region.bottom * viewHeight) / displayHeight).coerceIn(0f, 1f)
        return ordered(region, top, bottom, left, right)
    }

    fun displayRegionToViewRegion(
        region: RegionEntry,
        viewLeft: Int,
        viewTop: Int,
        viewWidth: Int,
        viewHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
    ): RegionEntry {
        if (viewWidth <= 0 || viewHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) return region
        val left = ((region.left * displayWidth - viewLeft) / viewWidth).coerceIn(0f, 1f)
        val right = ((region.right * displayWidth - viewLeft) / viewWidth).coerceIn(0f, 1f)
        val top = ((region.top * displayHeight - viewTop) / viewHeight).coerceIn(0f, 1f)
        val bottom = ((region.bottom * displayHeight - viewTop) / viewHeight).coerceIn(0f, 1f)
        return ordered(region, top, bottom, left, right)
    }

    private fun ordered(region: RegionEntry, top: Float, bottom: Float, left: Float, right: Float): RegionEntry =
        region.copy(
            top = top.coerceAtMost(bottom),
            bottom = bottom.coerceAtLeast(top),
            left = left.coerceAtMost(right),
            right = right.coerceAtLeast(left),
        )
}
