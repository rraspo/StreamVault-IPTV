package com.streamvault.data.manager

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingStatus
import org.junit.Test

class RecordingConflictDetectorTest {

    @Test
    fun `findRecordingConflict returns overlapping scheduled item`() {
        val conflict = listOf(
            recordingItem(
                id = "scheduled-1",
                startMs = 1_000L,
                endMs = 5_000L,
                status = RecordingStatus.SCHEDULED
            )
        ).findRecordingConflict(
            candidateStartMs = 2_000L,
            candidateEndMs = 4_000L,
            statuses = setOf(RecordingStatus.SCHEDULED, RecordingStatus.RECORDING)
        )

        assertThat(conflict?.id).isEqualTo("scheduled-1")
    }

    @Test
    fun `findRecordingConflict ignores terminal recordings`() {
        val conflict = listOf(
            recordingItem(
                id = "completed-1",
                startMs = 1_000L,
                endMs = 5_000L,
                status = RecordingStatus.COMPLETED
            )
        ).findRecordingConflict(
            candidateStartMs = 2_000L,
            candidateEndMs = 4_000L,
            statuses = setOf(RecordingStatus.SCHEDULED, RecordingStatus.RECORDING)
        )

        assertThat(conflict).isNull()
    }

    @Test
    fun `findRecordingConflict treats adjacent windows as non-overlapping`() {
        val conflict = listOf(
            recordingItem(
                id = "recording-1",
                startMs = 1_000L,
                endMs = 5_000L,
                status = RecordingStatus.RECORDING
            )
        ).findRecordingConflict(
            candidateStartMs = 5_000L,
            candidateEndMs = 8_000L,
            statuses = setOf(RecordingStatus.SCHEDULED, RecordingStatus.RECORDING)
        )

        assertThat(conflict).isNull()
    }

    private fun recordingItem(
        id: String,
        startMs: Long,
        endMs: Long,
        status: RecordingStatus
    ) = RecordingItem(
        id = id,
        providerId = 1L,
        channelId = 100L,
        channelName = "Sports 1",
        streamUrl = "https://example.com/live.ts",
        scheduledStartMs = startMs,
        scheduledEndMs = endMs,
        programTitle = "Match Day",
        outputPath = "/tmp/$id.ts",
        status = status
    )
}