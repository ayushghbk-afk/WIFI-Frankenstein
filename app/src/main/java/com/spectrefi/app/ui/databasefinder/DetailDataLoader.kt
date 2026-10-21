package com.spectrefi.app.ui.databasefinder

import kotlinx.coroutines.flow.Flow

interface DetailDataLoader {
    suspend fun loadDetailData(searchResult: SearchResult): Flow<Map<String, Any?>>
}
