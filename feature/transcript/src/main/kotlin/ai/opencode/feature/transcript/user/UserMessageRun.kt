package ai.opencode.feature.transcript.user

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Consecutive user messages read as one block: steering messages waiting under Working under one
 * Steering label, and delivered messages in a row. Every surface in a run stacks [spacing] apart;
 * where two bubbles meet, their trailing corners tighten to [tightRadius]; attachments keep their
 * own round shape.
 */
@Immutable
data class UserMessageRun(
    /** The row above is a message of the same run. */
    val joinsAbove: Boolean = false,
    /** The row below is a message of the same run. */
    val joinsBelow: Boolean = false,
    /** The surface it meets above (the message above's last) is a bubble. */
    val bubbleAbove: Boolean = false,
    /** The surface it meets below (the message below's first) is a bubble. */
    val bubbleBelow: Boolean = false,
) {
    /**
     * What a row brings to the planner: whether it waits in the inbox, and whether a line under
     * it (a failed send, a delivery note, a call log caption) closes the run.
     */
    data class Member(
        val pending: Boolean,
        val endsRun: Boolean = false,
        /** Its first surface is its bubble (no attachments above it). */
        val startsWithBubble: Boolean = true,
        /** Its last surface is its bubble (it has text). */
        val endsWithBubble: Boolean = true,
    )

    /** A message's bubble's corners: tight where it meets another bubble of the run. */
    fun bubbleCorners(hasAttachments: Boolean): UserSurfaceCorners =
        UserSurfaceCorners(top = !hasAttachments && joinsAbove && bubbleAbove, bottom = joinsBelow && bubbleBelow)

    companion object {
        val alone = UserMessageRun()
        /** Between two messages of a run, and between one message's surfaces. */
        val spacing: Dp = 4.dp
        /** The trailing corners where two surfaces meet. */
        val tightRadius: Dp = 6.dp

        /**
         * One run per stretch of adjacent user rows of the same kind: Working, the New divider,
         * or any other row ends it, and a pending message never joins a delivered one.
         */
        fun plan(members: List<Member?>): List<UserMessageRun?> = members.indices.map { index ->
            val member = members[index] ?: return@map null
            fun joins(above: Member?, below: Member?): Boolean {
                if (above == null || below == null) return false
                return above.pending == below.pending && !above.endsRun
            }
            val above = if (index > 0) members[index - 1] else null
            val below = if (index + 1 < members.size) members[index + 1] else null
            UserMessageRun(
                joinsAbove = joins(above, member),
                joinsBelow = joins(member, below),
                bubbleAbove = above?.endsWithBubble ?: false,
                bubbleBelow = below?.startsWithBubble ?: false,
            )
        }
    }
}

/** A surface's trailing corners, tightened where it meets another surface of its run. */
@Immutable
data class UserSurfaceCorners(val top: Boolean = false, val bottom: Boolean = false) {
    fun shape(radius: Dp): RoundedCornerShape = RoundedCornerShape(
        topStart = radius,
        topEnd = if (top) UserMessageRun.tightRadius else radius,
        bottomEnd = if (bottom) UserMessageRun.tightRadius else radius,
        bottomStart = radius,
    )

    companion object {
        val round = UserSurfaceCorners()
    }
}
