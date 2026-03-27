package com.s4.belsson.data.model

import com.s4.belsson.data.model.NervePathPoint
import com.s4.belsson.data.model.OverlayLine
import kotlinx.serialization.Serializable

@Serializable
data class PlanningOverlay(
    val outerContour: List<NervePathPoint> = emptyList(),
    val innerContour: List<NervePathPoint> = emptyList(),
    val baseGuide: List<NervePathPoint> = emptyList(),
    val widthIndicator: OverlayLine? = null
)