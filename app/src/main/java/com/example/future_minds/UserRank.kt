package com.example.future_minds

import android.content.Context
import android.graphics.Color

enum class UserRank(val color: Int, val minTrust: Int, val stringRes: Int) {
    NOVICE(Color.GRAY, 0, R.string.rank_novice),
    GUARDIAN_LEVEL_1(Color.BLUE, 100, R.string.rank_guardian_1),
    GUARDIAN_LEVEL_2(Color.GREEN, 300, R.string.rank_guardian_2),
    ELITE_GUARDIAN(Color.MAGENTA, 600, R.string.rank_elite),
    LEGENDARY_GUARDIAN(Color.parseColor("#FFD700"), 1000, R.string.rank_legendary);

    fun getDisplayName(context: Context): String {
        return context.getString(stringRes)
    }

    companion object {
        fun fromTrustFactor(trustFactor: Int): UserRank {
            return values().filter { trustFactor >= it.minTrust }.maxByOrNull { it.minTrust } ?: NOVICE
        }
    }
}
