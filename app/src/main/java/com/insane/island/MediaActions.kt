package com.insane.island

import android.content.ComponentName
import android.content.Context
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

// Действия над активным плеером для голосовых команд
object MediaActions {
    private val dislikeRe = Regex("dislike|дизлайк|не нрав|ban|hide|скрыть|thumb.?down", RegexOption.IGNORE_CASE)
    private val likeRe = Regex("like|love|heart|favou?rite|нрав|избран|добав|сохран|save|thumb.?up|\\badd\\b|remove|удал|убрат", RegexOption.IGNORE_CASE)

    fun controller(ctx: Context): MediaController? = try {
        val m = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val l = m.getActiveSessions(ComponentName(ctx, MediaListener::class.java))
        l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: l.firstOrNull()
    } catch (e: SecurityException) { null }

    private fun notifActions(c: MediaController) =
        MediaListener.acts.filterKeys { it.startsWith(c.packageName + "/") }.values.flatten()

    fun like(c: MediaController) {
        val ca = c.playbackState?.customActions?.firstOrNull {
            val t = "${it.action} ${it.name}"; likeRe.containsMatchIn(t) && !dislikeRe.containsMatchIn(t)
        }
        if (ca != null) { c.transportControls.sendCustomAction(ca, ca.extras); return }
        val na = notifActions(c).firstOrNull {
            val t = it.title?.toString() ?: ""; likeRe.containsMatchIn(t) && !dislikeRe.containsMatchIn(t)
        }
        if (na != null) { try { na.actionIntent?.send() } catch (_: Exception) {}; return }
        try { c.transportControls.setRating(Rating.newHeartRating(true)) } catch (_: Exception) {}
    }

    fun dislike(c: MediaController) {
        val da = c.playbackState?.customActions?.firstOrNull { dislikeRe.containsMatchIn("${it.action} ${it.name}") }
        if (da != null) { c.transportControls.sendCustomAction(da, da.extras); return }
        val na = notifActions(c).firstOrNull { dislikeRe.containsMatchIn(it.title?.toString() ?: "") }
        if (na != null) { try { na.actionIntent?.send() } catch (_: Exception) {}; return }
        c.transportControls.skipToNext()
    }
}
