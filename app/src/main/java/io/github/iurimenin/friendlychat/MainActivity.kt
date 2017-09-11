package io.github.iurimenin.friendlychat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import com.bumptech.glide.Glide
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.database.FirebaseListAdapter
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuth.AuthStateListener
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.item_message.view.*
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.debug
import org.jetbrains.anko.toast
import org.jetbrains.anko.warn
import java.util.*
import kotlin.collections.HashMap


/**
 * Created by Iuri Menin on 20/06/17.
 */
class MainActivity : AppCompatActivity(), AnkoLogger {

    // Choose an arbitrary request code value
    private val RC_SIGN_IN = 1
    private val RC_PHOTO_PICKER = 2

    val ANONYMOUS = "anonymous"
    val DEFAULT_MSG_LENGTH_LIMIT = 1000
    val FRIENDLY_MSG_LENGTH_KEY: String = "friendly_msg_length"

    private var mUsername: String = ANONYMOUS

    private var mMessageAdapter: FirebaseListAdapter<FriendlyMessage>? = null

    //Firebase instance variables
    private var mFirebaseDatabase: FirebaseDatabase? = null
    private var mMessagesDatabaseReference: DatabaseReference? = null
    private var mChildEventListner : ChildEventListener? = null
    private var mFirebaseAuth : FirebaseAuth? = null
    private var mAuthStateListener : AuthStateListener? = null
    private var mFirebaseStorage : FirebaseStorage? = null
    private var mChatPhotosStorageReference : StorageReference? = null
    private var mFirebaseRemoteConfig : FirebaseRemoteConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //Initialize Firebase components
        mFirebaseDatabase = FirebaseDatabase.getInstance()
        mFirebaseAuth = FirebaseAuth.getInstance()
        mFirebaseStorage = FirebaseStorage.getInstance()
        mFirebaseRemoteConfig = FirebaseRemoteConfig.getInstance()

        mMessagesDatabaseReference = mFirebaseDatabase?.reference?.child("messages")
        mChatPhotosStorageReference = mFirebaseStorage?.reference?.child("chat_photos")

        // Initialize message ListView and its adapter
        mMessageAdapter = object : FirebaseListAdapter<FriendlyMessage>(this,
                FriendlyMessage::class.java,
                R.layout.item_message,
                mMessagesDatabaseReference) {

            override fun populateView(view: View, chatMessage: FriendlyMessage, position: Int) {

                val isPhoto = !chatMessage.photoUrl.isNullOrBlank()
                if (isPhoto) {
                    view.messageTextView.visibility = View.GONE
                    view.photoImageView.visibility = View.VISIBLE
                    Glide.with(view.photoImageView.context)
                            .load(chatMessage.photoUrl)
                            .into(view.photoImageView)
                } else {
                    view.messageTextView.visibility = View.VISIBLE
                    view.photoImageView.visibility = View.GONE
                    view.messageTextView.text = chatMessage.text
                }
                view.nameTextView.text = chatMessage.name
            }
        }
        messageListView.adapter = mMessageAdapter

        // Initialize progress bar
        progressBar.visibility = ProgressBar.INVISIBLE

        // ImagePickerButton shows an image picker to upload a image for a message
        photoPickerButton?.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/jpeg"
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            startActivityForResult(Intent.createChooser(intent,
                    "Complete action using"), RC_PHOTO_PICKER)
        }

        // Enable Send button when there's text to send
        messageEditText!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}

            override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {
                sendButton.isEnabled = charSequence.isNotEmpty()
            }

            override fun afterTextChanged(editable: Editable) {}
        })
        messageEditText.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(DEFAULT_MSG_LENGTH_LIMIT))

        // Send button sends a message and clears the EditText
        sendButton.setOnClickListener {

            val key = mMessagesDatabaseReference?.push()?.key
            key?.let { it1 ->
                val friendlyMessage = FriendlyMessage(it1, messageEditText.text.toString(),
                        mUsername, null)

                mMessagesDatabaseReference?.child(it1)?.setValue(friendlyMessage)
            }

            // Clear input box
            messageEditText!!.setText("")
        }

        mAuthStateListener = AuthStateListener({

            val user = it.currentUser

            if (user != null) {
                // user is signed in
                user.displayName?.let { it1 -> onSignedInInitialize(it1) }
            } else {
                // user is signed out
                onSignedOutCleanup()
                startActivityForResult(
                        AuthUI.getInstance()
                                .createSignInIntentBuilder()
                                .setAvailableProviders(
                                        Arrays.asList(
                                                AuthUI.IdpConfig.Builder(AuthUI.EMAIL_PROVIDER).build(),
                                                AuthUI.IdpConfig.Builder(AuthUI.GOOGLE_PROVIDER).build()
                                        )
                                )
                                .build(),
                        RC_SIGN_IN)
            }
        })

        val configSettings = FirebaseRemoteConfigSettings.Builder()
                .setDeveloperModeEnabled(BuildConfig.DEBUG)
                .build()

        mFirebaseRemoteConfig?.setConfigSettings(configSettings)

        val defaultConfigMap : HashMap<String, Any> = HashMap()
        defaultConfigMap.put(FRIENDLY_MSG_LENGTH_KEY, DEFAULT_MSG_LENGTH_LIMIT)

        mFirebaseRemoteConfig?.setDefaults(defaultConfigMap)
        fetchConfig()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        when (item.itemId) {

            R.id.sign_out_menu -> signOut()
        }
        return super.onOptionsItemSelected(item)
    }

    private fun signOut() {
        AuthUI.getInstance().signOut(this)
    }

    override fun onResume() {
        super.onResume()
        mAuthStateListener?.let { mFirebaseAuth?.addAuthStateListener(it) }
    }

    override fun onPause() {super.onPause()
        mAuthStateListener?.let { mFirebaseAuth?.removeAuthStateListener(it) }
        detachDatabaseReadListener()
        mMessageAdapter?.cleanup()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            if (resultCode == RESULT_OK) {
                toast(R.string.signed_in)
            } else if (resultCode == RESULT_CANCELED) {
                toast(R.string.sign_in_canceled)
                finish()
            }
        } else if (requestCode == RC_PHOTO_PICKER && resultCode == RESULT_OK) {
            if (data?.data != null) {
                val selectedImageUri : Uri = data?.data
                val photoRef: StorageReference =
                        mChatPhotosStorageReference?.child(selectedImageUri.lastPathSegment)!!

                photoRef.putFile(selectedImageUri).addOnSuccessListener {
                    it.downloadUrl?.let {
                        val downloadUrl: Uri = it

                        val key = mMessagesDatabaseReference?.push()?.key
                        val friendlyMessage =
                                FriendlyMessage(key!!, null, mUsername, downloadUrl.toString())

                        mMessagesDatabaseReference?.child(key!!)?.setValue(friendlyMessage)
                    }
                }
            }
        }
    }

    private fun onSignedInInitialize(username: String) {
        mUsername = username
    }

    private fun onSignedOutCleanup() {
        mUsername = ANONYMOUS
        mMessageAdapter?.cleanup()
        detachDatabaseReadListener()
    }

    private fun detachDatabaseReadListener() {
        mChildEventListner?.let {
            mMessagesDatabaseReference?.removeEventListener(mChildEventListner)
            mChildEventListner = null
        }
    }

    private fun fetchConfig() {

        var cacheExpiration : Long = 3600

        if (mFirebaseRemoteConfig?.info?.configSettings?.isDeveloperModeEnabled == true)
            cacheExpiration = 0

        mFirebaseRemoteConfig?.fetch(cacheExpiration)
                ?.addOnSuccessListener {
                    mFirebaseRemoteConfig?.activateFetched()
                    applyRetrievedLengthLimit()
                }
                ?.addOnFailureListener {

                    warn("Error fetching config", it)
                    applyRetrievedLengthLimit()
                }
    }

    private fun applyRetrievedLengthLimit() {

        val friendly_msg_length = mFirebaseRemoteConfig?.getLong(FRIENDLY_MSG_LENGTH_KEY)
        friendly_msg_length?.let {
            messageEditText.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(it.toInt()))
            debug { FRIENDLY_MSG_LENGTH_KEY + " - " + friendly_msg_length }
        }
    }
}
