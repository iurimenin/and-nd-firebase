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
import android.widget.ProgressBar
import com.firebase.ui.auth.AuthUI
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuth.AuthStateListener
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.anko.toast
import java.util.*

/**
 * Created by Iuri Menin on 20/06/17.
 */
class MainActivity : AppCompatActivity() {

    val TAG = "MainActivity"

    // Choose an arbitrary request code value
    private val RC_SIGN_IN = 1
    private val RC_PHOTO_PICKER = 2

    val ANONYMOUS = "anonymous"
    val DEFAULT_MSG_LENGTH_LIMIT = 1000

    private var mUsername: String = ANONYMOUS

    private var mMessageAdapter: MessageAdapter? = null

    //Firebase instance variables
    private var mFirebaseDatabase: FirebaseDatabase? = null
    private var mMessagesDatabaseReference: DatabaseReference? = null
    private var mChildEventListner : ChildEventListener? = null
    private var mFirebaseAuth : FirebaseAuth? = null
    private var mAuthStateListener : AuthStateListener? = null
    private var mFirebaseStorage : FirebaseStorage? = null
    private var mChatPhotosStorageReference : StorageReference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //Initialize Firebase components
        mFirebaseDatabase = FirebaseDatabase.getInstance()
        mFirebaseAuth = FirebaseAuth.getInstance()
        mFirebaseStorage = FirebaseStorage.getInstance()

        mMessagesDatabaseReference = mFirebaseDatabase?.getReference()?.child("messages")
        mChatPhotosStorageReference = mFirebaseStorage?.getReference()?.child("chat_photos")

        // Initialize message ListView and its adapter
        val friendlyMessages = ArrayList<FriendlyMessage>()
        mMessageAdapter = MessageAdapter(this, R.layout.item_message, friendlyMessages)
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

            val friendlyMessage = FriendlyMessage(messageEditText.text.toString(),
                    mUsername, null)

            mMessagesDatabaseReference?.push()?.setValue(friendlyMessage)

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
        mMessageAdapter?.clear()
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
            data?.data?.let {
                val selectedImageUri : Uri = it
                val photoRef: StorageReference =
                        mChatPhotosStorageReference?.child(selectedImageUri.lastPathSegment)!!

                photoRef.putFile(selectedImageUri).addOnSuccessListener {
                    it.downloadUrl?.let {
                        val downloadUrl: Uri = it

                        val friendlyMessage =
                                FriendlyMessage(null, mUsername, downloadUrl.toString())

                        mMessagesDatabaseReference?.push()?.setValue(friendlyMessage)
                    }
                }
            }
        }
    }

    private fun onSignedInInitialize(username: String) {
        mUsername = username
        attachDatabaseReadListener()
    }

    private fun attachDatabaseReadListener() {

        if(mChildEventListner == null) {
            mChildEventListner = object : ChildEventListener {

                override fun onChildAdded(dataSnapshot: DataSnapshot, s: String?) {
                    val friendlyMessage = dataSnapshot.getValue(FriendlyMessage::class.java)
                    mMessageAdapter?.add(friendlyMessage)
                }

                override fun onChildChanged(dataSnapshot: DataSnapshot, s: String?) {}

                override fun onChildRemoved(dataSnapshot: DataSnapshot) {}

                override fun onChildMoved(dataSnapshot: DataSnapshot, s: String?) {}

                override fun onCancelled(databaseError: DatabaseError) {}
            }

            mMessagesDatabaseReference?.addChildEventListener(mChildEventListner)
        }
    }

    private fun onSignedOutCleanup() {
        mUsername = ANONYMOUS
        mMessageAdapter?.clear()
        detachDatabaseReadListener()
    }

    private fun detachDatabaseReadListener() {
        mChildEventListner?.let {
            mMessagesDatabaseReference?.removeEventListener(mChildEventListner)
            mChildEventListner = null
        }
    }
}
