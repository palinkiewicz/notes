package pl.dakil.notes.editor.canvas

import android.view.MotionEvent
import pl.dakil.notes.model.PointerSample
import pl.dakil.notes.model.ToolType

/**
 * Converts `MotionEvent` into the engine's device-independent [PointerSample].
 *
 * Raw `MotionEvent` is used rather than Compose's own pointer API for two reasons that matter:
 *
 * 1. Compose exposes `PointerType` and `pressure`, but not `getTouchMajor` (the contact-ellipse
 *    size that palm rejection needs) or `AXIS_TILT` / `AXIS_ORIENTATION` (needed for nib-shaped
 *    tools).
 * 2. An S-Pen samples at around 240 Hz while the display runs at 60–120 Hz, so Android batches the
 *    extra samples into the event's *historical* buffer. Reading only the current position throws
 *    away more than half of every stroke — the difference is plainly visible as faceting on fast
 *    curves.
 */
object MotionEventBridge {

    fun toolTypeOf(event: MotionEvent, pointerIndex: Int): ToolType =
        when (event.getToolType(pointerIndex)) {
            MotionEvent.TOOL_TYPE_STYLUS -> ToolType.STYLUS
            // The barrel button or the flipped end of an S-Pen reports as a distinct tool, which is
            // what lets "turn the pen over to erase" work without a mode switch.
            MotionEvent.TOOL_TYPE_ERASER -> ToolType.ERASER
            MotionEvent.TOOL_TYPE_MOUSE -> ToolType.MOUSE
            MotionEvent.TOOL_TYPE_FINGER -> ToolType.FINGER
            else -> ToolType.UNKNOWN
        }

    /** The current sample for [pointerIndex], in view-local pixels. */
    fun sampleOf(event: MotionEvent, pointerIndex: Int): PointerSample = PointerSample(
        x = event.getX(pointerIndex),
        y = event.getY(pointerIndex),
        pressure = event.getPressure(pointerIndex),
        tilt = event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex),
        orientation = event.getOrientation(pointerIndex),
        touchMajor = event.getTouchMajor(pointerIndex),
        toolType = toolTypeOf(event, pointerIndex),
        timeMs = event.eventTime,
        pointerId = event.getPointerId(pointerIndex),
        primaryButton = event.isPrimaryButtonDown(),
    )

    /**
     * Replays every buffered sample for [pointerIndex], oldest first, then the current one.
     *
     * This is the whole reason for using `MotionEvent` directly. [block] is called once per sample.
     */
    inline fun forEachSample(
        event: MotionEvent,
        pointerIndex: Int,
        block: (PointerSample) -> Unit,
    ) {
        val pointerId = event.getPointerId(pointerIndex)
        val toolType = toolTypeOf(event, pointerIndex)
        val primaryButton = event.isPrimaryButtonDown()

        for (h in 0 until event.historySize) {
            block(
                PointerSample(
                    x = event.getHistoricalX(pointerIndex, h),
                    y = event.getHistoricalY(pointerIndex, h),
                    pressure = event.getHistoricalPressure(pointerIndex, h),
                    tilt = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, h),
                    orientation = event.getHistoricalOrientation(pointerIndex, h),
                    touchMajor = event.getHistoricalTouchMajor(pointerIndex, h),
                    toolType = toolType,
                    timeMs = event.getHistoricalEventTime(h),
                    pointerId = pointerId,
                    primaryButton = primaryButton,
                )
            )
        }
        block(sampleOf(event, pointerIndex))
    }

    /**
     * Whether a button that means "draw" is held.
     *
     * A finger or stylus touching the glass has no buttons and reports zero, which must read as
     * pressed; a mouse hovering with no button down must not.
     */
    fun MotionEvent.isPrimaryButtonDown(): Boolean {
        if (getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE) return true
        return buttonState and MotionEvent.BUTTON_PRIMARY != 0
    }
}
