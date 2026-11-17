package com.nevoit.sfcompose.symbols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolDrawAttachmentsTest {
    @Test
    fun nativeIntroStartsAtTheAttachingTieAndRestoresTheOriginalAtCompletion() {
        assertEquals(0f, AttachmentPhase.evaluate(0.3f, 0.4f, true, false).scale, 0f)
        assertEquals(0f, AttachmentPhase.evaluate(0.4f, 0.4f, true, false).scale, 0f)
        val growing = AttachmentPhase.evaluate(0.5f, 0.4f, true, false)
        assertTrue(growing.scale > 0.5f && growing.scale < 1f)
        assertTrue(AttachmentPhase.evaluate(1f, 0.4f, true, false).preserveOriginal)
    }

    @Test
    fun nativeOutroUsesTheShortDurationAndHalfScaleBeforeRemoval() {
        assertTrue(AttachmentPhase.evaluate(0f, 0.4f, false, false).preserveOriginal)
        assertEquals(1f, AttachmentPhase.evaluate(0.5f, 0.4f, false, false).scale, 0f)
        val nearEnd = AttachmentPhase.evaluate(0.9995f, 0.4f, false, false)
        assertTrue(nearEnd.scale in 0.5f..1f)
        assertEquals(0f, AttachmentPhase.evaluate(1f, 0.4f, false, false).scale, 0f)
    }

    @Test
    fun reverseWindowsUseStartProgressOnlyForTheOutro() {
        assertTrue(AttachmentPhase.evaluate(0f, 0.4f, true, true).preserveOriginal)
        assertEquals(0f, AttachmentPhase.evaluate(1f, 0.4f, true, true).scale, 0f)
        assertEquals(AttachmentPhase.evaluate(0.9f, 0.1f, true, true).scale,
            AttachmentPhase.evaluate(0.9f, 0.8f, true, true).scale, 0f)
        assertEquals(0f, AttachmentPhase.evaluate(0.2f, 0.4f, false, true).scale, 0f)
        assertTrue(AttachmentPhase.evaluate(1f, 0.4f, false, true).preserveOriginal)
    }
}
