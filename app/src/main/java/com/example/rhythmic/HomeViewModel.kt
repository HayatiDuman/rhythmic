package com.example.rhythmic

import androidx.lifecycle.ViewModel
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.search.SearchExtractor

class HomeViewModel : ViewModel() {
    // Sayfa ölse bile bu değişkenler hayatta kalacak!
    var currentQuery = ""
    var isSearchLoading = false
    var hasMoreResults = true
    var searchExtractor: SearchExtractor? = null
    var nextPage: Page? = null
    val searchResults = mutableListOf<MusicModel>()
}