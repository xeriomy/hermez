package dev.hermes.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for FileEntry and DirectoryListing data classes.
 *
 * Pure data tests — no Android or network dependencies.
 */
class FileEntryTest {

    @Test
    fun `FileEntry with type dir is browsable directory`() {
        val entry = FileEntry(
            name = "src",
            path = "src",
            type = "dir",
            size = null,
            modified = null,
            isDirectory = false  // type="dir" should override
        )
        assertTrue(entry.isBrowsableDirectory)
    }

    @Test
    fun `FileEntry with isDirectory true is browsable`() {
        val entry = FileEntry(
            name = "docs",
            path = "docs",
            type = null,
            size = null,
            modified = null,
            isDirectory = true
        )
        assertTrue(entry.isBrowsableDirectory)
    }

    @Test
    fun `FileEntry with type file is not browsable`() {
        val entry = FileEntry(
            name = "readme.md",
            path = "readme.md",
            type = "file",
            size = 1024,
            modified = 1783449907.0,
            isDirectory = false
        )
        assertFalse(entry.isBrowsableDirectory)
    }

    @Test
    fun `FileEntry with no dir flags is not browsable`() {
        val entry = FileEntry(
            name = "script.sh",
            path = "script.sh",
            type = null,
            size = 512,
            modified = null,
            isDirectory = false
        )
        assertFalse(entry.isBrowsableDirectory)
    }

    @Test
    fun `DirectoryListing holds entries and path`() {
        val entries = listOf(
            FileEntry("a.txt", "a.txt", "file", 100, null, false),
            FileEntry("b", "b", "dir", null, null, true)
        )
        val listing = DirectoryListing(entries, ".", "workspace1")
        assertEquals(2, listing.entries.size)
        assertEquals(".", listing.path)
        assertEquals("workspace1", listing.workspace)
    }
}

/**
 * Unit tests for ModelOption data class.
 */
class ModelOptionTest {

    @Test
    fun `toString without provider`() {
        val option = ModelOption(id = "gpt-4", name = "GPT-4", provider = null)
        assertEquals("GPT-4", option.toString())
    }

    @Test
    fun `toString with provider`() {
        val option = ModelOption(id = "claude-3", name = "Claude 3", provider = "anthropic")
        assertEquals("Claude 3 (anthropic)", option.toString())
    }

    @Test
    fun `equality based on all fields`() {
        val a = ModelOption("gpt-4", "GPT-4", "openai")
        val b = ModelOption("gpt-4", "GPT-4", "openai")
        val c = ModelOption("gpt-4", "GPT-4", null)
        assertEquals(a, b)
        assertFalse(a == c)
    }
}
