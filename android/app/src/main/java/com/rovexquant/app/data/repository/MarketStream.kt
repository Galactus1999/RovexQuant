package com.rovexquant.app.data.repository

import com.rovexquant.app.data.model.MarketTick
import kotlinx.coroutines.flow.emptyFlow

/** Lifecycle-controlled stream port; transport implementations stay in data.remote. */
interface MarketStream {
    fun start(instrumentKeys: List<String>)
    fun stop()

    /** Bounded higher-frequency feed for chart-only consumers; default keeps legacy fakes compatible. */
    val chartTicks: kotlinx.coroutines.flow.Flow<List<MarketTick>>
        get() = emptyFlow()

    /** Number of frames discarded because the bounded ingress queue overflowed. */
    val droppedFrameCount: Long
        get() = 0L
}
