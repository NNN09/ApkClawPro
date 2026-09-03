package com.apk.claw.android.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apk.claw.android.R
import com.apk.claw.android.agent.store.InAppChatStore
import com.apk.claw.android.appViewModel
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.widget.CommonToolbar

/**
 * F5：App 内对话页。用户输入经 InApp 渠道进入与 IM 完全相同的任务链路，
 * 过程与结果消息经 InAppChatStore 实时回显。
 */
class ChatActivity : BaseActivity() {

    private val messages = mutableListOf<InAppChatStore.Message>()
    private lateinit var adapter: ChatAdapter
    private lateinit var etInput: EditText

    private val storeListener = { msg: InAppChatStore.Message ->
        runOnUiThread { onNewMessage(msg) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.chat_title))
            setBackIcon(R.drawable.ic_back)
            showBackButton(true) { finish() }
        }

        etInput = findViewById(R.id.etInput)
        adapter = ChatAdapter(messages)
        val list = findViewById<RecyclerView>(R.id.chatList)
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter

        messages.addAll(InAppChatStore.list())
        adapter.notifyDataSetChanged()
        scrollToBottom()

        findViewById<TextView>(R.id.btnSend).setOnClickListener { sendInput() }
        etInput.setOnEditorActionListener { _, _, _ ->
            sendInput()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        InAppChatStore.listener = storeListener
    }

    override fun onPause() {
        super.onPause()
        if (InAppChatStore.listener === storeListener) {
            InAppChatStore.listener = null
        }
    }

    private fun sendInput() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        if (!appViewModel.sendInAppMessage(text)) {
            Toast.makeText(this, R.string.chat_llm_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        etInput.setText("")
        hideKeyboard()
    }

    private fun onNewMessage(msg: InAppChatStore.Message) {
        messages.add(msg)
        adapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        findViewById<RecyclerView>(R.id.chatList).scrollToPosition((messages.size - 1).coerceAtLeast(0))
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(etInput.windowToken, 0)
    }

    /** 用户/助手两种气泡，消息角色决定布局 */
    private class ChatAdapter(private val items: List<InAppChatStore.Message>) :
        RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvText: TextView = view.findViewById(R.id.tvText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val layout = if (viewType == TYPE_USER) R.layout.item_chat_user else R.layout.item_chat_agent
            return ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.tvText.text = items[position].text
        }

        override fun getItemViewType(position: Int): Int =
            if (items[position].role == InAppChatStore.Role.USER.name) TYPE_USER else TYPE_AGENT

        companion object {
            private const val TYPE_USER = 0
            private const val TYPE_AGENT = 1
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ChatActivity::class.java))
        }
    }
}
