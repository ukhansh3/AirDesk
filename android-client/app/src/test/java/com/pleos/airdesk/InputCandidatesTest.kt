package com.pleos.airdesk

import org.junit.Assert.*
import org.junit.Test

class InputCandidatesTest {
    @Test fun pointerMappingUsesActualContentDimensions() {
        assertEquals(.5f to .25f, InputCandidates.normalized(100f, 50f, 200f, 200f))
        assertEquals(1f to 0f, InputCandidates.normalized(300f, -5f, 200f, 200f))
        assertNull(InputCandidates.normalized(0f, 0f, 0f, 100f))
        assertNull(InputCandidates.normalized(Float.NaN, 0f, 100f, 100f))
    }
    @Test fun heldStateIsIdempotentAndReleasedOnCancel() {
        val candidates = InputCandidates()
        assertNotNull(candidates.down())
        assertNull(candidates.down())
        candidates.key("ShiftLeft", true)
        assertEquals(2, candidates.heldCount)
        assertEquals("input.release_all", candidates.release("USER_REQUEST").type)
        assertEquals(0, candidates.heldCount)
        assertNull(candidates.up())
    }
    @Test fun keyboardOnlyAcceptsApprovedPhysicalCodesAndValidCommittedUnicode() {
        val candidates = InputCandidates()
        assertNull(candidates.key("UnapprovedKey", true))
        assertNotNull(candidates.text("한글 English 😀"))
        assertNotNull(candidates.text("😀".repeat(1024)))
        assertNull(candidates.text("😀".repeat(1025)))
        assertNull(candidates.text("\uD800"))
        assertNull(candidates.text(""))
    }
    @Test fun pinchLossReleasesAndReacquisitionDoesNotRestoreDrag() {
        val candidates = InputCandidates()
        val pinch = DiagnosticUiState(authorized = true, handDetected = true, gesturePhase = GesturePhase.PINCHED, x = .2f, y = .4f)
        assertEquals(listOf("pointer.move", "pointer.down"), candidates.gesture(pinch).map { it.type })
        assertEquals(1, candidates.heldCount)
        assertEquals("input.release_all", candidates.gesture(pinch.copy(handDetected = false, gesturePhase = GesturePhase.LOST)).single().type)
        assertEquals(0, candidates.heldCount)
        assertEquals(listOf("pointer.move"), candidates.gesture(pinch.copy(gesturePhase = GesturePhase.TRACKING)).map { it.type })
    }
    @Test fun scrollingIsBoundedInCanonicalLines() {
        assertEquals(2, InputCandidates.scrollLines(50f))
        assertEquals(-2, InputCandidates.scrollLines(-50f))
        assertEquals(120, InputCandidates().scroll(300)!!.payload.getInt("deltaY"))
    }
}
