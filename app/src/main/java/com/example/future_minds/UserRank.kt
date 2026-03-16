package com.example.future_minds

import android.graphics.Color

enum class UserRank(val displayName: String, val minTrustFactor: Int, val color: Int) {
    SCOLEREL("Școlărel", 0, Color.GRAY),
    SCOLARAS("Școlăraș", 100, Color.GREEN),
    SCOLAR("Școlar", 250, Color.BLUE),
    SCOLAR_AVANSAT("Școlar Avansat", 500, Color.MAGENTA),
    SCOLAR_PATRON("Școlar Patron", 1000, Color.YELLOW);

    companion object {
        fun fromTrustFactor(trustFactor: Int): UserRank {
            return values().findLast { trustFactor >= it.minTrustFactor } ?: SCOLEREL
        }
    }
}