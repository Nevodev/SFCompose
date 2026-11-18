package com.nevoit.shrinkprototype

import com.nevoit.shrinkprototype.catalog.ExpandedSymbols
import com.nevoit.shrinkprototype.catalog.SymbolRef

fun scenarioSymbols() = emptyList<SymbolRef>()

fun packedSymbols() = listOf(
    ExpandedSymbols.WifiRegular,
    ExpandedSymbols.ArabicZeroRegular,
    ExpandedSymbols.AirtagForwardRegular,
    ExpandedSymbols.ArrowForwardRegular,
    ExpandedSymbols.AirplayAudioRegular,
    ExpandedSymbols.CellularBarsRegular,
    ExpandedSymbols.HomepodMiniArrowForwardRegular,
    ExpandedSymbols.ArrowClockwiseRegular,
)
