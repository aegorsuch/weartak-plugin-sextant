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

/** Same-named rooms (e.g. several Stairs) are told apart by their floor: "Stairs 06", "Stairs 07". */
internal fun roomLabel(a: SextantAddress, p: SextantPoint): String =
    if (a.points.count { it.name.equals(p.name, ignoreCase = true) } > 1) "${p.name} ${floorLabel(p.floor)}" else p.name
