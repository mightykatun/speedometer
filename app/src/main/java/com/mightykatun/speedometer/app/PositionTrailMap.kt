package com.mightykatun.speedometer.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mightykatun.speedometer.app.domain.model.PositionFix
import kotlin.math.hypot
import kotlin.math.min

@Composable
internal fun PositionTrailMap(
    trail: List<PositionFix>,
    current: PositionFix,
    isStationary: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier
) {
    val heading = current.headingDegrees
    val headingText = heading?.let(::headingLabel)
    val altitudeText = current.altitudeMeters?.let(::altitudeLabel)
    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = "North-up position trail"
                val movementDescription = when {
                    isStationary -> "Stationary"
                    headingText != null -> "Heading $headingText"
                    else -> "Heading unavailable"
                }
                stateDescription = "Altitude ${altitudeText ?: "unavailable"}, $movementDescription"
            }
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                val projection = projectPositionTrail(
                    trail = trail,
                    current = current,
                    width = size.width,
                    height = size.height,
                    padding = 20.dp.toPx()
                )
                val tracePath = projection?.trace?.takeIf { it.size > 1 }?.let { points ->
                    roundedTracePath(points, 7.dp.toPx())
                }
                val horizontalFade = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.12f to Color.White,
                    0.88f to Color.White,
                    1f to Color.Transparent
                )
                val verticalFade = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.16f to Color.White,
                    0.84f to Color.White,
                    1f to Color.Transparent
                )
                val trailStroke = Stroke(
                    width = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
                val arrowStroke = Stroke(
                    width = 1.75.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
                val arrowPath = projection?.current?.let { position ->
                    val length = 9.dp.toPx()
                    val halfWidth = 5.dp.toPx()
                    Path().apply {
                        moveTo(position.x, position.y - length)
                        lineTo(position.x - halfWidth, position.y + length * 0.55f)
                        moveTo(position.x, position.y - length)
                        lineTo(position.x + halfWidth, position.y + length * 0.55f)
                    }
                }

                onDrawBehind {
                    if (tracePath != null) {
                        drawPath(
                            path = tracePath,
                            color = secondaryColor.copy(alpha = 0.72f),
                            style = trailStroke
                        )
                        drawRect(brush = horizontalFade, blendMode = BlendMode.DstIn)
                        drawRect(brush = verticalFade, blendMode = BlendMode.DstIn)
                    }
                    val currentPosition = projection?.current ?: return@onDrawBehind
                    if (isStationary || heading == null || arrowPath == null) {
                        drawCircle(
                            color = primaryColor,
                            radius = 4.25.dp.toPx(),
                            center = currentPosition,
                            style = arrowStroke
                        )
                    } else {
                        rotate(degrees = heading, pivot = currentPosition) {
                            drawPath(
                                path = arrowPath,
                                color = primaryColor,
                                style = arrowStroke
                            )
                        }
                    }
                }
            }
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = altitudeText ?: "-- m",
                color = secondaryColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            if (!isStationary && headingText != null) {
                Text(
                    text = headingText,
                    color = secondaryColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
    }
}

internal data class RoundedTraceCorner(
    val entry: Offset,
    val exit: Offset
)

internal fun roundedTraceCorner(
    previous: Offset,
    corner: Offset,
    next: Offset,
    maximumCut: Float
): RoundedTraceCorner? {
    val incomingX = corner.x - previous.x
    val incomingY = corner.y - previous.y
    val outgoingX = next.x - corner.x
    val outgoingY = next.y - corner.y
    val incomingLength = hypot(incomingX.toDouble(), incomingY.toDouble()).toFloat()
    val outgoingLength = hypot(outgoingX.toDouble(), outgoingY.toDouble()).toFloat()
    if (incomingLength < 0.5f || outgoingLength < 0.5f) return null

    val directionDot = (incomingX * outgoingX + incomingY * outgoingY) /
        (incomingLength * outgoingLength)
    if (directionDot > 0.995f) return null

    val cut = min(maximumCut, min(incomingLength, outgoingLength) * 0.25f)
    if (cut < 0.5f) return null
    return RoundedTraceCorner(
        entry = Offset(
            x = corner.x - incomingX / incomingLength * cut,
            y = corner.y - incomingY / incomingLength * cut
        ),
        exit = Offset(
            x = corner.x + outgoingX / outgoingLength * cut,
            y = corner.y + outgoingY / outgoingLength * cut
        )
    )
}

private fun roundedTracePath(points: List<Offset>, maximumCut: Float): Path = Path().apply {
    moveTo(points.first().x, points.first().y)
    for (index in 1 until points.lastIndex) {
        val corner = roundedTraceCorner(
            previous = points[index - 1],
            corner = points[index],
            next = points[index + 1],
            maximumCut = maximumCut
        )
        if (corner == null) {
            lineTo(points[index].x, points[index].y)
        } else {
            lineTo(corner.entry.x, corner.entry.y)
            quadraticBezierTo(
                points[index].x,
                points[index].y,
                corner.exit.x,
                corner.exit.y
            )
        }
    }
    lineTo(points.last().x, points.last().y)
}
