package com.example.smbtv

import org.junit.Assert.assertEquals
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

    @Test
    fun recognisesPhotosAndKeepsFoldersOutOfMedia() {
        assertTrue(SmbEntry("IMG_0001.JPG", false, 1).isImage)
        assertTrue(SmbEntry("pano.heic", false, 1).isMedia)
        assertFalse(SmbEntry("clip.mp4", false, 1).isImage)
        assertFalse(SmbEntry("Holiday.jpg", true, 0).isMedia)
    }

    @Test
    fun treatsCameraRawAsPhotos() {
        assertTrue(SmbEntry("DSC_0420.NEF", false, 1).isImage)
        assertTrue(SmbEntry("IMG_1234.CR3", false, 1).isImage)
        assertTrue(SmbEntry("pic.dng", false, 1).isMedia)
    }

    @Test
    fun findsEmbeddedJpegStarts() {
        val ff = 0xFF.toByte()
        val d8 = 0xD8.toByte()
        val bytes = byteArrayOf(0x49, 0x49, ff, d8, ff, 0x00, ff, d8, 0x01, ff, d8, ff)
        assertEquals(listOf(2, 9), jpegStarts(bytes))
    }
}
