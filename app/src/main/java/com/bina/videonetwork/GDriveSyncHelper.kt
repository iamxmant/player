package com.bina.videonetwork

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.documentfile.provider.DocumentFile
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

class GDriveSyncHelper(private val context: Context) {

    private val TAG = "GDriveSyncHelper"
    private lateinit var googleSignInClient: GoogleSignInClient
    private var driveService: Drive? = null
    private var folderUri: Uri? = null
    private var sharedFolderName: String? = null

    fun initialize() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE))
            .build()
        googleSignInClient = GoogleSignIn.getClient(context, gso)
    }

    fun startGoogleSignIn(signInLauncher: ActivityResultLauncher<Intent>) {
        signInLauncher.launch(googleSignInClient.signInIntent)
    }

    fun handleSignInResult(data: Intent?, onSuccess: () -> Unit, onFailed: (String) -> Unit) {
        Log.d(TAG, "handleSignInResult called")
        GoogleSignIn.getSignedInAccountFromIntent(data)
            .addOnSuccessListener { account ->
                Log.d(TAG, "Google sign-in successful")
                if (account.email == null) {
                    Log.e(TAG, "No email in account")
                    onFailed("Sign-in failed: No email provided")
                    return@addOnSuccessListener
                }
                Log.d(TAG, "Signed in with email: ${account.email}")
                setupDriveService(account.email!!)
                CoroutineScope(Dispatchers.IO).launch {
                    syncFiles(onSuccess, onFailed)
                }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Sign-in failed: ${exception.message}", exception)
                onFailed("Sign-in failed: ${exception.message ?: "Unknown error"}")
            }
    }

    fun syncWithGDrive(folderUri: Uri?, sharedFolderName: String, onComplete: () -> Unit, onFailed: (String) -> Unit) {
        Log.d(TAG, "syncWithGDrive called with folder: $folderUri, sharedFolder: $sharedFolderName")

        if (folderUri == null) {
            Log.e(TAG, "No local folder selected")
            onFailed("No local folder selected")
            return
        }
        if (!isNetworkAvailable()) {
            Log.e(TAG, "No internet connection")
            onFailed("No internet connection")
            return
        }
        this.folderUri = folderUri
        this.sharedFolderName = sharedFolderName

        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null || account.email == null) {
            Log.e(TAG, "Not signed in")
            onFailed("Not signed in - please sign in first")
        } else {
            Log.d(TAG, "Using account: ${account.email} for sync")
            setupDriveService(account.email!!)
            CoroutineScope(Dispatchers.IO).launch {
                syncFiles(onComplete, onFailed)
            }
        }
    }

    private fun setupDriveService(email: String) {
        Log.d(TAG, "Setting up Drive service for: $email")
        try {
            val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE))
            credential.selectedAccountName = email
            driveService = Drive.Builder(NetHttpTransport(), GsonFactory(), credential)
                .setApplicationName("VideoNetwork")
                .build()
            Log.d(TAG, "Drive service setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up Drive service: ${e.message}", e)
        }
    }

    private suspend fun syncFiles(onComplete: () -> Unit, onFailed: (String) -> Unit) {
        Log.d(TAG, "syncFiles started")
        withContext(Dispatchers.IO) {
            try {
                val drive = driveService ?: throw IllegalStateException("Drive service not initialized")
                val folderUri = this@GDriveSyncHelper.folderUri ?: throw IllegalStateException("Folder URI not set")
                val sharedFolderName = this@GDriveSyncHelper.sharedFolderName ?: throw IllegalStateException("Shared folder name not set")

                Log.d(TAG, "Sync parameters - Folder URI: $folderUri, Shared Folder: $sharedFolderName")

                val documentFolder = DocumentFile.fromTreeUri(context, folderUri)
                    ?: throw IllegalStateException("Invalid folder URI")

                // Get existing local files
                val localFiles = documentFolder.listFiles()
                    .filter { it.type?.startsWith("video/") == true }
                    .associateBy { sanitizeFileName(it.name ?: "") }
                Log.d(TAG, "Found ${localFiles.size} existing local videos")

                // Find Google Drive folder
                Log.d(TAG, "Searching for folder: $sharedFolderName")
                val folderId = findFolderId(drive, sharedFolderName)
                if (folderId == null) {
                    Log.e(TAG, "Folder '$sharedFolderName' not found in Google Drive")
                    throw IllegalStateException("Folder '$sharedFolderName' not found in Google Drive")
                }
                Log.d(TAG, "Found folder ID: $folderId")

                // List files in the Google Drive folder
                Log.d(TAG, "Listing files in Google Drive folder: $folderId")
                val fileList = drive.files().list()
                    .setQ("'$folderId' in parents and (mimeType contains 'video/' or mimeType = 'application/octet-stream') and trashed = false")
                    .setFields("files(id, name, mimeType, size, modifiedTime)")
                    .setIncludeItemsFromAllDrives(true)
                    .setSupportsAllDrives(true)
                    .execute()

                Log.d(TAG, "Found ${fileList.files.size} files in Google Drive folder")

                if (fileList.files.isEmpty()) {
                    Log.w(TAG, "No videos found in folder '$sharedFolderName'")
                    withContext(Dispatchers.Main) {
                        onFailed("No videos found in folder '$sharedFolderName'")
                    }
                    return@withContext
                }

                // Track files to download and files to keep
                val remoteFiles = fileList.files.associateBy { sanitizeFileName(it.name) }
                val filesToDownload = mutableListOf<com.google.api.services.drive.model.File>()
                val filesToKeep = mutableSetOf<String>()

                // Compare local and remote files
                Log.d(TAG, "Comparing local and remote files...")
                for (remoteFile in fileList.files) {
                    val sanitizedName = sanitizeFileName(remoteFile.name)
                    filesToKeep.add(sanitizedName)

                    val localFile = localFiles[sanitizedName]
                    if (localFile == null) {
                        Log.d(TAG, "New file found: ${remoteFile.name}")
                        filesToDownload.add(remoteFile)
                    } else {
                        // Check if file needs update (for now, we'll download if local file is smaller)
                        val localSize = localFile.length()
                        val remoteSize = remoteFile.size ?: 0

                        if (localSize < remoteSize) {
                            Log.d(TAG, "File needs update: ${remoteFile.name} (local: $localSize, remote: $remoteSize)")
                            filesToDownload.add(remoteFile)
                        } else {
                            Log.d(TAG, "File already up to date: ${remoteFile.name}")
                        }
                    }
                }

                // Find files to delete (local files not in Google Drive)
                val filesToDelete = localFiles.filter { (name, _) -> name !in filesToKeep }
                Log.d(TAG, "Files to download: ${filesToDownload.size}, Files to delete: ${filesToDelete.size}")

                // Delete files that are no longer in Google Drive
                filesToDelete.forEach { (name, file) ->
                    Log.d(TAG, "Deleting local file: $name")
                    val deleted = file.delete()
                    if (deleted) {
                        Log.d(TAG, "Successfully deleted: $name")
                    } else {
                        Log.w(TAG, "Failed to delete: $name")
                    }
                }

                // Download new and updated files
                if (filesToDownload.isNotEmpty()) {
                    Log.d(TAG, "Downloading ${filesToDownload.size} files")
                    filesToDownload.forEach { remoteFile ->
                        try {
                            Log.d(TAG, "Downloading: ${remoteFile.name}")
                            downloadFile(drive, remoteFile.id, remoteFile.name, documentFolder)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to download ${remoteFile.name}: ${e.message}")
                            // Continue with other files even if one fails
                        }
                    }
                } else {
                    Log.d(TAG, "No files need to be downloaded")
                }

                Log.d(TAG, "Sync completed successfully. Downloaded: ${filesToDownload.size}, Deleted: ${filesToDelete.size}")
                withContext(Dispatchers.Main) {
                    onComplete()
                }
            } catch (e: UserRecoverableAuthIOException) {
                Log.e(TAG, "Authentication error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onFailed("Authentication error: Please sign in again")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onFailed("Network error: ${e.message ?: "Unable to connect to Google Drive"}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onFailed("Sync error: ${e.message ?: "Unknown error occurred"}")
                }
            }
        }
    }

    private fun findFolderId(drive: Drive, folderPath: String): String? {
        Log.d(TAG, "findFolderId called with path: $folderPath")
        try {
            val folderNames = folderPath.split("/").filter { it.isNotBlank() }
            if (folderNames.isEmpty()) {
                Log.e(TAG, "Folder path is empty after filtering")
                return null
            }

            Log.d(TAG, "Processing folder names: $folderNames")

            // First, let's search for all folders with the first name to see what's available
            val firstFolderName = folderNames[0].trim()
            Log.d(TAG, "Searching broadly for folder: $firstFolderName")

            val broadQuery = "mimeType = 'application/vnd.google-apps.folder' and name = '$firstFolderName' and trashed = false"
            val broadSearch = drive.files().list()
                .setQ(broadQuery)
                .setFields("files(id, name, parents)")
                .setIncludeItemsFromAllDrives(true)
                .setSupportsAllDrives(true)
                .execute()

            Log.d(TAG, "Broad search found ${broadSearch.files.size} folders named '$firstFolderName'")
            broadSearch.files.forEach { file ->
                Log.d(TAG, " - ${file.name} (${file.id}), parents: ${file.parents}")
            }

            // If we only have one folder name, return the first match
            if (folderNames.size == 1) {
                return broadSearch.files.firstOrNull()?.id
            }

            // For multiple levels, try to find the complete path
            var currentParentId: String? = "root"
            var currentFolderId: String? = null

            for ((index, folderName) in folderNames.withIndex()) {
                val cleanFolderName = folderName.trim()
                if (cleanFolderName.isEmpty()) continue

                val query = if (currentParentId == "root") {
                    "mimeType = 'application/vnd.google-apps.folder' and name = '$cleanFolderName' and trashed = false"
                } else {
                    "mimeType = 'application/vnd.google-apps.folder' and name = '$cleanFolderName' and '$currentParentId' in parents and trashed = false"
                }

                Log.d(TAG, "Searching for folder '$cleanFolderName' (level ${index + 1}) with query: $query")

                val folderList = drive.files().list()
                    .setQ(query)
                    .setFields("files(id, name)")
                    .setIncludeItemsFromAllDrives(true)
                    .setSupportsAllDrives(true)
                    .execute()

                Log.d(TAG, "Found ${folderList.files.size} folders matching '$cleanFolderName'")

                folderList.files.forEach { file ->
                    Log.d(TAG, " - ${file.name} (${file.id})")
                }

                currentFolderId = folderList.files.firstOrNull()?.id
                if (currentFolderId == null) {
                    Log.e(TAG, "Folder '$cleanFolderName' not found in parent: $currentParentId")

                    // Let's try a different approach - search for any folder with this name and check if it has the next level
                    if (index < folderNames.size - 1) {
                        Log.d(TAG, "Trying alternative path finding for multi-level folder")
                        return findFolderByAlternativeMethod(drive, folderNames)
                    }
                    return null
                }

                Log.d(TAG, "Found folder '$cleanFolderName' with ID: $currentFolderId")
                currentParentId = currentFolderId
            }

            Log.d(TAG, "Final folder ID: $currentFolderId")
            return currentFolderId
        } catch (e: Exception) {
            Log.e(TAG, "Error in findFolderId for path $folderPath: ${e.message}", e)
            return null
        }
    }

    private fun findFolderByAlternativeMethod(drive: Drive, folderNames: List<String>): String? {
        Log.d(TAG, "Using alternative method to find folder path: $folderNames")

        // Find all folders with the first name
        val firstFolders = drive.files().list()
            .setQ("mimeType = 'application/vnd.google-apps.folder' and name = '${folderNames[0]}' and trashed = false")
            .setFields("files(id, name)")
            .setIncludeItemsFromAllDrives(true)
            .setSupportsAllDrives(true)
            .execute()

        Log.d(TAG, "Found ${firstFolders.files.size} potential root folders")

        // For each potential root folder, check if it contains the next level
        for (rootFolder in firstFolders.files) {
            Log.d(TAG, "Checking root folder: ${rootFolder.name} (${rootFolder.id})")

            var currentId = rootFolder.id
            for (i in 1 until folderNames.size) {
                val folderName = folderNames[i]
                val subFolders = drive.files().list()
                    .setQ("mimeType = 'application/vnd.google-apps.folder' and name = '$folderName' and '$currentId' in parents and trashed = false")
                    .setFields("files(id, name)")
                    .setIncludeItemsFromAllDrives(true)
                    .setSupportsAllDrives(true)
                    .execute()

                if (subFolders.files.isEmpty()) {
                    Log.d(TAG, "Folder '$folderName' not found in ${rootFolder.name}")
                    break
                }

                currentId = subFolders.files[0].id
                Log.d(TAG, "Found subfolder '$folderName' with ID: $currentId")

                // If we reached the last folder, return the ID
                if (i == folderNames.size - 1) {
                    Log.d(TAG, "Successfully found complete path using alternative method")
                    return currentId
                }
            }
        }

        Log.e(TAG, "Alternative method also failed to find folder path: $folderNames")
        return null
    }

    private fun downloadFile(drive: Drive, fileId: String, fileName: String, documentFolder: DocumentFile) {
        Log.d(TAG, "Downloading file: $fileName (ID: $fileId)")
        var outputStream: OutputStream? = null
        try {
            // Sanitize filename to remove invalid characters
            val sanitizedFileName = sanitizeFileName(fileName)
            if (sanitizedFileName != fileName) {
                Log.d(TAG, "Sanitized filename from '$fileName' to '$sanitizedFileName'")
            }

            // Check if file already exists and delete it (for update)
            val existingFile = documentFolder.findFile(sanitizedFileName)
            if (existingFile != null) {
                val deleted = existingFile.delete()
                Log.d(TAG, "Deleted existing file '$sanitizedFileName': $deleted")
            }

            // Try to create the file with retry mechanism
            Log.d(TAG, "Creating file: $sanitizedFileName")
            var outputFile: DocumentFile? = null
            var retryCount = 0
            val maxRetries = 3

            while (outputFile == null && retryCount < maxRetries) {
                outputFile = documentFolder.createFile("video/*", sanitizedFileName)
                if (outputFile == null) {
                    retryCount++
                    Log.w(TAG, "File creation failed, retry $retryCount for: $sanitizedFileName")
                    if (retryCount < maxRetries) {
                        Thread.sleep(100) // Small delay before retry
                    }
                }
            }

            outputFile ?: throw IllegalStateException("Failed to create file after $retryCount attempts: $sanitizedFileName")

            Log.d(TAG, "File created successfully, opening output stream")
            outputStream = context.contentResolver.openOutputStream(outputFile.uri)
                ?: throw IllegalStateException("Failed to open output stream for $sanitizedFileName")

            Log.d(TAG, "Starting download from Google Drive")
            drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            outputStream.close()
            outputStream = null

            Log.d(TAG, "Successfully downloaded: $sanitizedFileName")
        } catch (e: Exception) {
            outputStream?.close()
            Log.e(TAG, "Error downloading file $fileName: ${e.message}", e)
            throw e
        }
    }

    private fun sanitizeFileName(fileName: String): String {
        // Remove or replace invalid filename characters
        return fileName.replace(Regex("[<>:\"/\\\\|?*]"), "_")
            .replace(Regex("\\s+"), " ") // Replace multiple spaces with single space
            .trim() // Remove leading/trailing spaces
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}