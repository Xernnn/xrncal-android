package com.xern.xrncal.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

/**
 * The date the views open on: the page each view's pager settles on, or a tapped day. Another
 * app's time link [jump]s it, and the pager is made again at the new date.
 *
 * Each pager follows the date only from the [turn] it was made in. A link that starts the app
 * arrives in the first frame, and the first pager (made at today) reports its first page just
 * after the link moved the date. Followed, today would win and the link's date would be lost.
 */
@Stable
class Anchor(start: LocalDate) {
    var date by mutableStateOf(start)

    /** Counts the jumps; the views are keyed on it, so each jump makes the pager again. */
    var turn by mutableIntStateOf(0)
        private set

    fun jump(to: LocalDate) {
        date = to
        turn++
    }

    /** A pager made in [turn] settled on a page: [follow] runs only if no jump came since. */
    fun settled(turn: Int, follow: () -> Unit) {
        if (turn == this.turn) follow()
    }
}
