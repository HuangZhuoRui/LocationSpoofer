package com.vincenthzr.locationspoofer.data.model

data class RootSetupTestResult(
    val hasRoot: Boolean,
    val idOutput: String,
    val solution: RootSolution,
    val frameworkConnected: Boolean,
    /** App-side access only; per-process Hook status confirms actual reception. */
    val remoteConfigAccessible: Boolean,
    val configPublished: Boolean,
    val frameworkDetail: String
) {
    val overallVerified: Boolean get() = hasRoot && frameworkConnected && remoteConfigAccessible
}
