package com.chrono.app.data

/** Public files import missing records; a delayed scan never overwrites a saved edit. */
internal fun recoverResults(public: List<TestResult>, saved: List<TestResult>): List<TestResult> {
    val savedIds = saved.mapTo(hashSetOf()) { it.uid }
    // Older versions could assign the same folder to different captures. Keep both
    // identities so importing an overwritten public copy never loses the local one.
    return (saved + public.filter { it.uid !in savedIds })
        .distinctBy { it.uid }
        .sortedByDescending { it.epochMillis ?: 0L }
}
