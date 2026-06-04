package com.example.gtfsconverter

/**
 * In-memory fake for [GtfsRowSource] — the test counterpart to the production [FileGtfsRowSource].
 * Seeded with `tableFile -> rows` (each row a column→value map), it lets the build run entirely in
 * memory with no CSV files on disk. Mirrors the app's `Fake*Source` convention.
 */
class InMemoryGtfsRowSource(
    private val tables: Map<String, List<Map<String, String>>>,
) : GtfsRowSource {
    override fun forEachRow(tableFile: String, action: (Map<String, String>) -> Unit) {
        tables[tableFile].orEmpty().forEach(action)
    }
}
