package com.vincenthzr.locationspoofer.ui.screen.managedata

import com.vincenthzr.locationspoofer.data.db.CompleteLocation

data class ManageDataUiState(
    val dataList: List<CompleteLocation> = emptyList(),
    val isLoading: Boolean = false
)
