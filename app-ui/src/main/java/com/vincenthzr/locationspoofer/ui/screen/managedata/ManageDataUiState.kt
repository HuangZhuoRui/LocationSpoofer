package com.vincenthzr.locationspoofer.ui.screen.managedata

import com.vincenthzr.locationspoofer.data.db.CompleteLocation
import com.vincenthzr.locationspoofer.data.db.CompleteCollectionRoute

data class ManageDataUiState(
    val dataList: List<CompleteLocation> = emptyList(),
    val collectionRoutes: List<CompleteCollectionRoute> = emptyList(),
    val isLoading: Boolean = false
)
