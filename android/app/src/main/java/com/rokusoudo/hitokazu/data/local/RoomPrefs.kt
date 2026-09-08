package com.rokusoudo.hitokazu.data.local

import android.content.Context

// 直近参加していたルームIDを端末に保存する（Issue #49）。
// アプリのタスクキル・再起動後も「ルームXXXXXXXXに戻る」の復帰導線を出すために使う。
// DataStore等の新規依存を追加せずに実現できるSharedPreferencesを使用する。
class RoomPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(roomId: String) {
        prefs.edit().putString(KEY_ROOM_ID, roomId).apply()
    }

    fun load(): String? = prefs.getString(KEY_ROOM_ID, null)

    fun clear() {
        prefs.edit().remove(KEY_ROOM_ID).apply()
    }

    companion object {
        private const val PREFS_NAME = "hitokazu_room_prefs"
        private const val KEY_ROOM_ID = "last_room_id"
    }
}
