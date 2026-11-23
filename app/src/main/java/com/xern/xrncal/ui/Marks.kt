package com.xern.xrncal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xern.xrncal.domain.Lunar
import com.xern.xrncal.domain.Moon
import com.xern.xrncal.domain.MoonMark
import java.time.LocalDate

/**
 * A day's lunar date as the laptop's LunarLabel draws it. Mùng 1 and rằm, the days people keep,
 * are marked the same way in every view: a small chip tinted in the moon's own colour (never
 * today's red) holding a ring for the new moon or a dot for the full moon, so the shape tells the
 * two apart where the colours don't, and "1/10" or "15/10". [compact] leaves the chip's ground
 * out, for a cell too narrow to hold it. Any other day is [plain], in [style], as before.
 */
@Composable
fun LunarMark(day: LocalDate, plain: String, style: TextStyle, modifier: Modifier = Modifier, compact: Boolean = false) {
    val mark = Lunar.moon(Lunar.ofSolar(day))
    if (mark == null) Text(plain, style = style, maxLines = 1, modifier = modifier)
    else MoonChip(mark, style, modifier, compact)
}

@Composable
fun MoonChip(mark: MoonMark, style: TextStyle, modifier: Modifier = Modifier, compact: Boolean = false) {
    val ink = LocalInk.current
    val color = if (mark.moon == Moon.NEW) ink.moonNew else ink.moonFull
    val name = when {
        mark.tet -> "Tết"
        mark.moon == Moon.NEW -> "New moon ${mark.text}"
        else -> "Full moon ${mark.text}"
    }
    val ground = if (compact) Modifier else Modifier.clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 4.dp, vertical = 1.dp)
    Row(
        modifier.then(ground).clearAndSetSemantics { contentDescription = name },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 1.5.dp else 3.dp),
    ) {
        MoonGlyph(mark.moon, color, (style.fontSize.value * if (compact) 0.6f else 0.7f).dp)
        Text(mark.text, style = style.copy(color = color, fontWeight = if (mark.tet) FontWeight.Bold else FontWeight.SemiBold), maxLines = 1)
    }
}

/**
 * A date as the laptop draws today's: white on a filled circle of the today colour, [diameter]
 * across. Any other day is plain, in [color] and [weight], in the same room, so a row of dates
 * lines up whichever is today.
 */
@Composable
fun DayNumber(day: Int, today: Boolean, size: Int, diameter: Dp, color: Color, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Normal) {
    val ink = LocalInk.current
    Box(
        modifier.requiredSize(diameter).then(if (today) Modifier.clip(CircleShape).background(ink.today) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$day",
            style = type(size, if (today) FontWeight.Bold else weight, if (today) Color.White else color, lineHeight = size.sp),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** ○ for the new moon, ● for the full moon. */
@Composable
private fun MoonGlyph(moon: Moon, color: Color, size: Dp) {
    val shape = Modifier.size(size).clip(CircleShape)
    Box(if (moon == Moon.FULL) shape.background(color) else shape.border(if (size > 7.dp) 1.5.dp else 1.dp, color, CircleShape))
}
