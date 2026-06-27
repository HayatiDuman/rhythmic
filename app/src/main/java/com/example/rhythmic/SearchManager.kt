package com.example.rhythmic

import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.search.SearchExtractor

object SearchManager {
    var currentQuery = ""
    var isSearchLoading = false
    var hasMoreResults = true
    var searchExtractor: SearchExtractor? = null
    var nextPage: Page? = null
    val searchResults = mutableListOf<MusicModel>()
}