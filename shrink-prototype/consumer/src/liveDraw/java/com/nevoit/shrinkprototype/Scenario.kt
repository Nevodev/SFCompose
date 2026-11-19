package com.nevoit.shrinkprototype

import com.nevoit.shrinkprototype.catalog.LiveDrawSymbols
import com.nevoit.shrinkprototype.catalog.SymbolRef

fun scenarioSymbols() = emptyList<SymbolRef>()

fun packedSymbols() = listOf(
    LiveDrawSymbols.WifiRegular,
    LiveDrawSymbols.ArabicZeroRegular,
    LiveDrawSymbols.AirtagForwardRegular,
    LiveDrawSymbols.ArrowForwardRegular,
    LiveDrawSymbols.AirplayAudioRegular,
    LiveDrawSymbols.CellularBarsRegular,
    LiveDrawSymbols.HomepodMiniArrowForwardRegular,
    LiveDrawSymbols.ArrowClockwiseRegular,
)
