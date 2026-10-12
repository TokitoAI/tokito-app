package dev.tokito.physical.model

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ReferenceTwinCatalogTest {
    @Test fun missingCatalogAdvertisesNoAssets() {
        val root = Files.createTempDirectory("twin-catalog").toFile()
        try { assertTrue(ReferenceTwinCatalog.read(root).isEmpty()) } finally { root.deleteRecursively() }
    }
    @Test fun explicitCatalogRoutesOnlyDeclaredFiles() {
        val root = Files.createTempDirectory("twin-catalog").toFile()
        try {
            val directory = root.resolve("board").apply { mkdir() }
            directory.resolve("manifest.json").writeText("{}")
            directory.resolve("board.glb").writeText("not geometry: parser routing fixture only")
            root.resolve("reference-twins.json").writeText("""{"schema_version":1,"entries":[{"label":"Source A","manifest":"board/manifest.json","glb":"board/board.glb","context_revision":"source:1"}]}""")
            val entry = ReferenceTwinCatalog.read(root).single()
            assertEquals("Source A", entry.label)
            assertEquals(directory.resolve("board.glb").canonicalFile, entry.glb)
            assertThrows(IllegalArgumentException::class.java) { ReferenceTwinCatalog.verify(root) }
            root.resolve("reference-twins.json").writeText("""{"schema_version":1,"entries":[{"label":"Source A","manifest":"../manifest.json","glb":"board/board.glb","context_revision":"source:1"}]}""")
            assertThrows(IllegalArgumentException::class.java) { ReferenceTwinCatalog.read(root) }
        } finally { root.deleteRecursively() }
    }
}
