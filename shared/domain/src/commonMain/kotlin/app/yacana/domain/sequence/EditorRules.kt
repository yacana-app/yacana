// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

/** What a catalogue entry adds: an instruction, a block, a trigger or a condition. */
enum class EntryKind { Instruction, Block, Trigger, Condition }

/**
 * The advanced editor's catalogue, as N.I.N.A.'s, each entry with what it adds and what
 * it can't go without: a target around it, a camera that cools, or a filter that blocks the
 * light for darks in the middle of the night.
 */
enum class Entry(
    val kind: EntryKind,
    val needsTarget: Boolean = false,
    val needsCooler: Boolean = false,
    val needsDarkFilter: Boolean = false,
) {
    TakeExposures(EntryKind.Instruction),
    Cool(EntryKind.Instruction, needsCooler = true),
    Warm(EntryKind.Instruction, needsCooler = true),
    UnparkMount(EntryKind.Instruction),
    ParkMount(EntryKind.Instruction),
    Centre(EntryKind.Instruction, needsTarget = true),
    GoTo(EntryKind.Instruction, needsTarget = true),
    StopMount(EntryKind.Instruction),
    Autofocus(EntryKind.Instruction),
    Focuser(EntryKind.Instruction),
    Filter(EntryKind.Instruction),
    Switch(EntryKind.Instruction),
    WaitTime(EntryKind.Instruction),
    WaitSpan(EntryKind.Instruction),
    WaitAltitude(EntryKind.Instruction, needsTarget = true),
    Annotation(EntryKind.Instruction),
    Target(EntryKind.Block),
    Sequential(EntryKind.Block),
    Parallel(EntryKind.Block),
    AutofocusTemperature(EntryKind.Trigger),
    AutofocusTime(EntryKind.Trigger),
    AutofocusFilter(EntryKind.Trigger),
    AutofocusHfr(EntryKind.Trigger),
    Recentre(EntryKind.Trigger, needsTarget = true),
    DarksTemperature(EntryKind.Trigger, needsDarkFilter = true),
    Repeat(EntryKind.Condition),
    RepeatUntil(EntryKind.Condition),
    RepeatWhileHigh(EntryKind.Condition, needsTarget = true),
}

/** Where the editor adds: the whole night, one of its three areas, or a block. */
enum class Place { Night, Start, Targets, End, Block }

/** Why something can't go where the editor is, which the screen says. */
enum class Blocked { EnterAnArea, TriggersForTheNight, NoConditions, OnlyInATarget, AlreadyInATarget, NoCooler, NoDarkFilter }

/**
 * What each place takes: the night only triggers, for all of it; the start and the end
 * instructions and blocks; the targets' area those and conditions, which repeat the list; a block
 * everything.
 */
fun Place.takes(kind: EntryKind): Boolean = when (this) {
    Place.Night -> kind == EntryKind.Trigger
    Place.Start, Place.End -> kind == EntryKind.Instruction || kind == EntryKind.Block
    Place.Targets -> kind != EntryKind.Trigger
    Place.Block -> true
}

/**
 * Why this entry can't go in [place], [inTarget] or not, with a camera that cools or not ([cooler]
 * null when not known) and a dark filter or not ([darkFilter], the same); null when it can. What
 * points at a target goes only inside one, and a target never inside another.
 */
fun Entry.blocked(place: Place, inTarget: Boolean, cooler: Boolean?, darkFilter: Boolean? = null): Blocked? = when {
    !place.takes(kind) -> when {
        place == Place.Night -> Blocked.EnterAnArea
        kind == EntryKind.Trigger -> Blocked.TriggersForTheNight
        else -> Blocked.NoConditions
    }

    needsTarget && !inTarget -> Blocked.OnlyInATarget

    this == Entry.Target && inTarget -> Blocked.AlreadyInATarget

    needsCooler && cooler == false -> Blocked.NoCooler

    needsDarkFilter && darkFilter == false -> Blocked.NoDarkFilter

    else -> null
}

/** Why this item can't move into [place], [inTarget] or not, as "Move to…" says; null when it can. */
fun SequenceItem.blockedIn(place: Place, inTarget: Boolean): Blocked? = when {
    place == Place.Night -> Blocked.EnterAnArea
    !inTarget && needsTarget() -> Blocked.OnlyInATarget
    inTarget && holdsTarget() -> Blocked.AlreadyInATarget
    else -> null
}

/** Whether this points at a target it does not have: going to it, waiting for it, or a block of those without a target. */
fun SequenceItem.needsTarget(): Boolean = when (this) {
    is GoToTarget, is WaitUntilAbove -> true

    is Block ->
        target == null &&
            (
                items.any { it.needsTarget() } || triggers.any { it is Trigger.RecentreAfterDrift } ||
                    conditions.any { it is Condition.WhileAbove }
                )

    else -> false
}

/** Whether this is a target, or holds one. */
fun SequenceItem.holdsTarget(): Boolean = this is Block && (target != null || items.any { it.holdsTarget() })
