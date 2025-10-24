package com.bina.videonetwork

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ConcatenatingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var btnPrevious: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var btnSync: MaterialButton
    private lateinit var btnSetFolder: MaterialButton
    private lateinit var btnExit: MaterialButton
    private lateinit var sharedPreferences: android.content.SharedPreferences
    private lateinit var gDriveSyncHelper: GDriveSyncHelper

    private val handler = Handler(Looper.getMainLooper())
    private val TAG = "VideoNetwork"

    private var folderUri: Uri? = null
    private val PREFS_NAME = "VideoNetworkPrefs"
    private val PREF_FOLDER_URI = "folder_uri"

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            gDriveSyncHelper.handleSignInResult(
                result.data,
                onSuccess = {
                    performSync()
                },
                onFailed = { error ->
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
            )
        } else {
            Toast.makeText(this, "Sign-in failed", Toast.LENGTH_SHORT).show()
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Take persistent permissions and save the folder
            takePersistentFolderPermission(uri)
        } ?: run {
            Toast.makeText(this, "No folder selected", Toast.LENGTH_SHORT).show()
            // If no folder selected but we have a previous one, try to use it
            ensureFolderAndPlay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        gDriveSyncHelper = GDriveSyncHelper(this)
        gDriveSyncHelper.initialize()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN)

        playerView = findViewById(R.id.player_view)
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        btnPlayPause = playerView.findViewById(R.id.exo_play_pause)
        btnPrevious = playerView.findViewById(R.id.exo_prev)
        btnNext = playerView.findViewById(R.id.exo_next)
        btnSync = playerView.findViewById(R.id.btn_sync)
        btnSetFolder = playerView.findViewById(R.id.btn_set_folder)
        btnExit = playerView.findViewById(R.id.btn_exit)

        playerView.isFocusable = true
        playerView.isFocusableInTouchMode = true

        playerView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !player.isPlaying) {
                playerView.showController()
            } else if (player.isPlaying) {
                playerView.hideController()
            }
        }

        playerView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                playerView.requestFocus()
                playerView.showController()
                if (player.isPlaying) {
                    handler.postDelayed({ if (player.isPlaying) playerView.hideController() }, 2000)
                }
            }
            true
        }

        setupPlayerListener()
        setupButtonListeners()

        // Ensure we have a valid folder and start playback
        ensureFolderAndPlay()
    }

    private fun ensureFolderAndPlay() {
        Log.d(TAG, "Ensuring folder is available and starting playback")

        // First, try to load the saved folder URI
        val savedUriString = sharedPreferences.getString(PREF_FOLDER_URI, null)
        folderUri = savedUriString?.let { Uri.parse(it) }

        if (folderUri != null) {
            // Check if we still have permission to access the folder
            if (hasFolderAccess(folderUri!!)) {
                Log.d(TAG, "Found valid persisted folder: $folderUri")
                loadVideosAndPlay()
            } else {
                Log.w(TAG, "Lost access to persisted folder, requesting new one")
                // We lost access, need to request a new folder
                folderUri = null
                sharedPreferences.edit().remove(PREF_FOLDER_URI).apply()
                showFolderPicker()
            }
        } else {
            // No folder saved, show picker
            Log.d(TAG, "No folder saved, showing picker")
            showFolderPicker()
        }
    }

    private fun hasFolderAccess(uri: Uri): Boolean {
        return try {
            val documentFile = DocumentFile.fromTreeUri(this, uri)
            documentFile?.canRead() == true && documentFile.canWrite() == true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking folder access: ${e.message}")
            false
        }
    }

    private fun takePersistentFolderPermission(uri: Uri) {
        try {
            // Take persistent permissions
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            // Save the folder URI
            folderUri = uri
            sharedPreferences.edit().putString(PREF_FOLDER_URI, uri.toString()).apply()

            Log.d(TAG, "Folder permission granted and saved: $uri")
            refreshPlaylistAndRestartPlayer()
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to take persistent permission: ${e.message}")
            Toast.makeText(this, "Failed to get folder access. Please try again.", Toast.LENGTH_LONG).show()
            showFolderPicker()
        } catch (e: Exception) {
            Log.e(TAG, "Error taking folder permission: ${e.message}")
            Toast.makeText(this, "Error accessing folder. Please try again.", Toast.LENGTH_LONG).show()
            showFolderPicker()
        }
    }

    private fun showFolderPicker() {
        Log.d(TAG, "Showing folder picker")
        try {
            folderPickerLauncher.launch(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing folder picker: ${e.message}")
            Toast.makeText(this, "Cannot open folder picker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupButtonListeners() {
        btnPlayPause.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
                btnPlayPause.setIconResource(android.R.drawable.ic_media_play)
                playerView.showController()
            } else {
                player.play()
                btnPlayPause.setIconResource(android.R.drawable.ic_media_pause)
                playerView.hideController()
            }
        }

        btnPrevious.setOnClickListener {
            player.seekToPrevious()
            playerView.showController()
            if (player.isPlaying) {
                handler.postDelayed({ if (player.isPlaying) playerView.hideController() }, 2000)
            }
        }

        btnNext.setOnClickListener {
            player.seekToNext()
            playerView.showController()
            if (player.isPlaying) {
                handler.postDelayed({ if (player.isPlaying) playerView.hideController() }, 2000)
            }
        }

        btnSync.setOnClickListener {
            if (folderUri == null) {
                Toast.makeText(this, "Please select a folder first", Toast.LENGTH_SHORT).show()
                showFolderPicker()
            } else {
                performSync()
            }
        }

        btnSetFolder.setOnClickListener {
            showFolderPicker()
            playerView.showController()
            if (player.isPlaying) {
                handler.postDelayed({ if (player.isPlaying) playerView.hideController() }, 2000)
            }
        }

        btnExit.setOnClickListener {
            finish()
        }
    }

    private fun performSync() {
        if (folderUri == null) {
            Toast.makeText(this, "No folder selected for sync", Toast.LENGTH_SHORT).show()
            showFolderPicker()
            return
        }

        Toast.makeText(this, "Syncing videos...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            gDriveSyncHelper.syncWithGDrive(
                folderUri,
                getString(R.string.gdrive_shared_folder),
                onComplete = {
                    // Refresh playlist and restart player after successful sync
                    refreshPlaylistAndRestartPlayer()
                    Toast.makeText(this@MainActivity, "Sync complete", Toast.LENGTH_SHORT).show()
                },
                onFailed = { error ->
                    if (error.contains("Not signed in")) {
                        gDriveSyncHelper.startGoogleSignIn(googleSignInLauncher)
                    } else {
                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
        playerView.showController()
        if (player.isPlaying) {
            handler.postDelayed({ if (player.isPlaying) playerView.hideController() }, 2000)
        }
    }

    private fun refreshPlaylistAndRestartPlayer() {
        Log.d(TAG, "Refreshing playlist and restarting player")

        // Save current playback state if we have a valid folder
        val wasPlaying = player.isPlaying
        val currentPosition = player.currentPosition
        val currentMediaItemIndex = player.currentMediaItemIndex

        // Stop and release current player
        player.stop()
        player.release()

        // Create new player instance
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        setupPlayerListener()

        // Load videos and restore playback state if possible
        loadVideosAndPlay(wasPlaying, currentPosition, currentMediaItemIndex)
    }

    private fun setupPlayerListener() {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && player.isPlaying) {
                    playerView.hideController()
                } else if (state == Player.STATE_ENDED || !player.isPlaying) {
                    playerView.showController()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
            }

            override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                Log.e(TAG, "Player error: ${error.message}")
                Toast.makeText(this@MainActivity, "Playback error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun loadVideosAndPlay(
        restorePlayback: Boolean = false,
        restorePosition: Long = 0,
        restoreMediaItemIndex: Int = 0
    ) {
        if (folderUri == null) {
            Log.e(TAG, "No folder URI available for loading videos")
            Toast.makeText(this, "No video folder selected", Toast.LENGTH_SHORT).show()
            btnPlayPause.setIconResource(android.R.drawable.ic_media_play)
            return
        }

        try {
            val documentFile = DocumentFile.fromTreeUri(this, folderUri!!)
            if (documentFile == null || !documentFile.exists() || !documentFile.canRead()) {
                Log.e(TAG, "Cannot access folder: $folderUri")
                Toast.makeText(this, "Cannot access video folder. Please select again.", Toast.LENGTH_LONG).show()
                folderUri = null
                sharedPreferences.edit().remove(PREF_FOLDER_URI).apply()
                showFolderPicker()
                return
            }

            val videoUris = mutableListOf<Uri>()
            val videoFiles = mutableListOf<DocumentFile>()

            documentFile.listFiles().forEach { file ->
                if (file.type?.startsWith("video/") == true && file.exists() && file.canRead()) {
                    videoUris.add(file.uri)
                    videoFiles.add(file)
                    Log.d(TAG, "Found video: ${file.name} (${file.uri})")
                }
            }

            Log.d(TAG, "Total videos found: ${videoUris.size}")

            if (videoUris.isEmpty()) {
                Toast.makeText(this, "No videos found in folder", Toast.LENGTH_SHORT).show()
                btnPlayPause.setIconResource(android.R.drawable.ic_media_play)
                return
            }

            val mediaSources = ConcatenatingMediaSource()
            val dataSourceFactory = DefaultDataSource.Factory(this)

            videoUris.forEach { videoUri ->
                try {
                    val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(videoUri))
                    mediaSources.addMediaSource(mediaSource)
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating media source for $videoUri: ${e.message}")
                }
            }

            if (mediaSources.size == 0) {
                Toast.makeText(this, "No playable videos found", Toast.LENGTH_SHORT).show()
                btnPlayPause.setIconResource(android.R.drawable.ic_media_play)
                return
            }

            player.setMediaSource(mediaSources)
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.prepare()

            // Restore playback state if requested
            if (restorePlayback && restoreMediaItemIndex < videoUris.size) {
                player.seekTo(restoreMediaItemIndex, restorePosition)
                player.play()
                btnPlayPause.setIconResource(android.R.drawable.ic_media_pause)
                playerView.hideController()
                Log.d(TAG, "Restored playback to position $restorePosition in video $restoreMediaItemIndex")
            } else {
                // Start fresh playback
                player.play()
                btnPlayPause.setIconResource(android.R.drawable.ic_media_pause)
                playerView.hideController()
                Log.d(TAG, "Started fresh playback with ${videoUris.size} videos")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading videos: ${e.message}", e)
            Toast.makeText(this, "Error loading videos: ${e.message}", Toast.LENGTH_LONG).show()
            btnPlayPause.setIconResource(android.R.drawable.ic_media_play)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            playerView.requestFocus()
            playerView.showController()
            if (player.isPlaying) {
                handler.postDelayed({ if (player.isPlaying) playerView.hideController() }, 2000)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        player.release()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}