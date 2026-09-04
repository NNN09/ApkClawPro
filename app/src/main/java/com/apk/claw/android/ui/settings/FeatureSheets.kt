package com.apk.claw.android.ui.settings

import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 功能面板（技能/定时/触发/策略）共用的底部弹窗外壳：
 * 统一标题栏 + 关闭按钮 + 底色，内容布局由调用方填充。
 */
internal fun BaseActivity.showFormSheet(
    layoutId: Int,
    title: CharSequence,
    onInflate: (View, BottomSheetDialog) -> Unit
): BottomSheetDialog {
    val dialog = BottomSheetDialog(this)
    val view = layoutInflater.inflate(layoutId, null)
    view.findViewById<TextView>(R.id.tvTitle).text = title
    view.findViewById<View>(R.id.btnClose).setOnClickListener { dialog.dismiss() }
    dialog.setContentView(view)
    dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        ?.setBackgroundColor(getColor(R.color.colorBgPrimary))
    onInflate(view, dialog)
    dialog.show()
    return dialog
}

internal fun BaseActivity.toast(resId: Int) {
    Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
