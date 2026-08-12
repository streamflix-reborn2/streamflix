package com.streamflixreborn.streamflix.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

data class Video(
    val source: String,
    val subtitles: List<Subtitle> = listOf(),
    val headers: Map<String, String>? = null,
    val type: String? = null,
    val extraBuffering: Boolean = false,
    val useServerSubtitleSetting: Boolean = false,
    val maintainToken: Boolean = false,
    val restrictToPublicNetwork: Boolean = false,
) : Serializable {

    fun canUseExternalPlayer(): Boolean = !restrictToPublicNetwork

    sealed class Type : Parcelable, Serializable {
        @Parcelize
        data class Movie(
            val id: String,
            val title: String,
            val releaseDate: String,
            val poster: String,
            val imdbId: String?,
        ) : Type(), Serializable

        @Parcelize
        data class Episode(
            val id: String,
            val number: Int,
            val title: String?,
            val poster: String?,
            val overview: String?,
            val tvShow: TvShow,
            val season: Season,
        ) : Type(), Serializable {
            @Parcelize
            data class TvShow(
                val id: String,
                val title: String,
                val poster: String?,
                val banner: String?,
                val releaseDate: String?,
                val imdbId: String?,
            ) : Parcelable, Serializable

            @Parcelize
            data class Season(
                val number: Int,
                val title: String?,
            ) : Parcelable, Serializable
        }
    }

    data class Subtitle(
        val label: String,
        val file: String,
        var default: Boolean = false,
        val initialDefault: Boolean = false
    ) : Serializable

    data class Server(
        val id: String,
        val name: String,
        val src: String = "",
    ) : Serializable {
        var video: Video? = null
    }
}
