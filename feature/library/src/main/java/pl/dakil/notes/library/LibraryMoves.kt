package pl.dakil.notes.library

import androidx.compose.runtime.Immutable
import pl.dakil.notes.data.NoteRepository.Companion.childPath
import pl.dakil.notes.data.StoreRef

/** A move the user has started but not yet landed: what is travelling, and where it set off from. */
@Immutable
data class MoveRequest(
    val items: List<LibraryItem>,
    /**
     * Where each item is now, so the store can detach it from that folder.
     *
     * Per item rather than one shared parent, because a move can be started from search results —
     * and those can come from anywhere beneath the folder that was searched.
     */
    val parents: Map<StoreRef, StoreRef>,
)

/**
 * Whether a pending move may land in the folder now on screen.
 *
 * Two things have to be refused, and only one of them is obvious. Dropping a folder inside itself
 * would detach a whole subtree from the library and — on a filesystem that allows it — make a loop
 * nothing can walk out of. Dropping items where they all already are is merely pointless, but an
 * enabled button that does nothing is worse than a disabled one that explains itself.
 */
fun canMoveInto(request: MoveRequest, targetPath: String): Boolean {
    if (request.items.isEmpty()) return false
    // "All" rather than "any": a selection gathered from a search can be spread across folders, and
    // one of them already being here is no reason to refuse to gather the rest.
    if (request.items.all { it.path == targetPath }) return false
    return request.items.none { item ->
        item is LibraryItem.Folder && isSelfOrDescendant(targetPath, childPath(item.path, item.name))
    }
}

/** True when [target] is [folder] itself or somewhere beneath it. */
private fun isSelfOrDescendant(target: String, folder: String): Boolean =
    target == folder || target.startsWith("$folder/")
