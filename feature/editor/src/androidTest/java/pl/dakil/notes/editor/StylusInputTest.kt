package pl.dakil.notes.editor

import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.dakil.notes.editor.canvas.MotionEventBridge
import pl.dakil.notes.ink.InputIntent
import pl.dakil.notes.ink.InputRouter
import pl.dakil.notes.model.InputConfig
import pl.dakil.notes.model.PointerSample
import pl.dakil.notes.model.ToolType

/**
 * Exercises the one path that cannot be tested on the JVM or checked by hand on an emulator: real
 * `MotionEvent`s carrying stylus tool types, contact sizes and batched historical samples.
 *
 * The classification *logic* is covered by fast JVM tests in `:core:ink`; what is verified here is
 * the bridge between Android's event model and that logic — the part where a wrong constant or a
 * forgotten history loop silently halves every stroke.
 */
@RunWith(AndroidJUnit4::class)
class StylusInputTest {

    private fun event(
        action: Int,
        toolTypes: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        pressures: FloatArray = FloatArray(toolTypes.size) { 1f },
        touchMajors: FloatArray = FloatArray(toolTypes.size) { 10f },
        eventTime: Long = 1_000L,
        actionIndex: Int = 0,
    ): MotionEvent {
        val n = toolTypes.size
        val properties = Array(n) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                this.toolType = toolTypes[i]
            }
        }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().apply {
                x = xs[i]
                y = ys[i]
                pressure = pressures[i]
                size = 1f
                touchMajor = touchMajors[i]
                setAxisValue(MotionEvent.AXIS_TILT, 0.5f)
            }
        }
        val maskedAction = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        return MotionEvent.obtain(
            /* downTime = */ 1_000L,
            /* eventTime = */ eventTime,
            /* action = */ maskedAction,
            /* pointerCount = */ n,
            /* pointerProperties = */ properties,
            /* pointerCoords = */ coords,
            /* metaState = */ 0,
            /* buttonState = */ 0,
            /* xPrecision = */ 1f,
            /* yPrecision = */ 1f,
            /* deviceId = */ 0,
            /* edgeFlags = */ 0,
            /* source = */ InputDevice.SOURCE_STYLUS,
            /* flags = */ 0,
        )
    }

    // ---- Tool type mapping -----------------------------------------------------------------------

    @Test
    fun toolTypesMapToTheEngineVocabulary() {
        val cases = mapOf(
            MotionEvent.TOOL_TYPE_STYLUS to ToolType.STYLUS,
            MotionEvent.TOOL_TYPE_ERASER to ToolType.ERASER,
            MotionEvent.TOOL_TYPE_FINGER to ToolType.FINGER,
            MotionEvent.TOOL_TYPE_MOUSE to ToolType.MOUSE,
            MotionEvent.TOOL_TYPE_UNKNOWN to ToolType.UNKNOWN,
        )
        for ((androidType, expected) in cases) {
            val e = event(MotionEvent.ACTION_DOWN, intArrayOf(androidType), floatArrayOf(5f), floatArrayOf(5f))
            assertEquals(expected, MotionEventBridge.toolTypeOf(e, 0))
            e.recycle()
        }
    }

    @Test
    fun stylusSampleCarriesPressureTiltAndContactSize() {
        val e = event(
            MotionEvent.ACTION_DOWN,
            intArrayOf(MotionEvent.TOOL_TYPE_STYLUS),
            floatArrayOf(120f), floatArrayOf(340f),
            pressures = floatArrayOf(0.62f),
            touchMajors = floatArrayOf(7f),
        )
        val sample = MotionEventBridge.sampleOf(e, 0)
        assertEquals(120f, sample.x, 1e-3f)
        assertEquals(340f, sample.y, 1e-3f)
        assertEquals(0.62f, sample.pressure, 1e-3f)
        assertEquals(0.5f, sample.tilt, 1e-3f)
        assertEquals(7f, sample.touchMajor, 1e-3f)
        assertEquals(ToolType.STYLUS, sample.toolType)
        e.recycle()
    }

    // ---- The historical batch --------------------------------------------------------------------

    @Test
    fun batchedHistoricalSamplesAreAllReplayed() {
        // An S-Pen reports at ~240 Hz into 60-120 Hz frames, so Android batches the surplus into the
        // event's history. Reading only the current position throws away more than half of every
        // stroke — this is the single most consequential thing the bridge has to get right.
        val e = event(
            MotionEvent.ACTION_MOVE,
            intArrayOf(MotionEvent.TOOL_TYPE_STYLUS),
            floatArrayOf(0f), floatArrayOf(0f),
            eventTime = 1_000L,
        )
        val batched = 6
        for (i in 1..batched) {
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = i * 10f
                    y = i * 5f
                    pressure = 0.5f
                    size = 1f
                    touchMajor = 8f
                }
            )
            e.addBatch(1_000L + i * 4L, coords, 0)
        }

        val collected = ArrayList<PointerSample>()
        MotionEventBridge.forEachSample(e, 0) { collected += it }

        // Every historical sample plus the current one.
        assertEquals(batched + 1, collected.size)
        assertEquals(e.historySize, batched)

        // Oldest first, ending at the current position.
        assertTrue(
            "samples must arrive in time order",
            collected.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs },
        )
        assertEquals(e.x, collected.last().x, 1e-3f)
        assertEquals(e.y, collected.last().y, 1e-3f)
        e.recycle()
    }

    @Test
    fun anEventWithNoHistoryStillYieldsItsCurrentSample() {
        val e = event(
            MotionEvent.ACTION_MOVE,
            intArrayOf(MotionEvent.TOOL_TYPE_STYLUS),
            floatArrayOf(42f), floatArrayOf(24f),
        )
        val collected = ArrayList<PointerSample>()
        MotionEventBridge.forEachSample(e, 0) { collected += it }
        assertEquals(1, collected.size)
        assertEquals(42f, collected.single().x, 1e-3f)
        e.recycle()
    }

    // ---- End-to-end through the router -----------------------------------------------------------

    @Test
    fun aStylusDrawsWhileAPalmRestingBesideItIsRejected() {
        val router = InputRouter(InputConfig(fingerDrawingEnabled = false, palmRejectionWindowMs = 120))

        val pen = event(
            MotionEvent.ACTION_DOWN,
            intArrayOf(MotionEvent.TOOL_TYPE_STYLUS),
            floatArrayOf(300f), floatArrayOf(400f),
            eventTime = 5_000L,
        )
        assertEquals(InputIntent.Draw, router.begin(MotionEventBridge.sampleOf(pen, 0)).intent)
        pen.recycle()

        // A hand settling on the glass 20 ms later, with a palm-sized contact patch.
        val palm = event(
            MotionEvent.ACTION_POINTER_DOWN,
            intArrayOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_FINGER),
            floatArrayOf(300f, 500f), floatArrayOf(400f, 900f),
            touchMajors = floatArrayOf(6f, 180f),
            eventTime = 5_020L,
            actionIndex = 1,
        )
        assertEquals(InputIntent.Ignore, router.begin(MotionEventBridge.sampleOf(palm, 1)).intent)
        palm.recycle()

        assertTrue("the pen must keep drawing", router.isDrawing)
    }

    @Test
    fun theEraserEndOfAStylusIsRecognised() {
        val router = InputRouter()
        val e = event(
            MotionEvent.ACTION_DOWN,
            intArrayOf(MotionEvent.TOOL_TYPE_ERASER),
            floatArrayOf(100f), floatArrayOf(100f),
        )
        val sample = MotionEventBridge.sampleOf(e, 0)
        assertEquals(ToolType.ERASER, sample.toolType)
        assertEquals(InputIntent.Draw, router.begin(sample).intent)
        e.recycle()
    }

    @Test
    fun aPalmThatLandsBeforeThePenIsRevoked() {
        // The time-window rule cannot catch a palm that touched down first; the stylus arriving
        // must retract the streak it already drew.
        val router = InputRouter(InputConfig(fingerDrawingEnabled = true))

        val palm = event(
            MotionEvent.ACTION_DOWN,
            intArrayOf(MotionEvent.TOOL_TYPE_FINGER),
            floatArrayOf(500f), floatArrayOf(900f),
            touchMajors = floatArrayOf(40f),
            eventTime = 9_000L,
        )
        assertEquals(InputIntent.Draw, router.begin(MotionEventBridge.sampleOf(palm, 0)).intent)
        palm.recycle()

        val pen = event(
            MotionEvent.ACTION_POINTER_DOWN,
            intArrayOf(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_STYLUS),
            floatArrayOf(500f, 300f), floatArrayOf(900f, 400f),
            eventTime = 9_030L,
            actionIndex = 1,
        )
        val decision = router.begin(MotionEventBridge.sampleOf(pen, 1))
        assertEquals(InputIntent.Draw, decision.intent)
        assertEquals(listOf(0), decision.revoked)
        pen.recycle()
    }
}
