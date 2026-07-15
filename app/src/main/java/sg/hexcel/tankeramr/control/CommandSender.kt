package sg.hexcel.tankeramr.control

import android.content.Context

interface CommandSender {
    val name: String
    fun start(context: Context, onStatus: (String) -> Unit)
    fun send(text: String)
    fun close()
}
