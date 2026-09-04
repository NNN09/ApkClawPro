package com.apk.claw.android.ui.settings

import android.os.Bundle
import android.widget.EditText
import com.apk.claw.android.R
import com.apk.claw.android.agent.store.PersonaStore
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton

/**
 * 人设编辑页，与 LAN /api/persona GET/POST 同源同语义：
 * 内容直接读写 PersonaStore（filesDir/agent/persona.md），系统提示词组装时活读。
 */
class PersonaConfigActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_persona_config)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.persona_config_title))
            showBackButton(true) { finish() }
        }

        val etPersona = findViewById<EditText>(R.id.etPersona)
        etPersona.setText(PersonaStore.get())

        findViewById<KButton>(R.id.btnSave).setOnClickListener {
            PersonaStore.set(etPersona.text.toString())
            toast(R.string.persona_saved)
            finish()
        }
    }
}
