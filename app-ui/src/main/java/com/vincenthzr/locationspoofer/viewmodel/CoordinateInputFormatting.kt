package com.vincenthzr.locationspoofer.viewmodel

import java.util.Locale

/** Coordinate input state is parsed with toDoubleOrNull, not a localized number parser. */
internal fun formatCoordinateInput(coordinate: Double): String =
    String.format(Locale.US, "%.6f", coordinate)
