package com.m3u.smartphone.ui.business.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.m3u.data.database.model.ProgrammeSearchResult
import com.m3u.data.repository.programme.ProgrammeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class EpgSearchViewModel @Inject constructor(
    repository: ProgrammeRepository,
) : ViewModel() {
    val query = MutableStateFlow("")
    val category = MutableStateFlow("")

    val results: Flow<PagingData<ProgrammeSearchResult>> = combine(
        query.debounce(350),
        category.debounce(350),
    ) { query, category -> query to category }
        .flatMapLatest { (query, category) ->
            repository.searchAll(query, category.takeIf(String::isNotBlank))
        }
        .cachedIn(viewModelScope)
}
