package com.example.smbtv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbEntryTest {

    @Test
    fun recognisesVideoExtensionsCaseInsensitively() {
        assertTrue(SmbEntry("Film.MKV", false, 1).isVideo)
        assertTrue(SmbEntry("clip.webm", false, 1).isVideo)
        assertTrue(SmbEntry("show.s01e01.m2ts", false, 1).isVideo)
    }

    @Test
    fun rejectsNonVideoFilesAndNamesWithoutExtension() {
        assertFalse(SmbEntry("readme.txt", false, 1).isVideo)
        assertFalse(SmbEntry("cover.jpg", false, 1).isVideo)
        assertFalse(SmbEntry("Makefile", false, 1).isVideo)
        assertFalse(SmbEntry("archive.mkv.part", false, 1).isVideo)
    }
}
