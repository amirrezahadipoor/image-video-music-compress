package com.compressly.core.engine.model

/** The three first-class media families of the app. */
enum class MediaType {
    PHOTO,
    VIDEO,
    AUDIO;

    companion object {
        fun fromName(name: String): MediaType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: PHOTO
    }
}
