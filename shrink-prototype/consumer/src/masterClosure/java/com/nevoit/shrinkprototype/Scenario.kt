package com.nevoit.shrinkprototype

import com.nevoit.shrinkprototype.catalog.CompactSymbols
import com.nevoit.shrinkprototype.catalog.SymbolRef

fun scenarioSymbols() = emptyList<SymbolRef>()

fun packedSymbols() = listOf(
    CompactSymbols.WifiRegular,
    CompactSymbols.ArabicZeroRegular,
    CompactSymbols.AirtagForwardRegular,
    CompactSymbols.ArrowForwardRegular,
    CompactSymbols.AirplayAudioRegular,
    CompactSymbols.CellularBarsRegular,
    CompactSymbols.HomepodMiniArrowForwardRegular,
    CompactSymbols.ArrowClockwiseRegular,
)
