package com.example.future_minds

import android.graphics.Color

enum class UserRank(val displayName: String, val color: Int, val minTrust: Int) {
    NOVICE("Novice", Color.GRAY, 0),
    GUARDIAN_LEVEL_1("Gardian Lvl 1", Color.BLUE, 100),
    GUARDIAN_LEVEL_2("Gardian Lvl 2", Color.GREEN, 300),
    ELITE_GUARDIAN("Gardian Elită", Color.MAGENTA, 600),
    LEGENDARY_GUARDIAN("Gardian Legendar", Color.parseColor("#FFD700"), 1000); // Gold

    companion object {
        fun fromTrustFactor(trustFactor: Int): UserRank {
            return values().filter { trustFactor >= it.minTrust }.maxByOrNull { it.minTrust } ?: NOVICE
        }
    }
}
