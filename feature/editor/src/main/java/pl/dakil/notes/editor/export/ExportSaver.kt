package pl.dakil.notes.editor.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Saves rendered export files wherever the user points the system's own picker.
 *
 * One file — a PDF, or a single-page PNG — goes through the ordinary "save as" prompt, which lets
 * the user name it. Several PNGs cannot: that prompt makes exactly one document. They go to a
 * folder the user picks instead, one file per page, named after the note and the page number.
 *
 * Returns the function to call with the files once they are rendered, on the main thread.
 * [onResult] hears whether they arrived; a picker the user backs out of reports nothing at all,
 * since nothing went wrong.
 */
@Composable
fun rememberExportSaver(onResult: (saved: Boolean) -> Unit): (files: List<File>, title: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val report by rememberUpdatedState(onResult)
    // Not saveable: the files are in the cache and re-rendered on every export, so a pending save
    // that outlived the process would point at nothing.
    val slot = remember { Slot() }

    fun finish(write: (Pending) -> Unit) {
        val job = slot.job ?: return
        slot.job = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { write(job) }.isSuccess }
            report(ok)
        }
    }

    val single: (Uri?) -> Unit = { uri ->
        if (uri == null) slot.job = null
        else finish { job -> copyInto(context, job.files.single(), uri) }
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME), single)
    val png = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PNG_MIME), single)
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) slot.job = null
        else finish { job -> saveIntoTree(context, tree, job) }
    }

    return { files, title ->
        if (files.isNotEmpty()) {
            val job = Pending(files, title.ifBlank { DEFAULT_NAME })
            slot.job = job
            when {
                files.size > 1 -> folder.launch(null)
                files[0].extension.equals("pdf", ignoreCase = true) -> pdf.launch(job.nameOf(files[0]))
                else -> png.launch(job.nameOf(files[0]))
            }
        }
    }
}

/** Where the files wait while the picker is up; plain, since nothing draws from it. */
private class Slot {
    var job: Pending? = null
}

private class Pending(val files: List<File>, val title: String) {
    /** `export.pdf` -> `Title.pdf`, `export_3.png` -> `Title_3.png`: the page number survives. */
    fun nameOf(file: File): String = title + file.name.removePrefix(DEFAULT_NAME)
}

private fun copyInto(context: Context, file: File, uri: Uri) {
    val out = context.contentResolver.openOutputStream(uri, "wt")
        ?: throw IOException("Could not open $uri")
    out.use { sink -> file.inputStream().use { it.copyTo(sink) } }
}

private fun saveIntoTree(context: Context, tree: Uri, job: Pending) {
    val resolver = context.contentResolver
    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    for (file in job.files) {
        // A name already taken is the provider's to resolve; every stock one appends " (1)".
        val target = DocumentsContract.createDocument(resolver, parent, PNG_MIME, job.nameOf(file))
            ?: throw IOException("Could not create ${job.nameOf(file)}")
        copyInto(context, file, target)
    }
}

private const val PDF_MIME = "application/pdf"
private const val PNG_MIME = "image/png"

/** What the exporters call their files, and what a note with no title is saved as. */
private const val DEFAULT_NAME = "export"
