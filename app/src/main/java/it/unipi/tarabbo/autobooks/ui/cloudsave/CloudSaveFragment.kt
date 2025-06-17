package it.unipi.tarabbo.autobooks.ui.cloudsave

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import it.unipi.tarabbo.autobooks.R
import it.unipi.tarabbo.autobooks.databinding.FragmentCloudsaveBinding
import kotlinx.coroutines.launch
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import java.io.File // Import File
import java.io.FileInputStream // Import FileInputStream
import java.io.FileOutputStream // Import FileOutputStream
import com.google.api.client.http.FileContent // Import FileContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.api.services.drive.model.File as DriveFile // Alias File to avoid conflict
import kotlinx.coroutines.CancellationException


class CloudSaveFragment : Fragment() {

    private var _binding: FragmentCloudsaveBinding? = null
    private val binding get() = _binding!!

    private lateinit var credentialManager: CredentialManager
    private var googleAccountCredential: GoogleAccountCredential? = null
    private var driveService: Drive? = null

    // Database file name
    private val DATABASE_NAME = "AutoBooksDB.db" // Make sure this matches your DatabaseHelper.DATABASE_NAME

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCloudsaveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        credentialManager = CredentialManager.create(requireContext())

        // Set up click listeners
        binding.loginButton.setOnClickListener {
            lifecycleScope.launch {
                try {
                    //method that initiates the full Google Sign-In flow
                    signInWithGoogle()
                } catch (e: CancellationException) {
                    // This means the user cancelled the sign-in prompt
                    Log.d("CloudSaveFragment", "Login operation cancelled by user: ${e.message}")
                } catch (e: Exception) {
                    Log.e("CloudSaveFragment", "loginBUTTON : Error signing in: ${e.message}", e)
                    Toast.makeText(requireContext(), "Sign-in operation failed.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.logoutButton.setOnClickListener {
            lifecycleScope.launch {
                try{
                    signOut()
                }catch(e : Exception){
                    Log.e("CloudSaveFragment", "Error signing out: ${e.message}")
                }
            }
        }
        binding.uploadButton.setOnClickListener {
            lifecycleScope.launch {
                try{
                    uploadDatabaseToDrive()
                }catch(e : Exception){
                    Log.e("CloudSaveFragment", "Error uploading database: ${e.message}")
                }
            }
        }
        binding.downloadButton.setOnClickListener {
            lifecycleScope.launch {
                try{
                    downloadDatabaseFromDrive()
                }catch(e : Exception){
                    Log.e("CloudSaveFragment", "Error downloading database: ${e.message}")
                }
            }
        }

        // Check for existing Google Sign-In account (Credential Manager handles this implicitly on request)
        lifecycleScope.launch {
            trySignInSilently()
        }
    }

    private fun signInWithGoogle() {
        lifecycleScope.launch {
            val googleIdOption: GetGoogleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(getString(R.string.default_web_client_id))
                .setFilterByAuthorizedAccounts(false) // IMPORTANT: Allow user to pick any Google account
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            try {
                val result = credentialManager.getCredential(
                    request = request,
                    context = requireContext(),
                )
                handleSignInResult(result)
            } catch (e: GetCredentialException) {
                Log.e("CloudSaveFragment", "SignInWithGoogle : Explicit sign-in failed: ${e.message}")
                if (e !is CancellationException) {
                    Toast.makeText(requireContext(), "SignInWithGoogle : Sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                updateUI(null, null, null)
            } catch (e: CancellationException) {
                Log.d("CloudSaveFragment", "SignInWithGoogle : Explicit sign-in Job cancelled.")
            } catch (e: Exception) {
                Log.e("CloudSaveFragment", "SignInWithGoogle : General explicit sign-in error: ${e.message}", e)
                if (e !is CancellationException) {
                    Toast.makeText(requireContext(), "SignInWithGoogle : Sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                updateUI(null, null, null)
            }
        }
    }

    private fun trySignInSilently() {
        lifecycleScope.launch {
            val googleIdOption: GetGoogleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(getString(R.string.default_web_client_id))
                .setFilterByAuthorizedAccounts(true)
                .setAutoSelectEnabled(true)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            try {
                val result = credentialManager.getCredential(
                    request = request,
                    context = requireContext(),
                )
                handleSignInResult(result)
            } catch (e: GetCredentialException) {
                Log.d("CloudSaveFragment", "Silent sign-in failed: ${e.message}. User not automatically signed in.")
                updateUI(null, null, null)
            } catch (e: Exception) {
                Log.e("CloudSaveFragment", "General silent sign-in error: ${e.message}", e)
                updateUI(null, null, null)
            }
        }
    }

    private fun handleSignInResult(result: GetCredentialResponse) {
        // Crucial safety check: Ensure view is still alive and binding is not null
       if (_binding == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            Log.d("CloudSaveFragment", "handleSignInResult called but fragment view not in STARTED state. Skipping UI update.")
            return
        }
        when (val credential = result.credential) {
            is GoogleIdTokenCredential -> {
                Log.d("CloudSaveFragment", "Credential Type: GoogleIdTokenCredential")
                val idToken = credential.idToken
                val email = credential.id
                val displayName = credential.displayName
                val profilePictureUri = credential.profilePictureUri

                Log.d("CloudSaveFragment", "Signed In: Email: $email, Name: $displayName, Photo: $profilePictureUri, ID Token: $idToken")

                val account : Account? = email?.let { Account(it , "com.google") }

                if(account != null){
                    // Initialize GoogleAccountCredential and Drive service
                    googleAccountCredential = GoogleAccountCredential.usingOAuth2(
                        requireContext(), listOf(DriveScopes.DRIVE_APPDATA)
                    ).apply {
                        selectedAccount = account // Use the account from the credential
                    }
                } else {
                    // Handle the case where email is null
                    Log.e("CloudSaveFragment", "Email (credential.id) is null, cannot initialize Drive service.")
                    Toast.makeText(requireContext(), "Sign-in successful but account email not available for Drive access.", Toast.LENGTH_LONG).show()
                }



                driveService = Drive.Builder(
                    AndroidHttp.newCompatibleTransport(),
                    GsonFactory.getDefaultInstance(),
                    googleAccountCredential
                ).setApplicationName(getString(R.string.app_name)).build()

                updateUI(email, displayName, profilePictureUri)
                Toast.makeText(requireContext(), "Signed in as: ${displayName ?: email}", Toast.LENGTH_SHORT).show()
            }
            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    handleSignInResult(GetCredentialResponse(googleIdTokenCredential))
                } else {
                    Log.w("CloudSaveFragment", "Unhandled custom credential type: ${credential.type}")
                    Toast.makeText(requireContext(), "Unhandled credential type: ${credential.type}", Toast.LENGTH_LONG).show()
                    updateUI(null, null, null)
                }
            }
            else -> {
                Log.w("CloudSaveFragment", "Unhandled credential type: ${credential.type}")
                Toast.makeText(requireContext(), "Unhandled credential type.", Toast.LENGTH_LONG).show()
                updateUI(null, null, null)
            }
        }
    }

    private fun signOut() {
        lifecycleScope.launch {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
                googleAccountCredential = null // Clear credential
                driveService = null // Clear Drive service
                updateUI(null, null, null)
                Toast.makeText(requireContext(), "Logged out successfully.", Toast.LENGTH_SHORT).show()
                Log.d("CloudSaveFragment", "Sign out successful.")
            } catch (e: ClearCredentialException) {
                Log.e("CloudSaveFragment", "Sign-out failed: ${e.message}")
                Toast.makeText(requireContext(), "Sign-out failed.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("CloudSaveFragment", "General sign-out error: ${e.message}", e)
                Toast.makeText(requireContext(), "Sign-out failed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Google Drive Upload/Download Functionality ---

    private fun getDatabaseFile(): File {
        // Returns the path to your database file
        return requireContext().getDatabasePath(DATABASE_NAME)
    }

    private fun uploadDatabaseToDrive() {
        if (driveService == null) {
            Toast.makeText(requireContext(), "Not signed in to Google Drive.", Toast.LENGTH_SHORT).show()
            return;
        }

        binding.uploadButton.isEnabled = false;
        binding.uploadButton.text = getString(R.string.uploading);

        lifecycleScope.launch {
            try {
                val dbFile = getDatabaseFile();
                if (!dbFile.exists()) {
                    Toast.makeText(requireContext(), "Local database file not found!", Toast.LENGTH_LONG).show();
                    Log.e("CloudSaveFragment", "Local database file not found at: ${dbFile.absolutePath}");
                    return@launch;
                }

                // Move blocking operations to Dispatchers.IO
                val uploadedFile: DriveFile? = withContext(Dispatchers.IO) {
                    // Check if the file already exists in Drive's app folder
                    val files = driveService?.files()?.list()
                        ?.setQ("'appDataFolder' in parents and name = '$DATABASE_NAME'")
                        ?.setSpaces("appDataFolder")
                        ?.setFields("files(id, name, modifiedTime)")
                        ?.execute();

                    val existingFile: DriveFile? = files?.files?.firstOrNull();

                    val fileContent = FileContent("application/x-sqlite3", dbFile);

                    val driveFileMetadata = DriveFile().setName(DATABASE_NAME)
                        .setParents(listOf("appDataFolder"));

                    if (existingFile != null) {
                        // Update existing file
                        Log.d("CloudSaveFragment", "Updating existing file in Drive: ${existingFile.name} (ID: ${existingFile.id})");
                        driveService?.files()?.update(existingFile.id, driveFileMetadata, fileContent)?.execute();
                    } else {
                        // Create new file
                        Log.d("CloudSaveFragment", "Creating new file in Drive: $DATABASE_NAME");
                        driveService?.files()?.create(driveFileMetadata, fileContent)?.execute();
                    }
                }

                if (uploadedFile != null) {
                    Toast.makeText(requireContext(), "Database uploaded successfully!", Toast.LENGTH_SHORT).show();
                    Log.d("CloudSaveFragment", "Upload complete! File ID: ${uploadedFile.id}");
                } else {
                    Toast.makeText(requireContext(), "Failed to upload database.", Toast.LENGTH_LONG).show();
                    Log.e("CloudSaveFragment", "Upload failed, uploadedFile is null.");
                }

            } catch (e: UserRecoverableAuthIOException) {
                // Start the intent to recover from the authentication error
                startActivityForResult(e.intent, REQUEST_AUTHORIZATION); // Define REQUEST_AUTHORIZATION
                Log.e("CloudSaveFragment", "UserRecoverableAuthIOException: ${e.message}", e);
            } catch (e: Exception) {
                Log.e("CloudSaveFragment", "Error uploading database: ${e.message}", e);
                Toast.makeText(requireContext(), "Upload failed: ${e.message}", Toast.LENGTH_LONG).show();
            } finally {
                binding.uploadButton.isEnabled = true;
                binding.uploadButton.text = getString(R.string.upload_cloud_save);
            }
        }
    }

    // Add this constant to your CloudSaveFragment class
    private val REQUEST_AUTHORIZATION = 1001;

    // Override onActivityResult to handle the result of the authorization intent
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_AUTHORIZATION) {
            if (resultCode == Activity.RESULT_OK) {
                // User granted permissions, try the upload again
                uploadDatabaseToDrive(); // Or call the download function if it was download that failed
            } else {
                // User denied permissions or an error occurred
                Toast.makeText(requireContext(), "Authorization failed.", Toast.LENGTH_LONG).show();
                Log.e("CloudSaveFragment", "User authorization failed.");
            }
        }
    }

    private fun downloadDatabaseFromDrive() {
        if (driveService == null) {
            Toast.makeText(requireContext(), "Not signed in to Google Drive.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.downloadButton.isEnabled = false // Disable button during operation
        binding.downloadButton.text = getString(R.string.downloading)

        lifecycleScope.launch {
            try {
               val success = withContext(Dispatchers.IO){
                   // Find the database file in Drive's app folder
                   val files = driveService?.files()?.list()
                       ?.setQ("'appDataFolder' in parents and name = '$DATABASE_NAME'")
                       ?.setSpaces("appDataFolder")
                       ?.setFields("files(id, name, modifiedTime)")
                       ?.execute()

                   val driveFile: DriveFile? = files?.files?.firstOrNull()

                   if (driveFile == null) {
                       Toast.makeText(requireContext(), "No database backup found in Drive.", Toast.LENGTH_LONG).show()
                       Log.d("CloudSaveFragment", "No backup found for $DATABASE_NAME in appDataFolder.")
                       return@withContext false
                   }

                   Log.d("CloudSaveFragment", "Found backup file in Drive: ${driveFile.name} (ID: ${driveFile.id})")

                   // Download the file content
                   val outputStream = FileOutputStream(getDatabaseFile())
                   driveService?.files()?.get(driveFile.id)?.executeMediaAndDownloadTo(outputStream)
                   outputStream.close()
                   return@withContext true
               }
                if (success) {
                    Toast.makeText(requireContext(), "Database downloaded successfully!", Toast.LENGTH_LONG).show()
                    Log.d("CloudSaveFragment", "Download complete! Database saved to ${getDatabaseFile().absolutePath}")
                } else {
                    Toast.makeText(requireContext(), "No database backup found in Drive.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("CloudSaveFragment", "Error downloading database: ${e.message}", e)
                Toast.makeText(requireContext(), "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.downloadButton.isEnabled = true
                binding.downloadButton.text = getString(R.string.download_cloud_save)
            }
        }
    }


    private fun updateUI(email: String?, displayName: String?, photoUri: Uri?) {
        // Crucial safety check: Ensure view is still alive and binding is not null
        if (_binding == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            Log.d("CloudSaveFragment", "Attempted to update UI when view is not available.")
            return
        }
        if (email != null) {
            binding.userEmail.text = displayName ?: email
            binding.loginButton.visibility = View.GONE
            binding.logoutButton.visibility = View.VISIBLE
            binding.uploadButton.visibility = View.VISIBLE
            binding.downloadButton.visibility = View.VISIBLE

            photoUri?.let { uri ->
                Glide.with(this)
                    .load(uri)
                    .placeholder(R.drawable.user)
                    .error(R.drawable.user)
                    .circleCrop()
                    .into(binding.profileImage)
            } ?: run {
                binding.profileImage.setImageResource(R.drawable.user)
            }

        } else {
            binding.userEmail.text = getString(R.string.not_logged_in)
            binding.loginButton.visibility = View.VISIBLE
            binding.logoutButton.visibility = View.GONE
            binding.uploadButton.visibility = View.GONE
            binding.downloadButton.visibility = View.GONE
            binding.profileImage.setImageResource(R.drawable.user)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d("CloudSaveFragment", "onDestroyView called, setting _binding to null.")
        _binding = null
    }
}