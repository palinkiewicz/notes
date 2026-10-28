package pl.dakil.notes.model

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.CRC32

/**
 * The bytes of an attachment too large to keep on the heap, behind a handle.
 *
 * A picture is held as a [ByteArray] on its block, and that is fine at a few hundred kilobytes. A
 * PDF can be tens of megabytes, and several paths load a note only to look at it — the library's
 * previews keep dozens of sheets, the index scan reads every changed file — so a PDF held the same
 * way would be read into memory again on each of them. It stays in a file instead, which is also
 * the only thing `PdfRenderer` can open.
 *
 * [size] and [crc32] are known up front because a STORED ZIP entry has to declare both before its
 * first byte is written, and the point of a handle is not to read the whole thing to find out.
 */
interface MediaData {
    val size: Long
    val crc32: Long

    /** The file the bytes are in, when there is one. */
    val file: File?

    fun open(): InputStream
}

/** Media already in memory: tests, and notes read without a spool. */
class InMemoryMedia(val bytes: ByteArray) : MediaData {
    override val size: Long get() = bytes.size.toLong()
    override val crc32: Long by lazy { CRC32().apply { update(bytes) }.value }
    override val file: File? get() = null
    override fun open(): InputStream = ByteArrayInputStream(bytes)
}

/** Media in a file whose length and checksum are already known. */
class FileMedia(
    override val file: File,
    override val size: Long,
    override val crc32: Long,
) : MediaData {
    override fun open(): InputStream = FileInputStream(file)
}

/**
 * Media that was read past and not kept, for a caller that only wanted the rest of the note.
 *
 * It can say how big it was and nothing else. A note holding one is not written back, because
 * writing it would need the bytes this handle threw away.
 */
class DiscardedMedia(override val size: Long, override val crc32: Long) : MediaData {
    override val file: File? get() = null
    override fun open(): InputStream =
        throw IllegalStateException("This attachment was skipped on read and its bytes are gone")
}
