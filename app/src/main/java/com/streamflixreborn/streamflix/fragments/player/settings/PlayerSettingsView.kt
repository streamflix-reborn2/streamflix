package com.streamflixreborn.streamflix.fragments.player.settings

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.SubtitleView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.OpenSubtitles
import com.streamflixreborn.streamflix.utils.mediaServers
import com.streamflixreborn.streamflix.utils.SubDL
import com.streamflixreborn.streamflix.utils.SubtitleOffset
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.findClosest
import com.streamflixreborn.streamflix.utils.getAlpha
import com.streamflixreborn.streamflix.utils.getRgb
import com.streamflixreborn.streamflix.utils.mediaServerId
import com.streamflixreborn.streamflix.utils.mediaServers
import com.streamflixreborn.streamflix.utils.setAlpha
import com.streamflixreborn.streamflix.utils.setRgb
import com.streamflixreborn.streamflix.utils.supportedTrackFormats
import kotlin.math.roundToInt

abstract class PlayerSettingsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    var player: ExoPlayer? = null
        set(value) {
            if (field === value) return

            value?.let {
                Settings.Server.init(it)
                Settings.Quality.init(it, resources)
                Settings.Audio.init(it, resources)
                Settings.Subtitle.init(it, resources)
                Settings.Speed.refresh(it)
            }

            value?.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.contains(Player.EVENT_PLAYLIST_METADATA_CHANGED)) {
                        Settings.Server.init(value)
                    }
                    if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                        Settings.Server.refresh(value)
                    }
                    if (events.contains(Player.EVENT_TRACKS_CHANGED)) {
                        Settings.Quality.init(value, resources)
                        Settings.Audio.init(value, resources)
                        Settings.Subtitle.init(value, resources)
                    }
                    if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) {
                        Settings.Speed.refresh(value)
                    }
                }
            })

            field = value
        }
    var subtitleView: SubtitleView? = null
    var openSubtitles: List<OpenSubtitles.Subtitle> = listOf()
        set(value) {
            Settings.Subtitle.OpenSubtitles.init(value)
            field = value
        }
    var subDLSubtitles: List<SubDL.Subtitle> = listOf()
        set(value) {
            Settings.Subtitle.SubDLSubtitles.init(value)
            field = value
        }

    protected var currentSettings = Setting.MAIN

    protected enum class Setting {
        MAIN,
        QUALITY,
        AUDIO,
        SUBTITLES,
        SUBTITLE_OFFSET,
        CAPTION_STYLE,
        CAPTION_STYLE_FONT_COLOR,
        CAPTION_STYLE_TEXT_SIZE,
        CAPTION_STYLE_FONT_OPACITY,
        CAPTION_STYLE_EDGE_STYLE,
        CAPTION_STYLE_BACKGROUND_COLOR,
        CAPTION_STYLE_BACKGROUND_OPACITY,
        CAPTION_STYLE_WINDOW_COLOR,
        CAPTION_STYLE_WINDOW_OPACITY,
        CAPTION_STYLE_MARGIN,
        OPEN_SUBTITLES,
        SUBDL,
        SPEED,
        EXTRA_BUFFERING,
        SOFTWARE_DECODER,
        SERVERS,
        GESTURES,
        KEEP_SCREEN_ON,
        MANUAL_ZOOM,
    }

    protected var onQualitySelected: ((Settings.Quality) -> Unit) =
        fun(quality) {
            val player = player ?: return

            when (quality) {
                is Settings.Quality.Auto -> {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .setMaxVideoBitrate(Int.MAX_VALUE)
                        .setForceHighestSupportedBitrate(false)
                        .build()
                    UserPreferences.qualityHeight = null
                }

                is Settings.Quality.VideoTrackInformation -> {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .setOverrideForType(
                            TrackSelectionOverride(
                                quality.trackGroup.mediaTrackGroup,
                                listOf(quality.trackIndex)
                            )
                        )
                        .setForceHighestSupportedBitrate(false)
                        .build()
                    UserPreferences.qualityHeight = quality.height
                }
            }

            qualitySelectionListener?.invoke(quality)
        }

    protected var qualitySelectionListener: ((Settings.Quality) -> Unit)? = null
    fun setOnQualitySelectedListener(listener: (Settings.Quality) -> Unit) {
        qualitySelectionListener = listener
    }

    protected var onAudioSelected: ((Settings.Audio) -> Unit) =
        fun(audio) {
            val player = player ?: return
            if (audio !is Settings.Audio.AudioTrackInformation) return

            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(
                    TrackSelectionOverride(
                        audio.trackGroup.mediaTrackGroup,
                        listOf(audio.trackIndex)
                    )
                )
                .setTrackTypeDisabled(audio.trackGroup.type, false)
                .build()
        }

    protected var onSubtitleSelected: ((Settings.Subtitle) -> Unit) =
        fun(subtitle) {
            val player = player ?: return

            when (subtitle) {
                is Settings.Subtitle.None -> {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED.inv())
                        .build()
                    UserPreferences.subtitleName = null
                }

                is Settings.Subtitle.TextTrackInformation -> {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(
                            TrackSelectionOverride(
                                subtitle.trackGroup.mediaTrackGroup,
                                listOf(subtitle.trackIndex)
                            )
                        )
                        .setTrackTypeDisabled(subtitle.trackGroup.type, false)
                        .build()
                    UserPreferences.subtitleName = (subtitle.language ?: subtitle.label).substringBefore(" ")
                }

                else -> {}
            }
        }

    protected var onSubtitleOffsetSelected: ((Settings.Subtitle.Offset.Value) -> Unit) =
        fun(offset) {
            val player = player ?: return
            SubtitleOffset.offsetMs = offset.milliseconds
            // Reset the text renderer so cues discarded under the previous offset are available.
            player.seekTo(player.currentPosition)
        }

    protected var onCaptionStyleChanged: ((CaptionStyleCompat) -> Unit) =
        fun(captionStyle) {
            val subtitleView = subtitleView ?: return

            UserPreferences.captionStyle = captionStyle
            subtitleView.setStyle(UserPreferences.captionStyle)
            subtitleView.setPadding(0, 0, 0, UserPreferences.captionMargin.dp(subtitleView.context))
        }

    protected var onMarginSelected: ((Settings.Subtitle.Style.Margin) -> Unit) =
        fun(margin) {
            val subtitleView = subtitleView ?: return

            UserPreferences.captionMargin = margin.value
            subtitleView.setPadding(0, 0, 0, UserPreferences.captionMargin.dp(subtitleView.context))
        }

    protected var onFontColorSelected: ((Settings.Subtitle.Style.FontColor) -> Unit) =
        fun(fontColor) {
            onCaptionStyleChanged.invoke(
                CaptionStyleCompat(
                    UserPreferences.captionStyle.foregroundColor.setRgb(fontColor.color),
                    UserPreferences.captionStyle.backgroundColor,
                    UserPreferences.captionStyle.windowColor,
                    UserPreferences.captionStyle.edgeType,
                    UserPreferences.captionStyle.edgeColor,
                    null
                )
            )
        }

    protected var onTextSizeSelected: ((Settings.Subtitle.Style.TextSize) -> Unit) =
        fun(textSize) {
            val subtitleView = subtitleView ?: return

            UserPreferences.captionTextSize = textSize.value
            subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * UserPreferences.captionTextSize)
        }

    protected var onFontOpacitySelected: ((Settings.Subtitle.Style.FontOpacity) -> Unit) =
        fun(fontOpacity) {
            onCaptionStyleChanged.invoke(
                CaptionStyleCompat(
                    UserPreferences.captionStyle.foregroundColor.setAlpha(fontOpacity.alpha),
                    UserPreferences.captionStyle.backgroundColor,
                    UserPreferences.captionStyle.windowColor,
                    UserPreferences.captionStyle.edgeType,
                    UserPreferences.captionStyle.edgeColor,
                    null
                )
            )
        }

    protected var onEdgeStyleSelected: ((Settings.Subtitle.Style.EdgeStyle) -> Unit) =
        fun(edgeStyle) {
            onCaptionStyleChanged.invoke(
                CaptionStyleCompat(
                    UserPreferences.captionStyle.foregroundColor,
                    UserPreferences.captionStyle.backgroundColor,
                    UserPreferences.captionStyle.windowColor,
                    edgeStyle.type,
                    UserPreferences.captionStyle.edgeColor,
                    null
                )
            )
        }

    protected var onBackgroundColorSelected: ((Settings.Subtitle.Style.BackgroundColor) -> Unit) =
        fun(backgroundColor) {
            onCaptionStyleChanged.invoke(
                CaptionStyleCompat(
                    UserPreferences.captionStyle.foregroundColor,
                    UserPreferences.captionStyle.backgroundColor.setRgb(backgroundColor.color),
                    UserPreferences.captionStyle.windowColor,
                    UserPreferences.captionStyle.edgeType,
                    UserPreferences.captionStyle.edgeColor,
                    null
                )
            )
        }

    protected var onBackgroundOpacitySelected: ((Settings.Subtitle.Style.BackgroundOpacity) -> Unit) =
        fun(backgroundOpacity) {
            onCaptionStyleChanged.invoke(
                CaptionStyleCompat(
                    UserPreferences.captionStyle.foregroundColor,
                    UserPreferences.captionStyle.backgroundColor.setAlpha(backgroundOpacity.alpha),
                    UserPreferences.captionStyle.windowColor,
                    UserPreferences.captionStyle.edgeType,
                    UserPreferences.captionStyle.edgeColor,
                    null
                )
            )
        }

    protected var onWindowColorSelected: ((Settings.Subtitle.Style.WindowColor) -> Unit) =
        fun(windowColor) {
            onCaptionStyleChanged.invoke(
                CaptionStyleCompat(
                    UserPreferences.captionStyle.foregroundColor,
                    UserPreferences.captionStyle.backgroundColor,
                    UserPreferences.captionStyle.windowColor.setRgb(windowColor.color),
                    UserPreferences.captionStyle.edgeType,
                    UserPreferences.captionStyle.edgeColor,
                    null
                )
            )
        }

    protected var onWindowOpacitySelected: ((Settings.Subtitle.Style.WindowOpacity) -> Unit) =
        fun(windowOpacity) {
            onCaptionStyleChanged.invoke(
                CaptionStyleCompat(
                    UserPreferences.captionStyle.foregroundColor,
                    UserPreferences.captionStyle.backgroundColor,
                    UserPreferences.captionStyle.windowColor.setAlpha(windowOpacity.alpha),
                    UserPreferences.captionStyle.edgeType,
                    UserPreferences.captionStyle.edgeColor,
                    null
                )
            )
        }

    protected var onLocalSubtitlesClicked: (() -> Unit)? = null
    fun setOnLocalSubtitlesClickedListener(onLocalSubtitlesClicked: () -> Unit) {
        this.onLocalSubtitlesClicked = onLocalSubtitlesClicked
    }

    abstract var onSubtitlesClicked: (() -> Unit)?

    protected var onOpenSubtitleSelected: ((Settings.Subtitle.OpenSubtitles.Subtitle) -> Unit)? = null
    fun setOnOpenSubtitleSelectedListener(onOpenSubtitleSelected: (Settings.Subtitle.OpenSubtitles.Subtitle) -> Unit) {
        this.onOpenSubtitleSelected = onOpenSubtitleSelected
    }

    protected var onSubDLSubtitleSelected: ((Settings.Subtitle.SubDLSubtitles.Subtitle) -> Unit)? = null
    fun setOnSubDLSubtitleSelectedListener(onSubDLSubtitleSelected: (Settings.Subtitle.SubDLSubtitles.Subtitle) -> Unit) {
        this.onSubDLSubtitleSelected = onSubDLSubtitleSelected
    }

    protected var onSpeedSelected: ((Settings.Speed) -> Unit) =
        fun(speed) {
            val player = player ?: return

            player.playbackParameters = player.playbackParameters
                .withSpeed(speed.value)
        }

    protected var onExtraBufferingListener: ((Boolean) -> Unit)? = null
    fun setOnExtraBufferingSelectedListener(listener: (Boolean) -> Unit) {
        this.onExtraBufferingListener = listener
    }

    protected var onSoftwareDecoderListener: ((Boolean) -> Unit)? = null
    fun setOnSoftwareDecoderSelectedListener(listener: (Boolean) -> Unit) {
        this.onSoftwareDecoderListener = listener
    }

    protected var onExtraBufferingSelected: ((Settings.ExtraBuffering) -> Unit) =
        fun(extraBuffering) {
            val newValue = when (extraBuffering) {
                is Settings.ExtraBuffering.On -> true
                is Settings.ExtraBuffering.Off -> false
            }
            Settings.ExtraBuffering.selectedValue = if (newValue == Settings.ExtraBuffering.isDefaultEnabled) null else newValue
            onExtraBufferingListener?.invoke(newValue)
        }

    protected var onSoftwareDecoderSelected: ((Settings.SoftwareDecoder) -> Unit) =
        fun(softwareDecoder) {
            val newValue = when (softwareDecoder) {
                is Settings.SoftwareDecoder.On -> true
                is Settings.SoftwareDecoder.Off -> false
            }
            Settings.SoftwareDecoder.selectedValue = if (newValue == Settings.SoftwareDecoder.isDefaultEnabled) null else newValue
            onSoftwareDecoderListener?.invoke(newValue)
        }

    protected var onServerSelected: ((Settings.Server) -> Unit)? = null
    fun setOnServerSelectedListener(onServerSelected: (server: Settings.Server) -> Unit) {
        this.onServerSelected = onServerSelected
    }


    interface Item

    sealed class Settings : Item {

        companion object {
            val listMobile = listOf(
                Quality,
                Audio,
                Subtitle,
                Speed,
                Server,
                ExtraBuffering,
                SoftwareDecoder,
                Gestures,
                KeepScreenOn,
                ManualZoom,
            )
            val listTv = listOf(
                Quality,
                Audio,
                Subtitle,
                Speed,
                Server,
                ExtraBuffering,
                SoftwareDecoder,
                ManualZoom,
            )
        }

        data object ManualZoom : Settings()

        sealed class Gestures : Item {
            companion object : Settings() {
                val list = listOf(On, Off)
                val selected: Gestures get() = if (UserPreferences.playerGestures) On else Off
            }
            abstract val isSelected: Boolean
            abstract val stringId: Int

            data object On : Gestures() {
                override val isSelected: Boolean get() = UserPreferences.playerGestures
                override val stringId: Int get() = R.string.settings_player_gestures_on
            }
            data object Off : Gestures() {
                override val isSelected: Boolean get() = !UserPreferences.playerGestures
                override val stringId: Int get() = R.string.settings_player_gestures_off
            }
        }

        sealed class KeepScreenOn : Item {
            companion object : Settings() {
                val list = listOf(On, Off)
                val selected: KeepScreenOn get() = if (UserPreferences.keepScreenOnWhenPaused) On else Off
            }
            abstract val isSelected: Boolean
            abstract val stringId: Int

            data object On : KeepScreenOn() {
                override val isSelected: Boolean get() = UserPreferences.keepScreenOnWhenPaused
                override val stringId: Int get() = R.string.settings_autoupdate_on
            }
            data object Off : KeepScreenOn() {
                override val isSelected: Boolean get() = !UserPreferences.keepScreenOnWhenPaused
                override val stringId: Int get() = R.string.settings_autoupdate_off
            }
        }

        sealed class ExtraBuffering : Item {
            companion object : Settings() {
                var isDefaultEnabled = false
                var selectedValue: Boolean? = null

                val isEnabled: Boolean get() = selectedValue ?: (isDefaultEnabled || UserPreferences.forceExtraBuffering)

                val list = listOf(On, Off)

                val selected: ExtraBuffering
                    get() = if (isEnabled) On else Off

                fun init(defaultEnabled: Boolean) {
                    isDefaultEnabled = defaultEnabled
                    selectedValue = null
                }
            }

            abstract val isSelected: Boolean
            abstract val stringId: Int

            data object On : ExtraBuffering() {
                override val isSelected: Boolean get() = isEnabled
                override val stringId: Int
                    get() = when {
                        selectedValue == null && isDefaultEnabled -> R.string.player_settings_extra_buffer_auto_on
                        selectedValue == true && !isDefaultEnabled -> R.string.player_settings_extra_buffer_forced_on
                        else -> R.string.player_settings_extra_buffer_on
                    }
            }

            data object Off : ExtraBuffering() {
                override val isSelected: Boolean get() = !isEnabled
                override val stringId: Int
                    get() = when {
                        selectedValue == null && !isDefaultEnabled -> R.string.player_settings_extra_buffer_auto_off
                        selectedValue == false && isDefaultEnabled -> R.string.player_settings_extra_buffer_forced_off
                        else -> R.string.player_settings_extra_buffer_off
                    }
            }
        }

        sealed class SoftwareDecoder : Item {
            companion object : Settings() {
                var isDefaultEnabled = false
                var selectedValue: Boolean? = null

                val isEnabled: Boolean get() = selectedValue ?: (isDefaultEnabled)

                val list = listOf(On, Off)

                val selected: SoftwareDecoder
                    get() = if (isEnabled) On else Off

                fun init(defaultEnabled: Boolean) {
                    isDefaultEnabled = defaultEnabled
                    selectedValue = null
                }
            }

            abstract val isSelected: Boolean
            abstract val stringId: Int

            data object On : SoftwareDecoder() {
                override val isSelected: Boolean get() = isEnabled
                override val stringId: Int
                    get() = when {
                        selectedValue == null && isDefaultEnabled -> R.string.player_settings_software_decoder_auto_on
                        selectedValue == true && !isDefaultEnabled -> R.string.player_settings_software_decoder_forced_on
                        else -> R.string.player_settings_software_decoder_on
                    }
            }

            data object Off : SoftwareDecoder() {
                override val isSelected: Boolean get() = !isEnabled
                override val stringId: Int
                    get() = when {
                        selectedValue == null && !isDefaultEnabled -> R.string.player_settings_software_decoder_auto_off
                        selectedValue == false && isDefaultEnabled -> R.string.player_settings_software_decoder_forced_off
                        else -> R.string.player_settings_software_decoder_off
                    }
            }
        }

        sealed class Quality : Item {

            companion object : Settings() {
                val list = mutableListOf<Quality>()

                val selected: Quality
                    get() = list.find { it.isSelected } ?: Auto

                fun init(player: ExoPlayer, resources: Resources) {
                    list.clear()
                    list.add(Auto)
                    list.addAll(
                        player.currentTracks.groups
                            .filter { it.type == C.TRACK_TYPE_VIDEO }
                            .flatMap { trackGroup ->
                                (0 until trackGroup.length)
                                    .mapNotNull { trackIndex ->
                                        val trackFormat = trackGroup.getTrackFormat(trackIndex)
                                        if (trackFormat.selectionFlags and C.SELECTION_FLAG_FORCED != 0) {
                                            return@mapNotNull null
                                        }

                                        VideoTrackInformation(
                                            name = DefaultTrackNameProvider(resources)
                                                .getTrackName(trackFormat),
                                            width = trackFormat.width,
                                            height = trackFormat.height,
                                            bitrate = trackFormat.bitrate,
                                            trackGroup = trackGroup,
                                            trackIndex = trackIndex,
                                            player = player,
                                        )
                                    }
                                    .distinctBy { it.width to it.height }
                            }
                            .sortedByDescending { it.height }
                    )

                    list.filterIsInstance<VideoTrackInformation>()
                        .find { it.height == UserPreferences.qualityHeight }
                        ?.let {
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                .setOverrideForType(
                                    TrackSelectionOverride(
                                        it.trackGroup.mediaTrackGroup,
                                        listOf(it.trackIndex)
                                    )
                                )
                                .setForceHighestSupportedBitrate(false)
                                .build()
                        }
                }
            }

            abstract val isSelected: Boolean

            data object Auto : Quality() {
                val currentTrack: VideoTrackInformation?
                    get() = list
                        .filterIsInstance<VideoTrackInformation>()
                        .find { it.isCurrentlyPlayed }
                override val isSelected: Boolean
                    get() = list
                        .filterIsInstance<VideoTrackInformation>()
                        .none { it.isSelected }
            }

            class VideoTrackInformation(
                val name: String,
                val width: Int,
                val height: Int,
                val bitrate: Int,
                val trackGroup: Tracks.Group,
                val trackIndex: Int,
                val player: ExoPlayer,
            ) : Quality() {
                val isCurrentlyPlayed: Boolean
                    get() {
                        val currentFormat = player.videoFormat ?: return false
                        val bitrateMatch = currentFormat.bitrate == bitrate
                        val resolutionMatch = currentFormat.height == height && currentFormat.width == width

                        return bitrateMatch || resolutionMatch
                    }
                override val isSelected: Boolean
                    get() = player.trackSelectionParameters.overrides.values.any { override ->
                        override.mediaTrackGroup == trackGroup.mediaTrackGroup &&
                            override.trackIndices.contains(trackIndex)
                    }
            }
        }

        sealed class Audio : Item {

            companion object : Settings() {
                val list = mutableListOf<AudioTrackInformation>()

                val selected: AudioTrackInformation?
                    get() = list.find { it.isSelected }

                fun init(player: ExoPlayer, resources: Resources) {
                    val currentServerId = player.currentMediaItem?.mediaMetadata?.extras?.getString("mediaServerId")
                    val servers = player.playlistMetadata.mediaServers
                    val currentServer = servers.find { it.id == currentServerId }
                    val serverTag = currentServer?.name?.let { name ->
                        Regex("\\[(.*?)]").find(name)?.groupValues?.get(1)
                            ?: Regex("\\((.*?)\\)").find(name)?.groupValues?.get(1)
                    }

                    list.clear()
                    list.addAll(
                        player.currentTracks.groups
                            .filter { it.type == C.TRACK_TYPE_AUDIO }
                            .flatMap { trackGroup ->
                                trackGroup.supportedTrackFormats
                                    .filter { it.format.selectionFlags and C.SELECTION_FLAG_FORCED == 0 }
                                    .map { (trackIndex, trackFormat) ->
                                        val trackName = DefaultTrackNameProvider(resources)
                                            .getTrackName(trackFormat)
                                        
                                        val finalName = when {
                                            trackName.isBlank() || trackName.lowercase() == "und" || trackName.lowercase() == "unknown" -> {
                                                if (serverTag != null) "Audio $serverTag" else "Track ${trackIndex + 1}"
                                            }
                                            else -> trackName
                                        }

                                        AudioTrackInformation(
                                            name = finalName,

                                            trackGroup = trackGroup,
                                            trackIndex = trackIndex,
                                        )
                                    }
                            }
                            .sortedBy { it.name }
                    )
                }
            }

            abstract val isSelected: Boolean

            class AudioTrackInformation(
                val name: String,

                val trackGroup: Tracks.Group,
                val trackIndex: Int,
            ) : Audio() {
                override val isSelected: Boolean
                    get() = trackGroup.isTrackSelected(trackIndex)
            }
        }

        sealed class Subtitle : Item {

            companion object : Settings() {
                val list = mutableListOf<Subtitle>()

                val selected: Subtitle
                    get() = list.find {
                        when (it) {
                            is None -> it.isSelected
                            is TextTrackInformation -> it.isSelected
                            else -> false
                        }
                    } ?: None

                fun init(player: ExoPlayer, resources: Resources) {
                    list.clear()
                    list.add(Style)
                    list.add(Offset)
                    list.add(None)
                    list.addAll(
                        player.currentTracks.groups
                            .filter { it.type == C.TRACK_TYPE_TEXT }
                            .flatMap { trackGroup ->
                                trackGroup.supportedTrackFormats
                                    .filter { it.format.selectionFlags and C.SELECTION_FLAG_FORCED == 0 }
                                    .map { (trackIndex, trackFormat) ->
                                        TextTrackInformation(
                                            name = DefaultTrackNameProvider(resources)
                                                .getTrackName(trackFormat),
                                            label = trackFormat.label ?: "",
                                            language = trackFormat.language?.replaceFirstChar { it.titlecase() },

                                            trackGroup = trackGroup,
                                            trackIndex = trackIndex,
                                        )
                                    }
                            }
                            .sortedBy { it.language ?: it.label }
                    )
                    list.add(LocalSubtitles)
                    list.add(OpenSubtitles)
                    // Add SubDL only if an API key is configured
                    if (UserPreferences.subdlApiKey.isNotEmpty()) {
                        list.add(SubDLSubtitles)
                    }
                }
            }

            data object Offset : Subtitle() {
                val list: List<Item> = (-10_000L..10_000L step 500L).map(::Value)
                val selected: Value
                    get() = list.filterIsInstance<Value>()
                        .find { it.milliseconds == SubtitleOffset.offsetMs }
                        ?: Value(0)

                class Value(val milliseconds: Long) : Item {
                    val isSelected: Boolean
                        get() = milliseconds == SubtitleOffset.offsetMs
                }
            }

            sealed class Style : Item {

                companion object : Subtitle() {
                    val DEFAULT = CaptionStyleCompat(
                        Color.WHITE,
                        Color.BLACK.setAlpha(128),
                        Color.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_NONE,
                        Color.BLACK,
                        null
                    )

                    val list: List<Item> = listOf(
                        ResetStyle,
                        FontColor,
                        TextSize,
                        FontOpacity,
                        EdgeStyle,
                        BackgroundColor,
                        BackgroundOpacity,
                        WindowColor,
                        WindowOpacity,
                        Margin,
                    )
                }

                class Margin(
                    val value: Int,
                ) : Item {
                    val isSelected: Boolean
                        get() = selected == this

                    companion object : Style() {
                        val list: List<Item> = (0..100 step 4).map { Margin(it) }
                        val selected: Margin
                            get() = list.filterIsInstance<Margin>().find { it.value == UserPreferences.captionMargin } ?: list.filterIsInstance<Margin>().find { it.value == 24 } ?: Margin(24)
                    }
                }

                data object ResetStyle : Style()

                class FontColor(
                    val stringId: Int,
                    val color: Int,
                ) : Item {
                    val isSelected: Boolean
                        get() = color == UserPreferences.captionStyle.foregroundColor.getRgb()

                    companion object : Style() {
                        private val DEFAULT = FontColor(
                            R.string.player_settings_caption_style_font_color_white,
                            Color.WHITE
                        )

                        val list = listOf(
                            DEFAULT,
                            FontColor(
                                R.string.player_settings_caption_style_font_color_yellow,
                                Color.YELLOW
                            ),
                            FontColor(
                                R.string.player_settings_caption_style_font_color_green,
                                Color.GREEN
                            ),
                            FontColor(
                                R.string.player_settings_caption_style_font_color_cyan,
                                Color.CYAN
                            ),
                            FontColor(
                                R.string.player_settings_caption_style_font_color_blue,
                                Color.BLUE
                            ),
                            FontColor(
                                R.string.player_settings_caption_style_font_color_magenta,
                                Color.MAGENTA
                            ),
                            FontColor(
                                R.string.player_settings_caption_style_font_color_red,
                                Color.RED
                            ),
                            FontColor(
                                R.string.player_settings_caption_style_font_color_black,
                                Color.BLACK
                            ),
                        )

                        val selected: FontColor
                            get() = list.find { it.isSelected } ?: DEFAULT
                    }
                }

                class TextSize(
                    val stringId: Int,
                    val value: Float,
                ) : Item {
                    val isSelected: Boolean
                        get() = value == UserPreferences.captionTextSize

                    companion object : Style() {
                        val DEFAULT = TextSize(
                            R.string.player_settings_caption_style_text_size_1,
                            1F
                        )

                        val list = listOf(
                            TextSize(
                                R.string.player_settings_caption_style_text_size_0_5,
                                0.5F
                            ),
                            TextSize(
                                R.string.player_settings_caption_style_text_size_0_75,
                                0.75F
                            ),
                            TextSize(
                                R.string.player_settings_caption_style_text_size_0_8,
                                0.8F
                            ),
                            TextSize(
                                R.string.player_settings_caption_style_text_size_0_9,
                                0.9F
                            ),
                            DEFAULT,
                            TextSize(
                                R.string.player_settings_caption_style_text_size_1_25,
                                1.25F
                            ),
                            TextSize(
                                R.string.player_settings_caption_style_text_size_1_5,
                                1.5F
                            ),
                            TextSize(
                                R.string.player_settings_caption_style_text_size_2,
                                2F
                            ),
                            TextSize(
                                R.string.player_settings_caption_style_text_size_3,
                                3F
                            ),
                        )

                        val selected: TextSize
                            get() = list.find { it.isSelected } ?: DEFAULT
                    }
                }

                class FontOpacity(
                    val stringId: Int,
                    private val value: Float,
                ) : Item {
                    val alpha: Int
                        get() = (value * 255).roundToInt()

                    val isSelected: Boolean
                        get() = alpha == UserPreferences.captionStyle.foregroundColor.getAlpha()

                    companion object : Style() {
                        private val DEFAULT = FontOpacity(
                            R.string.player_settings_caption_style_font_opacity_1,
                            1F
                        )

                        val list = listOf(
                            FontOpacity(
                                R.string.player_settings_caption_style_font_opacity_0_5,
                                0.5F
                            ),
                            FontOpacity(
                                R.string.player_settings_caption_style_font_opacity_0_75,
                                0.75F
                            ),
                            DEFAULT,
                        )

                        val selected: FontOpacity
                            get() = list.find { it.isSelected } ?: DEFAULT
                    }
                }

                class EdgeStyle(
                    val stringId: Int,
                    val type: Int,
                ) : Item {
                    val isSelected: Boolean
                        get() = type == UserPreferences.captionStyle.edgeType

                    companion object : Style() {
                        private val DEFAULT = EdgeStyle(
                            R.string.player_settings_caption_style_edge_style_none,
                            CaptionStyleCompat.EDGE_TYPE_NONE
                        )

                        val list = listOf(
                            DEFAULT,
                            EdgeStyle(
                                R.string.player_settings_caption_style_edge_style_raised,
                                CaptionStyleCompat.EDGE_TYPE_RAISED
                            ),
                            EdgeStyle(
                                R.string.player_settings_caption_style_edge_style_depressed,
                                CaptionStyleCompat.EDGE_TYPE_DEPRESSED
                            ),
                            EdgeStyle(
                                R.string.player_settings_caption_style_edge_style_outline,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE
                            ),
                            EdgeStyle(
                                R.string.player_settings_caption_style_edge_style_drop_shadow,
                                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                            ),
                        )

                        val selected: EdgeStyle
                            get() = list.find { it.isSelected } ?: DEFAULT
                    }
                }

                class BackgroundColor(
                    val stringId: Int,
                    val color: Int,
                ) : Item {
                    val isSelected: Boolean
                        get() = color == UserPreferences.captionStyle.backgroundColor.getRgb()

                    companion object : Style() {
                        private val DEFAULT = BackgroundColor(
                            R.string.player_settings_caption_style_background_color_black,
                            Color.BLACK
                        )

                        val list = listOf(
                            DEFAULT,
                            BackgroundColor(
                                R.string.player_settings_caption_style_background_color_yellow,
                                Color.YELLOW
                            ),
                            BackgroundColor(
                                R.string.player_settings_caption_style_background_color_green,
                                Color.GREEN
                            ),
                            BackgroundColor(
                                R.string.player_settings_caption_style_background_color_cyan,
                                Color.CYAN
                            ),
                            BackgroundColor(
                                R.string.player_settings_caption_style_background_color_blue,
                                Color.BLUE
                            ),
                            BackgroundColor(
                                R.string.player_settings_caption_style_background_color_magenta,
                                Color.MAGENTA
                            ),
                            BackgroundColor(
                                R.string.player_settings_caption_style_background_color_red,
                                Color.RED
                            ),
                            BackgroundColor(
                                R.string.player_settings_caption_style_background_color_white,
                                Color.WHITE
                            ),
                        )

                        val selected: BackgroundColor
                            get() = list.find { it.isSelected } ?: DEFAULT
                    }
                }

                class BackgroundOpacity(
                    val stringId: Int,
                    private val value: Float,
                ) : Item {
                    val alpha: Int
                        get() = (value * 255).roundToInt()

                    val isSelected: Boolean
                        get() = alpha == UserPreferences.captionStyle.backgroundColor.getAlpha()

                    companion object : Style() {
                        private val DEFAULT = BackgroundOpacity(
                            R.string.player_settings_caption_style_background_opacity_0_5,
                            0.5F
                        )

                        val list = listOf(
                            BackgroundOpacity(
                                R.string.player_settings_caption_style_background_opacity_0,
                                0F
                            ),
                            BackgroundOpacity(
                                R.string.player_settings_caption_style_background_opacity_0_25,
                                0.25F
                            ),
                            DEFAULT,
                            BackgroundOpacity(
                                R.string.player_settings_caption_style_background_opacity_0_75,
                                0.75F
                            ),
                            BackgroundOpacity(
                                R.string.player_settings_caption_style_background_opacity_1,
                                1F
                            ),
                        )

                        val selected: BackgroundOpacity
                            get() = list.find { it.isSelected } ?: DEFAULT
                    }
                }

                class WindowColor(
                    val stringId: Int,
                    val color: Int,
                ) : Item {
                    val isSelected: Boolean
                        get() = color == UserPreferences.captionStyle.windowColor.getRgb()

                    companion object : Style() {
                        private val DEFAULT = WindowColor(
                            R.string.player_settings_caption_style_window_color_black,
                            Color.BLACK
                        )

                        val list = listOf(
                            DEFAULT,
                            WindowColor(
                                R.string.player_settings_caption_style_window_color_yellow,
                                Color.YELLOW
                            ),
                            WindowColor(
                                R.string.player_settings_caption_style_window_color_green,
                                Color.GREEN
                            ),
                            WindowColor(
                                R.string.player_settings_caption_style_window_color_cyan,
                                Color.CYAN
                            ),
                            WindowColor(
                                R.string.player_settings_caption_style_window_color_blue,
                                Color.BLUE
                            ),
                            WindowColor(
                                R.string.player_settings_caption_style_window_color_magenta,
                                Color.MAGENTA
                            ),
                            WindowColor(
                                R.string.player_settings_caption_style_window_color_red,
                                Color.RED
                            ),
                            WindowColor(
                                R.string.player_settings_caption_style_window_color_white,
                                Color.WHITE
                            ),
                        )

                        val selected: WindowColor
                            get() = list.find { it.isSelected } ?: DEFAULT
                    }
                }

                class WindowOpacity(
                    val stringId: Int,
                    private val value: Float,
                ) : Item {
                    val alpha: Int
                        get() = (value * 255).roundToInt()

                    val isSelected: Boolean
                        get() = alpha == UserPreferences.captionStyle.windowColor.getAlpha()

                    companion object : Style() {
                        private val DEFAULT = WindowOpacity(
                            R.string.player_settings_caption_style_window_opacity_0,
                            0F
                        )

                        val list = listOf(
                            DEFAULT,
                            WindowOpacity(
                                R.string.player_settings_caption_style_window_opacity_0_25,
                                0.25F
                            ),
                            WindowOpacity(
                                R.string.player_settings_caption_style_window_opacity_0_5,
                                0.5F
                            ),
                            WindowOpacity(
                                R.string.player_settings_caption_style_window_opacity_0_75,
                                0.75F
                            ),
                            WindowOpacity(
                                R.string.player_settings_caption_style_window_opacity_1,
                                1F
                            ),
                        )

                        val selected: WindowOpacity
                            get() = list.find { it.isSelected } ?: DEFAULT
                    }
                }
            }

            data object None : Subtitle() {
                val isSelected: Boolean
                    get() = list
                        .filterIsInstance<TextTrackInformation>()
                        .none { it.isSelected }
            }

            class TextTrackInformation(
                val name: String,
                val label: String,
                val language: String?,

                val trackGroup: Tracks.Group,
                val trackIndex: Int,
            ) : Subtitle() {
                val isSelected: Boolean
                    get() = trackGroup.isTrackSelected(trackIndex)
            }

            data object LocalSubtitles : Subtitle()

            sealed class OpenSubtitles : Item {

                companion object : Settings.Subtitle() {
                    val list = mutableListOf<Subtitle>()

                    fun init(openSubtitles: List<com.streamflixreborn.streamflix.utils.OpenSubtitles.Subtitle>) {
                        list.clear()
                        list.addAll(openSubtitles.map {
                            Subtitle(it)
                        })
                    }
                }

                class Subtitle(
                    val openSubtitle: com.streamflixreborn.streamflix.utils.OpenSubtitles.Subtitle
                ) : OpenSubtitles()
            }

            sealed class SubDLSubtitles : Item {

                companion object : Settings.Subtitle() {
                    val list = mutableListOf<Subtitle>()

                    fun init(subDLSubtitles: List<com.streamflixreborn.streamflix.utils.SubDL.Subtitle>) {
                        list.clear()
                        list.addAll(subDLSubtitles.map {
                            Subtitle(it)
                        })
                    }
                }

                class Subtitle(
                    val subDLSubtitle: com.streamflixreborn.streamflix.utils.SubDL.Subtitle
                ) : SubDLSubtitles()
            }
        }

        class Speed(
            val stringId: Int,
            val value: Float,
        ) : Item {
            var isSelected: Boolean = false

            companion object : Settings() {
                private val DEFAULT = Speed(R.string.player_settings_speed_1, 1F)

                val list = listOf(
                    Speed(R.string.player_settings_speed_0_25, 0.25F),
                    Speed(R.string.player_settings_speed_0_5, 0.5F),
                    Speed(R.string.player_settings_speed_0_75, 0.75F),
                    DEFAULT,
                    Speed(R.string.player_settings_speed_1_25, 1.25F),
                    Speed(R.string.player_settings_speed_1_5, 1.5F),
                    Speed(R.string.player_settings_speed_1_75, 1.75F),
                    Speed(R.string.player_settings_speed_2, 2F),
                )

                val selected: Speed
                    get() = list.find { it.isSelected }
                        ?: list.find { it.value == 1F }
                        ?: DEFAULT

                fun refresh(player: ExoPlayer) {
                    list.forEach { it.isSelected = false }
                    list.findClosest(player.playbackParameters.speed) { it.value }?.let {
                        it.isSelected = true
                    }
                }
            }
        }

        class Server(
            val id: String,
            val name: String,
        ) : Item {
            var isSelected: Boolean = false

            companion object : Settings() {
                val list = mutableListOf<Server>()

                val selected: Server?
                    get() = list.find { it.isSelected }

                fun init(player: ExoPlayer) {
                    list.clear()
                    list.addAll(player.playlistMetadata.mediaServers.map {
                        Server(
                            id = it.id,
                            name = it.name,
                        )
                    })

                    list.firstOrNull()?.isSelected = true
                }

                fun refresh(player: ExoPlayer) {
                    list.forEach {
                        it.isSelected = (it.id == player.mediaMetadata.mediaServerId)
                    }
                }
            }
        }
    }
}
