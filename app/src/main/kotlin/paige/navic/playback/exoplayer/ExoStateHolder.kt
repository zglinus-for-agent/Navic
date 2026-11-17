/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.playback.exoplayer

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Rating
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.wav.WavExtractor
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import coil3.ImageLoader
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.runtime.StorageManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.repository.SongRepository
import paige.navic.playback.exoplayer.impl.cache.NavicDataSource
import paige.navic.playback.exoplayer.impl.listeners.ExoDiscordIntegrationImpl
import paige.navic.playback.exoplayer.impl.playback.ExoAudioGainProcessor
import paige.navic.playback.exoplayer.impl.util.ExoArtworkLoader
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

@OptIn(UnstableApi::class)
class ExoStateHolder : KoinComponent {
	private val context: Context by inject()
	private val preferenceManager: PreferenceManager by inject()
	private val dataSource: NavicDataSource.NavicDataSourceFactory by inject()
	private val sessionManager: SessionManager by inject()
	private val imageLoader: ImageLoader by inject()
	private val storageManager: StorageManager by inject()
	private val songRepository: SongRepository by inject()
	private val mutex = Mutex()

	// Favourite commands arrive on the session's looper; star/unstar touches Room and
	// the sync queue, so it goes off to IO rather than blocking that looper.
	private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	// Cached so makeButtons() can pick the heart icon synchronously; refreshed from the
	// DB on media item transitions and flipped optimistically when the button is hit.
	@Volatile
	private var favouriteStarred = false

	@Volatile
	private var favouriteMediaId: String? = null

	private var isInitialized = false

	// ExoPlaybackService and AudioGainManager depends on these fields below
	lateinit var playerInstance: ExoPlayer
	lateinit var mediaSession: MediaSession

	val gainProcessor = ExoAudioGainProcessor()


	private inner class MediaSessionCallback(private val player: ExoPlayer) : MediaSession.Callback {
		override fun onConnect(
			session: MediaSession,
			controller: MediaSession.ControllerInfo
		): MediaSession.ConnectionResult {
			// only the app may edit the playlist or push a shuffle order, otherwise
			// external controllers desync the app queue from the player timeline
			val isOwnApp = controller.uid == Process.myUid()

			// PROBE (2026-11-17): who is actually talking to this session. If nothing
			// external ever connects here, the HarmonyOS media card is not going
			// through MediaSession at all and this whole route is a dead end.
			Log.i(
				"NavicProbe",
				"onConnect pkg=${controller.packageName} uid=${controller.uid} " +
					"own=$isOwnApp trusted=${controller.isTrusted} " +
					"hints=${controller.connectionHints}"
			)

			val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
				.buildUpon()
				.add(SessionCommand(COMMAND_SHUFFLE, Bundle.EMPTY))
				.add(SessionCommand(COMMAND_REPEAT, Bundle.EMPTY))
				.add(SessionCommand(COMMAND_FAVORITE, Bundle.EMPTY))
				.apply {
					if (isOwnApp) add(SessionCommand(COMMAND_SET_SHUFFLE_ORDER, Bundle.EMPTY))
				}
				.build()

			val playerCommands =
				if (isOwnApp) {
					MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
				} else {
					// shortcut: blocks playlist edits from every external controller, so Android
					// Auto browse-and-play needs an exception here once real Auto support lands
					MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
						.buildUpon()
						.remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
						.remove(Player.COMMAND_SET_MEDIA_ITEM)
						.build()
				}

			return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
		}

		override fun onCustomCommand(
			session: MediaSession,
			controller: MediaSession.ControllerInfo,
			customCommand: SessionCommand,
			args: Bundle
		): ListenableFuture<SessionResult> {
			// PROBE: log every custom command verbatim, including unknown ones.
			Log.i(
				"NavicProbe",
				"onCustomCommand action=${customCommand.customAction} " +
					"pkg=${controller.packageName} args=$args"
			)
			when (customCommand.customAction) {
				COMMAND_SHUFFLE -> {
					player.shuffleModeEnabled = !player.shuffleModeEnabled
				}

				COMMAND_REPEAT -> {
					player.repeatMode = when (player.repeatMode) {
						Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
						Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
						else -> Player.REPEAT_MODE_OFF
					}
				}

				COMMAND_FAVORITE -> {
					toggleFavourite(session, player)
				}

				COMMAND_SET_SHUFFLE_ORDER -> {
					val order = args.getIntArray(KEY_SHUFFLE_ORDER)
					if (order != null && order.size == player.mediaItemCount) {
						player.setShuffleOrder(
							ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime())
						)
					}
				}

				else -> Log.w(
					"NavicProbe",
					"UNKNOWN custom action: ${customCommand.customAction}"
				)
			}

			return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
		}

		// media3 grants COMMAND_CODE_SESSION_SET_RATING by default (it is in
		// SessionCommand.SESSION_WRITE_COMMANDS) but Callback#onSetRating answers
		// ERROR_NOT_SUPPORTED unless overridden, so a system UI whose favourite
		// button is bound to rating used to get a silent "no". Now the rating is
		// applied to the song: heart on -> star, heart off -> unstar.
		override fun onSetRating(
			session: MediaSession,
			controller: MediaSession.ControllerInfo,
			rating: Rating
		): ListenableFuture<SessionResult> {
			applyFavorite(player.currentMediaItem?.mediaId, rating, controller)
			return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
		}

		override fun onSetRating(
			session: MediaSession,
			controller: MediaSession.ControllerInfo,
			mediaId: String,
			rating: Rating
		): ListenableFuture<SessionResult> {
			applyFavorite(mediaId, rating, controller)
			return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
		}

		// Our own heart button, the same shape NetEase Cloud Music uses: the app draws
		// the control and handles the click itself, it does not wait for the system to
		// provide one.
		private fun toggleFavourite(session: MediaSession, player: Player) {
			val id = favouriteMediaId ?: player.currentMediaItem?.mediaId
			if (id == null || id.startsWith("radio_")) {
				Log.w("NavicProbe", "toggleFavourite: nothing to favourite (id=$id)")
				return
			}
			val target = !favouriteStarred
			favouriteStarred = target
			session.setCustomLayout(makeButtons(player))
			Log.i("NavicProbe", "toggleFavourite id=$id -> star=$target")
			ioScope.launch {
				try {
					if (target) songRepository.starSong(id) else songRepository.unstarSong(id)
					Log.i("NavicProbe", "favourite written: id=$id star=$target")
				} catch (e: Exception) {
					Log.e("NavicProbe", "favourite write failed: id=$id star=$target", e)
					// put the icon back where the data actually is
					withContext(Dispatchers.Main) {
						favouriteStarred = !target
						session.setCustomLayout(makeButtons(player))
					}
				}
			}
		}

		private fun applyFavorite(
			mediaId: String?,
			rating: Rating,
			controller: MediaSession.ControllerInfo
		) {			val id = mediaId ?: run {
				Log.w("NavicProbe", "onSetRating: no media item to rate")
				return
			}
			// radio_* items are live streams with no row in the song table
			if (id.startsWith("radio_")) {
				Log.w("NavicProbe", "onSetRating: ignoring radio item $id")
				return
			}
			val wantStarred = rating.isRated
			Log.i(
				"NavicProbe",
				"onSetRating id=$id star=$wantStarred rating=$rating " +
					"pkg=${controller.packageName}"
			)
			ioScope.launch {
				try {
					if (wantStarred) songRepository.starSong(id)
					else songRepository.unstarSong(id)
					Log.i("NavicProbe", "favourite applied: id=$id star=$wantStarred")
				} catch (e: Exception) {
					Log.e("NavicProbe", "favourite failed: id=$id star=$wantStarred", e)
				}
			}
		}
	}

	private val extractorsFactory = ExtractorsFactory {
		arrayOf(
			FlacExtractor(),
			WavExtractor(),
			FragmentedMp4Extractor(DefaultSubtitleParserFactory.UNSUPPORTED),
			Mp4Extractor(DefaultSubtitleParserFactory.UNSUPPORTED),
			OggExtractor(),
			MatroskaExtractor(DefaultSubtitleParserFactory.UNSUPPORTED),
			AdtsExtractor(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING),
			Mp3Extractor(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
		)
	}

	private fun createRenderers(context: Context): RenderersFactory {
		val audioProcessors = arrayOf<AudioProcessor>(gainProcessor)
		val audioSinkBuilder = DefaultAudioSink.Builder(context)

		if (preferenceManager.floatOutputEnabled) {
			// using setAudioProcessors will indirectly use the default audio processor chain
			// which by default uses the silence skipper and playback speed audio processors,
			// they both don't work with anything that isn't 16 bit pcm,
			// so exoplayer (our fork) will just happily ignore the replaygain processor
			// that's why this below is being done
			audioSinkBuilder
				.setEnableFloatOutput(true)
				.setAudioProcessorChain(object : AudioProcessorChain {
					override fun getAudioProcessors(inputFormat: Format): Array<out AudioProcessor> {
						return audioProcessors
					}

					override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
						return PlaybackParameters.DEFAULT
					}

					override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
						return false
					}

					override fun getMediaDuration(playoutDuration: Long): Long {
						return playoutDuration
					}

					override fun getSkippedOutputFrameCount(): Long {
						return 0
					}
				})
		} else {
			audioSinkBuilder.setAudioProcessors(audioProcessors)
		}

		val audioSink = audioSinkBuilder.build()

		return RenderersFactory { eventHandler, _, eventListener, _, _ ->
			arrayOf<BaseRenderer>(
				FfmpegAudioRenderer(
					eventHandler,
					eventListener,
					audioSink
				),
				MediaCodecAudioRenderer(
					context,
					MediaCodecSelector.DEFAULT,
					eventHandler,
					eventListener,
					audioSink
				)
			)
		}
	}

	private fun makeButtons(player: Player) = buildList {
		add(
			CommandButton.Builder(
				if (player.shuffleModeEnabled) {
					CommandButton.ICON_SHUFFLE_ON
				} else {
					CommandButton.ICON_SHUFFLE_OFF
				}
			)
				.setDisplayName("Shuffle")
				.setSessionCommand(SessionCommand(COMMAND_SHUFFLE, Bundle.EMPTY))
				.build()
		)

		add(
			CommandButton.Builder(
				when (player.repeatMode) {
					Player.REPEAT_MODE_OFF -> CommandButton.ICON_REPEAT_OFF
					Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
					else -> CommandButton.ICON_REPEAT_ONE
				}
			)
				.setDisplayName("Repeat")
				.setSessionCommand(SessionCommand(COMMAND_REPEAT, Bundle.EMPTY))
				.build()
		)

		add(
			CommandButton.Builder(
				if (favouriteStarred) {
					CommandButton.ICON_HEART_FILLED
				} else {
					CommandButton.ICON_HEART_UNFILLED
				}
			)
				.setDisplayName(if (favouriteStarred) "Unfavourite" else "Favourite")
				.setSessionCommand(SessionCommand(COMMAND_FAVORITE, Bundle.EMPTY))
				.setEnabled(favouriteMediaId != null)
				.build()
		)
	}

	private val loadControl: LoadControl = DefaultLoadControl.Builder().apply {
		setBufferDurationsMs(
			/* minBufferMs = */ 32_000,
			/* maxBufferMs = */ 64_000,
			/* bufferForPlaybackMs = */ 2_500,
			/* bufferForPlaybackAfterRebufferMs = */ 5_000
		)
		setBackBuffer(10_000, true)
	}.build()

	private suspend fun createPlayerInstance(): ExoPlayer = mutex.withLock {
		val mediaSourceFactory = DefaultMediaSourceFactory(dataSource, extractorsFactory)

		playerInstance = ExoPlayer.Builder(
			context,
			createRenderers(context)
		).apply {
			setLoadControl(loadControl)
			setMediaSourceFactory(mediaSourceFactory)
			setHandleAudioBecomingNoisy(true)
			setWakeMode(C.WAKE_MODE_NETWORK)
		}.build()
			.apply {
				setAudioAttributes(
					AudioAttributes.Builder()
						.setUsage(C.USAGE_MEDIA)
						.setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
						.build(),
					true
				)

				trackSelectionParameters =
					trackSelectionParameters.buildUpon().setAudioOffloadPreferences(
						TrackSelectionParameters.AudioOffloadPreferences
							.Builder()
							.setIsGaplessSupportRequired(preferenceManager.gaplessPlayback)
							.setAudioOffloadMode(
								if (preferenceManager.audioOffload) {
									TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
								} else {
									TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
								}
							)
							.build()
					).build()
			}
			.also { player ->
				player.preloadConfiguration = ExoPlayer.PreloadConfiguration(
					10.seconds.toLong(DurationUnit.MICROSECONDS)
				)

				player.addListener(object : Player.Listener {
					override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
						mediaSession.setCustomLayout(makeButtons(player))
					}

					override fun onRepeatModeChanged(repeatMode: Int) {
						mediaSession.setCustomLayout(makeButtons(player))
					}

					override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
						// this fires as soon as items are set, which can be before the
						// session exists - hence the guard
						if (!this@ExoStateHolder::mediaSession.isInitialized) return
						val id = mediaItem?.mediaId
						favouriteMediaId = id
						favouriteStarred = false
						mediaSession.setCustomLayout(makeButtons(player))
						if (id == null || id.startsWith("radio_")) return
						// the heart must reflect the DB, not the last click
						ioScope.launch {
							val starred = try {
								songRepository.isSongStarred(id)
							} catch (e: Exception) {
								false
							}
							withContext(Dispatchers.Main) {
								if (favouriteMediaId == id) {
									favouriteStarred = starred
									mediaSession.setCustomLayout(makeButtons(player))
								}
							}
						}
					}
				})

				player.addListener(ExoDiscordIntegrationImpl(player))
			}

		return playerInstance
	}

	private suspend fun createMediaSession(): MediaSession = mutex.withLock {
		val bitmapLoader = ExoArtworkLoader(
			context,
			dataSourceFactory = dataSource,
			imageLoader,
			sessionManager,
			storageManager
		)
		val sessionIntent = context.packageManager
			.getLaunchIntentForPackage(context.packageName)
			?.apply {
				flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
					Intent.FLAG_ACTIVITY_CLEAR_TOP
			}

		val sessionPendingIntent = PendingIntent.getActivity(
			context,
			0,
			sessionIntent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		this.playerInstance.let {
			mediaSession = MediaSession.Builder(context, it)
				.setSessionActivity(sessionPendingIntent)
				.setBitmapLoader(bitmapLoader)
				.setCallback(MediaSessionCallback(it))
				.setCustomLayout(makeButtons(it))
				.build()
		}

		return mediaSession
	}

	/**
	 * initializes instances for the player and the media session, should be called ASAP on PlaybackService (onCreate)
	 */
	fun initState() = runBlocking {
		if (isInitialized) return@runBlocking

		createPlayerInstance()
		createMediaSession()
		isInitialized = !isInitialized
	}

	fun destroySession() {
		if (!isInitialized) return

		mediaSession.let {
			it.player.stop()
			it.player.release()
			it.release()
		}
		isInitialized = !isInitialized
	}

	fun reloadNetworkDataSource() {
		if (!isInitialized) return

		val player = playerInstance
		val mediaItemIndex = player.currentMediaItemIndex
		val mediaItem = player.currentMediaItem ?: return
		if (mediaItemIndex == C.INDEX_UNSET) return

		val position = player.currentPosition.coerceAtLeast(0L)
		val playWhenReady = player.playWhenReady
		player.replaceMediaItem(mediaItemIndex, mediaItem)
		player.seekTo(mediaItemIndex, position)
		player.playWhenReady = playWhenReady
	}

	companion object {
		const val COMMAND_SHUFFLE = "COMMAND_SHUFFLE"
		const val COMMAND_REPEAT = "COMMAND_REPEAT"
		const val COMMAND_FAVORITE = "COMMAND_FAVORITE"
		const val COMMAND_SET_SHUFFLE_ORDER = "COMMAND_SET_SHUFFLE_ORDER"
		const val KEY_SHUFFLE_ORDER = "KEY_SHUFFLE_ORDER"

		fun newSessionToken(context: Context): SessionToken {
			return SessionToken(context, ComponentName(context, ExoPlaybackService::class.java))
		}
	}
}
