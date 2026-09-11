package com.suseoaa.locationspoofer.ui.screen.managedata

import com.suseoaa.locationspoofer.data.db.CompleteLocation

data class ManageDataUiState(
    val dataList: List<CompleteLocation> = emptyList(),
    val isLoading: Boolean = false
)
