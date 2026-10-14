// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.sequence

/** The block at [path] from this one, by its items' indices; null if there is none. */
fun Block.blockAt(path: List<Int>): Block? = path.fold(this as Block?) { block, index -> block?.items?.getOrNull(index) as? Block }

/** The item at [path] from this block; null if there is none. */
fun Block.itemAt(path: List<Int>): SequenceItem? = if (path.isEmpty()) this else blockAt(path.dropLast(1))?.items?.getOrNull(path.last())

/** This block with the one at [path] changed by [how]; as it was if there is none. */
fun Block.changeAt(path: List<Int>, how: (Block) -> Block): Block = if (path.isEmpty()) {
    how(this)
} else {
    copy(items = items.mapIndexed { index, item -> if (index == path.first() && item is Block) item.changeAt(path.drop(1), how) else item })
}

/** This block with the item at [path] gone. */
fun Block.removeAt(path: List<Int>): Block {
    val gone = path.last()
    return changeAt(path.dropLast(1)) { block -> block.copy(items = block.items.filterIndexed { index, _ -> index != gone }) }
}

/** This block with [item] right after the one at [path], as a copy of it goes. */
fun Block.insertAfter(path: List<Int>, item: SequenceItem): Block = changeAt(path.dropLast(1)) {
    it.copy(items = it.items.take(path.last() + 1) + item + it.items.drop(path.last() + 1))
}

/** This block with the item at [path] taken to [to] among its neighbours. */
fun Block.moveTo(path: List<Int>, to: Int): Block = changeAt(path.dropLast(1)) { it.copy(items = it.items.moved(path.last(), to)) }

/**
 * This block with the item at [path] at the end of the block at [into], as "Move to…" takes it:
 * added there first, then taken from where it was, which an item added at the end of a block
 * around it or before it does not move.
 */
fun Block.moveInto(path: List<Int>, into: List<Int>): Block {
    val item = itemAt(path) ?: return this
    return changeAt(into) { it.copy(items = it.items + item) }.removeAt(path)
}

/** This list with the item at [from] taken to [to]. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices) return this
    val list = toMutableList()
    val item = list.removeAt(from)
    list.add(to.coerceIn(0, list.size), item)
    return list
}
