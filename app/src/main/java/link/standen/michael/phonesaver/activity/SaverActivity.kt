package link.standen.michael.phonesaver.activity

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.util.Log
import link.standen.michael.phonesaver.R
import link.standen.michael.phonesaver.util.LocationHelper
import android.provider.OpenableColumns
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.*
import java.io.*
import java.net.MalformedURLException
import java.net.URL
import java.util.regex.Pattern

/**
 * An activity to handle saving files.
 * https://developer.android.com/training/sharing/receive.html
 */
class SaverActivity : ListActivity() {

	private val TAG = "SaverActivity"

	private val FILENAME_REGEX = "[^a-zA-Z0-9æøåÆØÅ_ -]"
	private val FILENAME_LENGTH_LIMIT = 100

	private var location: String? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.saver_activity)

		if (testSupported()) {
			LocationHelper.loadFolderList(this)?.let {
				if (it.size > 1) {
					// Init list view
					val listView = findViewById(android.R.id.list) as ListView
					listView.onItemClickListener = AdapterView.OnItemClickListener { _, view, _, _ ->
						location = LocationHelper.addRoot((view as TextView).text.toString())
						useIntent()
					}
					listView.adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, it)
					return // await selection
				} else if (it.size == 1) {
					// Only one location, just use it
					location = LocationHelper.addRoot(it[0])
					useIntent()
					return // activity dead
				} else {
					Toast.makeText(this, R.string.toast_save_init_no_locations, Toast.LENGTH_LONG).show()
					exitApplication()
					return // activity dead
				}
			}

			Toast.makeText(this, R.string.toast_save_init_error, Toast.LENGTH_LONG).show()
		} else {
			showNotSupported()
		}
	}

	fun testSupported(): Boolean {
		// Get intent, action and MIME type
		val action: String? = intent.action
		val type: String? = intent.type
		Log.d(TAG, "Action: $action")
		Log.d(TAG, "Type: $type")

		type?.let {
			if (Intent.ACTION_SEND == action) {
				if (type.startsWith("image/") || type.startsWith("video/") || type == "text/plain") {
					return true
				}
			} else if (Intent.ACTION_SEND_MULTIPLE == action) {
				if (type.startsWith("image/")) {
					return true
				}
			}
		}
		return false
	}

	fun useIntent() {
		// Get intent, action and MIME type
		val action: String? = intent.action
		val type: String? = intent.type

		var done = false

		type?.let {
			if (Intent.ACTION_SEND == action) {
				if (type.startsWith("image/") || type.startsWith("video/")) {
					// Handle single image/video being sent
					done = handleImageVideo()
				} else if (type == "text/plain") {
					handleText()
					return // HandleText is async
				}
			} else if (Intent.ACTION_SEND_MULTIPLE == action) {
				if (type.startsWith("image/")) {
					// Handle multiple images being sent
					done = handleMultipleImages()
				}
			} else {
				// Handle other intents, such as being started from the home screen
			}
		}

		finishIntent(done)
	}

	/**
	 * Show the not supported information.
	 */
	fun showNotSupported() {
		// Hide list
		findViewById(android.R.id.list).visibility = View.GONE
		// Build and show unsupported message
		val supportView = findViewById(R.id.not_supported2) as TextView
		// Generate issue text here as should always be English and does not need to be in strings.xml
		val bob = StringBuilder()
		bob.append("https://github.com/ScreamingHawk/phone-saver/issues/new?title=")
		bob.append("Support Request - ")
		bob.append(intent.type)
		bob.append("&body=")
		bob.append("Support request. Generated by Phone Saver.%0D%0A")
		bob.append("%0D%0AIntent type: ")
		bob.append(intent.type)
		bob.append("%0D%0AIntent action: ")
		bob.append(intent.action)
		intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
			bob.append("%0D%0AText: ")
			bob.append(it)
		}
		intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let {
			bob.append("%0D%0ASubject: ")
			bob.append(it)
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
			intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let {
                bob.append("%0D%0AHTML Text: ")
                bob.append(it)
            }
		}
		// Version
		try {
			val versionName = packageManager.getPackageInfo(packageName, 0).versionName
			bob.append("%0D%0AApplication Version: ")
			bob.append(versionName)
		} catch (e: PackageManager.NameNotFoundException) {
			Log.e(TAG, "Unable to get package version", e)
		}
		bob.append("%0D%0A%0D%0AMore information: TYPE_ADDITIONAL_INFORMATION_HERE")
		bob.append("%0D%0A%0D%0AThank you")
		val issueLink = bob.toString().replace(" ", "%20")

		supportView.text = Html.fromHtml(resources.getString(R.string.not_supported2, issueLink))
		supportView.movementMethod = LinkMovementMethod.getInstance()
		findViewById(R.id.not_supported).visibility = View.VISIBLE
	}

	/**
	 * Call when the intent is finished
	 */
	fun finishIntent(success: Boolean?) {
		// Notify user
		if (success == null){
			Toast.makeText(this, R.string.toast_save_in_progress, Toast.LENGTH_SHORT).show()
		} else if (success){
			Toast.makeText(this, R.string.toast_save_successful, Toast.LENGTH_SHORT).show()
		} else {
			Toast.makeText(this, R.string.toast_save_failed, Toast.LENGTH_SHORT).show()
		}

		exitApplication()
	}

	/**
	 * Exists the application is the best way available for the Android version
	 */
	fun exitApplication() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			finishAndRemoveTask()
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
			finishAffinity()
		} else {
			finish()
		}
	}

	fun handleImageVideo(): Boolean {
		intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let {
			return saveUri(it, getFilename(it))
		}
		return false
	}

	fun handleText() {
		intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
			object: AsyncTask<Unit, Unit, Unit>(){
				var success: Boolean? = false

				override fun doInBackground(vararg params: Unit?) {
					try {
						// It's a URL
						val url = URL(it)
						val connection = url.openConnection()
						val contentType = connection.getHeaderField("Content-Type")
						Log.d(TAG, "ContentType: $contentType")
						val filename = getFilename(intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: Uri.parse(it).lastPathSegment)
						if (contentType.startsWith("image/") || contentType.startsWith("video/")) {
							success = saveUrl(Uri.parse(it), filename)
						}
					} catch (e: MalformedURLException){
						// It's just some text
						val filename = getFilename(intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: it)
						success = saveString(it, filename)
					}
				}

				override fun onPostExecute(result: Unit?) {
					super.onPostExecute(result)
					finishIntent(success)
				}
			}.execute()
		}
	}

	fun handleMultipleImages(): Boolean {
		val imageUris: ArrayList<Uri>? = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
		imageUris?.let {
			var success = true
			imageUris.forEach {
				success = success && saveUri(it, getFilename(it))
			}
			return success
		}
		return false
	}

	/**
	 * Save the given uri to file
	 */
	fun saveUri(uri: Uri, filename: String): Boolean {
		var success = false

		val sourceFilename = uri.path
		val destinationFilename = safeAddPath(filename)

		Log.d(TAG, "Saving $sourceFilename to $destinationFilename")

		contentResolver.openInputStream(uri)?.use { bis ->
			success = saveStream(bis, destinationFilename)
		}

		return success
	}

	/**
	 * Save the given url to file
	 */
	fun saveUrl(uri: Uri, filename: String): Boolean? {
		var success: Boolean? = false

		location?.let {
			val sourceFilename = uri.toString()
			val destinationFilename = safeAddPath(filename)

			Log.d(TAG, "Saving $sourceFilename to $destinationFilename")

			Log.d(TAG, "remove root: "+LocationHelper.removeRoot(it))

			val fout = File(destinationFilename)
			if (!fout.exists()){
				fout.createNewFile()
			}

			val downloader = DownloadManager.Request(uri)
			downloader.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_MOBILE or DownloadManager.Request.NETWORK_WIFI)
					.setAllowedOverRoaming(true)
					.setTitle(filename)
					.setDescription(resources.getString(R.string.downloader_description, sourceFilename))
					.setDestinationInExternalPublicDir(LocationHelper.removeRoot(it), filename)

			(getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(downloader)

			success = null
		}

		return success
	}

	private fun saveStream(bis: InputStream, destinationFilename: String): Boolean {
		var success = false
		var bos: OutputStream? = null
		try {
			val fout = File(destinationFilename)
			if (!fout.exists()){
				fout.createNewFile()
			}
			bos = BufferedOutputStream(FileOutputStream(fout, false))
			val buf = ByteArray(1024)
			bis.read(buf)
			do {
				bos.write(buf)
			} while (bis.read(buf) != -1)

			// Done
			success = true
		} catch (e: IOException) {
			Log.e(TAG, "Unable to save file", e)
		} finally {
			try {
				bos?.close()
			} catch (e: IOException) {
				Log.e(TAG, "Unable to close stream", e)
			}
		}
		return success
	}

	private fun saveString(s: String, filename: String): Boolean {
		val destinationFilename = safeAddPath(filename)
		var success = false
		var bw: BufferedWriter? = null

		try {
			val fout = File(destinationFilename)
			if (!fout.exists()){
				fout.createNewFile()
			}
			bw = BufferedWriter(FileWriter(destinationFilename))
			bw.write(s)

			// Done
			success = true
		} catch (e: IOException) {
			Log.e(TAG, "Unable to save file", e)
		} finally {
			try {
				bw?.close()
			} catch (e: IOException) {
				Log.e(TAG, "Unable to close stream", e)
			}
		}
		return success
	}

	private fun getFilename(uri: Uri): String {
		// Find the actual filename
		if (uri.scheme == "content") {
			contentResolver.query(uri, null, null, null, null)?.use {
				if (it.moveToFirst()) {
					return getFilename(it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME)))
				}
			}
		}
		return getFilename(uri.lastPathSegment)
	}

	private fun getFilename(s: String): String {
		// Default to last path if null
		var result: String = s

		Log.d(TAG, "Converting filename: $result")

		// Do some validation

		result = result
				// Take last section after a slash
				.replaceBeforeLast("/", "")
				// Take first section before a space
				.replaceAfter(" ", "")
				// Remove non-filename characters
				.replace(Regex(FILENAME_REGEX), "")
		if (result.length > FILENAME_LENGTH_LIMIT) {
			// Do not go over the filename length limit
			result = result.substring(0, FILENAME_LENGTH_LIMIT)
		}

		Log.d(TAG, "Converted filename: $result")

		return result
	}

	private fun safeAddPath(filename: String): String {
		location?.let {
			if (!filename.startsWith(it)){
				return it + File.separatorChar + filename
			}
		}
		return filename
	}
}
