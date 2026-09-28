package com.snapreel.app.preservation

import com.snapreel.app.data.model.MediaItem
import com.snapreel.app.testsupport.FakeDocumentsProvider
import com.snapreel.app.testsupport.FakeDocumentsProvider.FakeFile
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map

/** Generators shared by the preservation tests. All of them stay outside the bug condition. */
object PreservationArbs {

    // Few distinct bases (with case variants) so NAME_* orders hit lowercase ties.
    private val bases = listOf("clip", "Clip", "CLIP", "img", "IMG_0001", "a", "B", "b", "holiday", "Holiday", "z", "émile", "10", "9")
    private val exts = listOf(
        "mp4", "MP4", "mkv", "webm", "3gp", "mov", "avi", "m4v", "ts", "flv",
        "jpg", "JPEG", "png", "webp", "gif", "bmp",
        "txt", "pdf", "", // unsupported, or no extension
    )
    private val mimes = listOf(
        "video/mp4", "video/x-matroska", "image/jpeg", "image/png",
        "", "application/octet-stream", "text/plain",
    )

    /** Small ranges produce ties (stable-sort order matters); large ones are realistic. */
    private val tieOrWide = { wide: LongRange -> Arb.choice(Arb.long(0L..3L), Arb.long(wide)) }

    /** One child row of the fake SAF tree (documentId is assigned by [folder]). */
    val fakeFile: Arb<FakeFile> = arbitrary {
        val base = Arb.element(bases).bind()
        val ext = Arb.element(exts).bind()
        FakeFile(
            documentId = "",
            name = if (ext.isEmpty()) base else "$base.$ext",
            mimeType = Arb.element(mimes).bind(),
            size = tieOrWide(0L..5_000_000_000L).bind(),
            lastModified = tieOrWide(0L..2_000_000_000_000L).bind(),
        )
    }

    /** A folder's children with unique document ids, in provider order. */
    fun folder(sizes: IntRange = 0..25): Arb<List<FakeFile>> =
        Arb.list(fakeFile, sizes).map { files -> files.mapIndexed { i, f -> f.copy(documentId = "doc$i") } }

    /** A folder guaranteed to list at least one video (a `video/mp4` `.mp4` row is inserted at a random spot). */
    fun folderWithVideo(sizes: IntRange = 0..20): Arb<List<FakeFile>> = arbitrary {
        val rest = Arb.list(fakeFile, sizes).bind()
        val at = Arb.int(0..rest.size).bind()
        val video = FakeFile("", "reel.mp4", "video/mp4", Arb.long(0L..3L).bind(), Arb.long(0L..3L).bind())
        (rest.take(at) + video + rest.drop(at)).mapIndexed { i, f -> f.copy(documentId = "doc$i") }
    }

    /** What the unfixed scan lists for [files], in provider order (before sorting). */
    fun expectedScan(files: List<FakeFile>): List<MediaItem> = files.mapNotNull {
        LegacyOracles.scanRow(FakeDocumentsProvider.treeUri, it.documentId, it.name, it.mimeType, it.size, it.lastModified)
    }

    /** A scanned list as the viewers see it: unique URIs, random video/image mix. */
    val mediaList: Arb<List<MediaItem>> = Arb.list(Arb.boolean(), 0..30).map { flags ->
        flags.mapIndexed { i, isVideo ->
            MediaItem(
                uri = FakeDocumentsProvider.documentUri("doc$i"),
                name = if (isVideo) "v$i.mp4" else "i$i.jpg",
                mimeType = if (isVideo) "video/mp4" else "image/jpeg",
                size = i.toLong(),
                dateModified = i.toLong(),
                isVideo = isVideo,
            )
        }
    }
}
