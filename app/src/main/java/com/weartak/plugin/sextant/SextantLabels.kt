package com.weartak.plugin.sextant

import java.util.Locale

/** Preselectable room names on the name-a-room screen, alphabetical. */
internal val ROOM_PRESETS = listOf(
    "Balcony", "Bathroom", "Bedroom", "Dining Room", "Elevator", "Exterior Door", "Interior Door", "Kitchen",
    "Living Room", "Office", "Parking", "Stairs", "Yard",
).sortedWith(String.CASE_INSENSITIVE_ORDER)

/** Room list order: name (case-insensitive), then floor. */
internal val ROOM_ORDER = compareBy<SextantPoint, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.floor }

/** Two-digit floor without an "L" prefix: 8 -> "08", 11 -> "11", -1 -> "-01". */
internal fun floorLabel(floor: Int): String =
    if (floor < 0) String.format(Locale.US, "-%02d", -floor) else String.format(Locale.US, "%02d", floor)

/**
 * Keyboards (e.g. Samsung voice input) may swap a spoken word for an emoji ("Doorway" -> 🚪).
 * Replace each emoji with its Unicode name ("Door") so names stay readable text.
 */
internal fun cleanName(raw: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val cp = raw.codePointAt(i)
        i += Character.charCount(cp)
        when {
            cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp == 0x20E3 -> Unit
            isEmoji(cp) -> {
                val word = Character.getName(cp)?.lowercase(Locale.US)
                    ?.split(' ', '-')?.filter { it.isNotEmpty() }
                    ?.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }
                if (!word.isNullOrEmpty()) sb.append(' ').append(word).append(' ')
            }
            else -> sb.appendCodePoint(cp)
        }
    }
    return sb.toString().replace(Regex("\\s+"), " ").trim()
}

private fun isEmoji(cp: Int) = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
    cp in 0x2190..0x21FF || cp in 0x2300..0x23FF

/** Same-named rooms (e.g. several Stairs) are told apart by their floor: "Stairs 06", "Stairs 07". */
internal fun roomLabel(a: SextantAddress, p: SextantPoint): String =
    if (a.points.count { it.name.equals(p.name, ignoreCase = true) } > 1) "${p.name} ${floorLabel(p.floor)}" else p.name
