package com.chrono.app.data

/** Match only a prepared capture identity, never the most recent display label. */
internal fun matchingDraft(drafts: List<TestResult>, saved: List<TestResult>, device: String, boot: Long, id: Int): TestResult? {
    if (device.isBlank() || boot == 0L || id <= 0) return null
    return drafts.filter {
        it.deviceSerial == device && it.bootId == boot && it.deviceResultId == id &&
            saved.none { result -> result.linkedDraftUid == it.uid }
    }.singleOrNull()
}

/** Keep capture identity and raw measurement; copy only the selected setup info. */
internal fun TestResult.withShotInfo(draft: TestResult): TestResult = copy(
    label = draft.label, distanceM = draft.distanceM,
    measurementErrorM = draft.measurementErrorM, measurementErrorUnit = draft.measurementErrorUnit,
    tool = draft.tool, shotType = draft.shotType, disruptorLoading = draft.disruptorLoading,
    projectileType = draft.projectileType, target = draft.target,
    targetDistValue = draft.targetDistValue, targetDistUnit = draft.targetDistUnit,
    passFail = draft.passFail, specialNotes = draft.specialNotes, outcome = draft.outcome,
    shotFolder = draft.shotFolder, thumbnailUri = draft.thumbnailUri,
    needsShotInfo = false, linkedDraftUid = draft.uid, accepted = false,
)
