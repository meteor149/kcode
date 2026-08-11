package ai.meteor.kcode

import ai.meteor.kcode.chat.ToolUseEvent
import ai.koog.prompt.streaming.StreamFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class StreamingToolCallTrackerTest {
    @Test
    fun startsToolAsSoonAsNamedDeltaArrives() {
        val tracker = StreamingToolCallTracker(toolRound = 2)

        val event = tracker.onFrame(
            StreamFrame.ToolCallDelta(
                id = "call-1",
                name = "write_file",
                content = "{\"path\":",
                index = 0,
            ),
        )

        val started = assertIs<ToolUseEvent.Started>(event)
        assertEquals("call-1", started.id)
        assertEquals("write_file", started.name)
        assertEquals("{\"path\":", started.input)
    }

    @Test
    fun updatesToolWithAccumulatedArgumentDeltas() {
        val tracker = StreamingToolCallTracker(toolRound = 0)
        tracker.onFrame(StreamFrame.ToolCallDelta("call-1", "write_file", "{\"path\":", 0))

        val event = tracker.onFrame(StreamFrame.ToolCallDelta(null, null, "\"/workspace\"}", 0))

        val updated = assertIs<ToolUseEvent.Updated>(event)
        assertEquals("call-1", updated.id)
        assertEquals("{\"path\":\"/workspace\"}", updated.input)
    }

    @Test
    fun ignoresUnidentifiableDeltaUntilToolNameArrives() {
        val tracker = StreamingToolCallTracker(toolRound = 1)

        assertNull(tracker.onFrame(StreamFrame.ToolCallDelta(null, null, "{", 0)))

        val event = tracker.onFrame(StreamFrame.ToolCallDelta("call-2", "read_file", "}", 0))
        val started = assertIs<ToolUseEvent.Started>(event)
        assertEquals("{}", started.input)
    }

    @Test
    fun startsCompletedToolWithoutProviderIdOrIndex() {
        val tracker = StreamingToolCallTracker(toolRound = 3)

        val event = tracker.onFrame(StreamFrame.ToolCallComplete(null, "refresh", "{}", null))

        val started = assertIs<ToolUseEvent.Started>(event)
        assertEquals("refresh:3:0", started.id)
    }
}
