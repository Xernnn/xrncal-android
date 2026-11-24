package com.xern.xrncal

import com.xern.xrncal.ui.Anchor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * A clock widget's "open the calendar at this time" link that starts the app: 0.3.2 opened the Day
 * view on today, not the link's date. The link moved the date in the first frame, then the first
 * pager, made at today, reported its first page and moved it back.
 */
class AnchorTest {
    private val today = LocalDate.of(2026, 11, 25)
    private val linked = LocalDate.of(2026, 12, 10)

    @Test
    fun theFirstPagersFirstPageDoesNotUndoALinkThatStartedTheApp() {
        val anchor = Anchor(today)
        val firstPager = anchor.turn
        anchor.jump(linked)
        anchor.settled(firstPager) { anchor.date = today }
        assertEquals(linked, anchor.date)
    }

    @Test
    fun thePagerMadeAfterTheJumpFollowsAsBefore() {
        val anchor = Anchor(today)
        anchor.jump(linked)
        val pager = anchor.turn
        anchor.settled(pager) { anchor.date = linked }
        anchor.settled(pager) { anchor.date = linked.plusDays(1) }
        assertEquals(linked.plusDays(1), anchor.date)
    }

    @Test
    fun withoutAJumpThePagerFollowsEveryPage() {
        val anchor = Anchor(today)
        val pager = anchor.turn
        anchor.settled(pager) { anchor.date = today.plusDays(1) }
        anchor.settled(pager) { anchor.date = today.plusDays(2) }
        assertEquals(today.plusDays(2), anchor.date)
    }
}
