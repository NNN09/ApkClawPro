package com.apk.claw.android.tool.impl.system

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * F9：查询本机联系人（姓名/电话），需 READ_CONTACTS 运行时权限。
 * 结果最多 20 条，避免刷爆上下文。
 */
class QueryContactsTool : BaseTool() {

    companion object {
        private const val MAX_RESULTS = 20
    }

    override fun getName() = "query_contacts"

    override fun getDisplayName() = ClawApplication.instance.getString(R.string.tool_name_query_contacts)

    override fun getParameters() = listOf(
        ToolParameter(
            "query", "string",
            "Optional filter: name or number substring to match. Empty = list first contacts",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val context = ClawApplication.instance
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult.error(
                "READ_CONTACTS permission not granted. Ask the user to grant it on the home screen card, then retry"
            )
        }
        val query = optionalString(params, "query", "").trim()
        val selection: String?
        val selectionArgs: Array<String>?
        if (query.isEmpty()) {
            selection = null
            selectionArgs = null
        } else {
            selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            selectionArgs = arrayOf("%$query%", "%$query%")
        }
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val lines = mutableListOf<String>()
                while (cursor.moveToNext() && lines.size < MAX_RESULTS) {
                    val name = cursor.getString(nameIdx) ?: ""
                    val number = cursor.getString(numberIdx) ?: ""
                    if (name.isEmpty() && number.isEmpty()) continue
                    lines.add("- $name: $number")
                }
                if (lines.isEmpty()) {
                    ToolResult.success("No contacts found${if (query.isEmpty()) "" else " for '$query'"}")
                } else {
                    ToolResult.success(
                        "Contacts (${lines.size}${if (cursor.count > MAX_RESULTS) "+, refine query" else ""}):\n" +
                            lines.joinToString("\n")
                    )
                }
            } ?: ToolResult.error("Contacts provider unavailable")
        } catch (e: Exception) {
            ToolResult.error("Failed to query contacts: ${e.message}")
        }
    }

    override fun getDescriptionEN() =
        "Search device contacts by name or number substring (requires READ_CONTACTS). Use for '妈妈的电话是多少'."

    override fun getDescriptionCN() =
        "按姓名或号码模糊查询本机联系人（需要 READ_CONTACTS 权限）。适用于“妈妈的电话是多少”。"
}
