package pl.dakil.notes.sync

import pl.dakil.notes.model.json.JsonArray
import pl.dakil.notes.model.json.JsonBool
import pl.dakil.notes.model.json.JsonNumber
import pl.dakil.notes.model.json.JsonObject
import pl.dakil.notes.model.json.JsonReader
import pl.dakil.notes.model.json.JsonString
import pl.dakil.notes.model.json.JsonValue
import pl.dakil.notes.model.json.JsonWriter

/**
 * The parts of the app's settings a backup carries, each of which a restore can take or leave.
 *
 * The notes themselves are not one of these: they are the archive's ordinary entries, and have
 * always been. Keyed by an explicit string, never by ordinal or `name`, because the key is written
 * into every backup and has to mean the same thing to a build that reorders the constants.
 */
enum class BackupGroup(val key: String) {
    /** Appearance, input, keyboard, editor, page defaults, export choices, the backup schedule. */
    SETTINGS("settings"),

    /** Every tool's colour, width and opacity, and which pen and eraser were in hand. */
    TOOLS("tools"),

    /** Saved and recent colours. */
    PALETTE("palette"),

    /** Saved paper themes and which one new notes use. */
    PAPER("paperThemes");

    companion object {
        fun fromKey(key: String?): BackupGroup? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One stored preference, with the type it was stored as.
 *
 * The type travels with the value because JSON cannot tell an int from a long from a whole-number
 * float, and `SharedPreferences` can: reading back a long with `getInt` throws, which would turn a
 * restored backup into an app that no longer starts.
 */
sealed interface PrefValue {
    data class Bool(val value: Boolean) : PrefValue
    data class IntV(val value: Int) : PrefValue
    data class LongV(val value: Long) : PrefValue
    data class FloatV(val value: Float) : PrefValue
    data class Str(val value: String) : PrefValue
    data class StrSet(val value: Set<String>) : PrefValue

    companion object {
        /** Wraps what `SharedPreferences.getAll` hands back; null for anything it cannot hold. */
        fun of(raw: Any?): PrefValue? = when (raw) {
            is Boolean -> Bool(raw)
            is Int -> IntV(raw)
            is Long -> LongV(raw)
            is Float -> FloatV(raw)
            is String -> Str(raw)
            is Set<*> -> StrSet(raw.filterIsInstance<String>().toSet())
            else -> null
        }
    }
}

/**
 * The settings half of a backup: `.daknote-settings.json` at the root of the archive.
 *
 * Written deterministically — groups in a fixed order, keys sorted, no timestamp — for the same
 * reason the archive is: two backups of an unchanged device are then byte-identical, so the backup
 * folder's retention count counts something and a synced folder stops re-uploading the same bytes.
 */
data class SettingsSnapshot(val groups: Map<BackupGroup, Map<String, PrefValue>>) {

    fun encode(): String {
        var groupsJson = JsonObject.EMPTY
        for (group in BackupGroup.entries) {
            val values = groups[group]?.takeIf { it.isNotEmpty() } ?: continue
            var obj = JsonObject.EMPTY
            for (key in values.keys.sorted()) obj = obj.with(key, writeValue(values.getValue(key)))
            groupsJson = groupsJson.with(group.key, obj)
        }
        return JsonWriter.write(
            JsonObject.EMPTY
                .with(FORMAT, FORMAT_NAME)
                .with(MIN_READER_VERSION, VERSION)
                .with(VERSION_KEY, VERSION)
                .with(GROUPS, groupsJson),
            pretty = false,
        )
    }

    /** The groups with something in them — what a restore can offer. */
    val nonEmptyGroups: Set<BackupGroup>
        get() = groups.filterValues { it.isNotEmpty() }.keys

    companion object {
        /** The newest settings layout this build writes and understands. */
        const val VERSION = 1

        /**
         * Reads what [encode] wrote, or null when it is not that, or is from a build too new to
         * trust.
         *
         * Never throws. A restore with an unreadable settings entry still has the notes to offer,
         * and offering only those is the right fallback — the same as an archive made before
         * backups carried settings at all.
         */
        fun decode(text: String?): SettingsSnapshot? {
            if (text.isNullOrBlank()) return null
            val root = runCatching { JsonReader.parseObject(text) }.getOrNull() ?: return null
            if (root.string(FORMAT) != FORMAT_NAME) return null
            // A hard gate, like `minReaderVersion` in `.daknote`: a newer build that changed what a
            // key means says so here, and guessing at it could write nonsense into this device.
            if (root.int(MIN_READER_VERSION, Int.MAX_VALUE) > VERSION) return null
            val groupsJson = root.obj(GROUPS) ?: return null
            val groups = LinkedHashMap<BackupGroup, Map<String, PrefValue>>()
            groupsJson.forEach { groupKey, value ->
                val group = BackupGroup.fromKey(groupKey) ?: return@forEach
                val obj = value as? JsonObject ?: return@forEach
                val values = LinkedHashMap<String, PrefValue>()
                obj.forEach { key, tagged -> readValue(tagged)?.let { values[key] = it } }
                groups[group] = values
            }
            return SettingsSnapshot(groups)
        }

        private fun writeValue(value: PrefValue): JsonObject = when (value) {
            is PrefValue.Bool -> JsonObject.EMPTY.with(T_BOOL, value.value)
            is PrefValue.IntV -> JsonObject.EMPTY.with(T_INT, value.value)
            is PrefValue.LongV -> JsonObject.EMPTY.with(T_LONG, value.value)
            is PrefValue.FloatV -> JsonObject.EMPTY.with(T_FLOAT, value.value)
            is PrefValue.Str -> JsonObject.EMPTY.with(T_STRING, value.value)
            is PrefValue.StrSet -> JsonObject.EMPTY.with(T_SET, JsonArray.ofStrings(value.value.sorted()))
        }

        /** One tag per value; anything else — two tags, an unknown one, a wrong type — is skipped. */
        private fun readValue(json: JsonValue): PrefValue? {
            val obj = json as? JsonObject ?: return null
            if (obj.size != 1) return null
            val tag = obj.keys.first()
            val raw = obj[tag]
            return when (tag) {
                T_BOOL -> (raw as? JsonBool)?.let { PrefValue.Bool(it.value) }
                T_INT -> (raw as? JsonNumber)?.literal?.toIntOrNull()?.let(PrefValue::IntV)
                T_LONG -> (raw as? JsonNumber)?.literal?.toLongOrNull()?.let(PrefValue::LongV)
                T_FLOAT -> (raw as? JsonNumber)?.literal?.toFloatOrNull()
                    ?.takeIf { it.isFinite() }?.let(PrefValue::FloatV)
                T_STRING -> (raw as? JsonString)?.let { PrefValue.Str(it.value) }
                T_SET -> (raw as? JsonArray)?.let { array ->
                    PrefValue.StrSet(array.items.mapNotNull { (it as? JsonString)?.value }.toSet())
                }
                else -> null
            }
        }

        private const val FORMAT = "format"
        private const val FORMAT_NAME = "daknote-settings"
        private const val MIN_READER_VERSION = "minReaderVersion"
        private const val VERSION_KEY = "version"
        private const val GROUPS = "groups"

        private const val T_BOOL = "bool"
        private const val T_INT = "int"
        private const val T_LONG = "long"
        private const val T_FLOAT = "float"
        private const val T_STRING = "string"
        private const val T_SET = "stringSet"
    }
}
