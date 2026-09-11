package com.suseoaa.locationspoofer.ui.screen.tabs.location

enum class LocationPanelState {
    COLLAPSED, // 下滑到底部（仅搜索框 + 右侧定点模拟/停止模拟按钮并排，其余卡片自然沉入底部边缘）
    DEFAULT,   // 默认状态（定点模拟按钮微高于 TabBar，收藏地点卡片完全下沉延伸至屏幕下方边缘外）
    EXPANDED   // 上滑抽拉完全展开（整组卡片向上抽拉滑出，根据点位数量动态延伸完整展现）
}
