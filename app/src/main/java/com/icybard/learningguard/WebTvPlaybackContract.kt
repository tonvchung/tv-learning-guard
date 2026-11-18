package com.icybard.learningguard

/** Must stay wire-compatible with WebTvLive's GuardPlaybackContract. */
object WebTvPlaybackContract {
    const val ACTION = "com.tonvchung.webtv.GUARD_PLAYBACK"
    const val MSG_GET_SESSION_TOKEN = 1
    const val MSG_SESSION_TOKEN = 2
    const val EXTRA_SESSION_TOKEN = "sessionToken"
}
