package io.github.iurimenin.friendlychat

/**
 * Created by Iuri Menin on 20/06/17.
 */
data class FriendlyMessage(var uid: String,
                           var text: String?,
                           var name: String,
                           var photoUrl: String?) {

    constructor() : this("", "", "", "")
}
