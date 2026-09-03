package com.apk.claw.android.server

import android.content.Context
import android.graphics.Bitmap
import com.apk.claw.android.BuildConfig
import com.apk.claw.android.agent.store.PersonaStore
import com.apk.claw.android.agent.store.SkillImportScanner
import com.apk.claw.android.agent.store.SkillPackager
import com.apk.claw.android.agent.store.SkillStore
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.apk.claw.android.utils.XLog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 局域网 HTTP 配置服务器
 * 提供 H5 页面用于在电脑浏览器上配置钉钉/飞书 key
 */
class ConfigServer(
    private val context: Context,
    port: Int = PORT
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "ConfigServer"
        const val PORT = 9527
        private const val MIME_HTML = "text/html"
        private const val MIME_JSON = "application/json"
        private const val MIME_MARKDOWN = "text/markdown"
        private const val MIME_ZIP = "application/zip"
        private const val MIME_PNG = "image/png"
        private const val SKILL_TEMPLATES_ASSET_DIR = "skills-templates"
        /** 导入内容的体积上限（防压缩炸弹/超大文件） */
        private const val MAX_IMPORT_BYTES = 512 * 1024
    }

    private val gson = Gson()

    /** 取字符串字段；缺失或 JSON null 返回 null，避免 JsonNull.asString 抛异常变成 500 */
    private fun JsonObject.optString(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    override fun serve(session: IHTTPSession): Response {
        // CORS 预检请求
        if (session.method == Method.OPTIONS) {
            return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }

        val uri = session.uri
        val method = session.method

        return try {
            when {
                (uri == "/" || uri == "/index.html") && method == Method.GET -> serveHtml()
                uri == "/api/channels" && method == Method.GET -> handleGetChannels()
                uri == "/api/channels" && method == Method.POST -> handlePostChannels(session)
                uri == "/api/llm" && method == Method.GET -> handleGetLlm()
                uri == "/api/llm" && method == Method.POST -> handlePostLlm(session)
                uri == "/api/persona" && method == Method.GET -> handleGetPersona()
                uri == "/api/persona" && method == Method.POST -> handlePostPersona(session)
                uri == "/api/skills" && method == Method.GET -> handleGetSkills()
                uri == "/api/skills" && method == Method.POST -> handlePostSkill(session)
                // F11：技能/人格分发
                uri == "/api/skills/export" && method == Method.GET -> handleExportSkill(session)
                uri == "/api/skills/export-all" && method == Method.GET -> handleExportAllSkills()
                uri == "/api/skills/qr" && method == Method.GET -> handleSkillQr(session)
                uri == "/api/skills/import-url" && method == Method.POST -> handleImportSkillFromUrl(session)
                uri == "/api/skills/import-content" && method == Method.POST -> handleImportSkillContent(session)
                uri == "/api/skills/templates" && method == Method.GET -> handleGetSkillTemplates()
                uri == "/api/skills/install-template" && method == Method.POST -> handleInstallSkillTemplate(session)
                uri == "/api/persona/export" && method == Method.GET -> handleExportPersona()
                uri == "/api/persona/import" && method == Method.POST -> handleImportPersona(session)
                uri == "/api/tasks" && method == Method.GET -> handleGetTasks()
                uri == "/api/schedules" && method == Method.GET -> handleGetSchedules()
                uri == "/api/schedules" && method == Method.POST -> handlePostSchedule(session)
                uri == "/api/schedules/delete" && method == Method.POST -> handlePostScheduleDelete(session)
                uri == "/api/schedules/toggle" && method == Method.POST -> handlePostScheduleToggle(session)
                uri == "/debug.html" && method == Method.GET && BuildConfig.DEBUG -> serveDebugHtml()
                uri == "/api/debug/tools" && method == Method.GET && BuildConfig.DEBUG -> handleGetTools()
                uri == "/api/debug/execute" && method == Method.POST && BuildConfig.DEBUG -> handleExecuteTool(session)
                uri == "/api/debug/screen-full" && method == Method.GET && BuildConfig.DEBUG -> handleGetScreenFull()
                uri.startsWith("/api/debug/file") && method == Method.GET && BuildConfig.DEBUG -> handleServeFile(session)
                else -> corsResponse(
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND, MIME_JSON,
                        """{"code":-1,"message":"not found"}"""
                    )
                )
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Server error: ${e.message}")
            corsResponse(
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_JSON,
                    """{"code":-1,"message":"${e.message}"}"""
                )
            )
        }
    }

    private fun serveHtml(): Response {
        val inputStream = context.assets.open("web/index.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
    }

    private fun handleGetChannels(): Response {
        val data = JsonObject().apply {
            addProperty("dingtalkAppKey", KVUtils.getDingtalkAppKey())
            addProperty("dingtalkAppSecret", KVUtils.getDingtalkAppSecret())
            addProperty("feishuAppId", KVUtils.getFeishuAppId())
            addProperty("feishuAppSecret", KVUtils.getFeishuAppSecret())
            addProperty("qqAppId", KVUtils.getQqAppId())
            addProperty("qqAppSecret", KVUtils.getQqAppSecret())
            addProperty("discordBotToken", KVUtils.getDiscordBotToken())
            addProperty("telegramBotToken", KVUtils.getTelegramBotToken())
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handlePostChannels(session: IHTTPSession): Response {
        // NanoHTTPD 要求先 parseBody 才能读取 POST body
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}"""
                )
            )
        }

        var reinitDingtalk = false
        var reinitFeishu = false
        var reinitQQ = false
        var reinitDiscord = false
        var reinitTelegram = false

        // 钉钉配置
        if (json.has("dingtalkAppKey")) {
            val value = json.get("dingtalkAppKey").asString
            KVUtils.setDingtalkAppKey(value)
            reinitDingtalk = true
        }
        if (json.has("dingtalkAppSecret")) {
            val value = json.get("dingtalkAppSecret").asString
            // 如果是脱敏值则跳过
            if (!isMaskedValue(value)) {
                KVUtils.setDingtalkAppSecret(value)
                reinitDingtalk = true
            }
        }

        // 飞书配置
        if (json.has("feishuAppId")) {
            val value = json.get("feishuAppId").asString
            KVUtils.setFeishuAppId(value)
            reinitFeishu = true
        }
        if (json.has("feishuAppSecret")) {
            val value = json.get("feishuAppSecret").asString
            if (!isMaskedValue(value)) {
                KVUtils.setFeishuAppSecret(value)
                reinitFeishu = true
            }
        }

        // QQ 配置
        if (json.has("qqAppId")) {
            val value = json.get("qqAppId").asString
            KVUtils.setQqAppId(value)
            reinitQQ = true
        }
        if (json.has("qqAppSecret")) {
            val value = json.get("qqAppSecret").asString
            if (!isMaskedValue(value)) {
                KVUtils.setQqAppSecret(value)
                reinitQQ = true
            }
        }

        // Discord 配置
        if (json.has("discordBotToken")) {
            val value = json.get("discordBotToken").asString
            if (!isMaskedValue(value)) {
                KVUtils.setDiscordBotToken(value)
                reinitDiscord = true
            }
        }

        // Telegram 配置
        if (json.has("telegramBotToken")) {
            val value = json.get("telegramBotToken").asString
            if (!isMaskedValue(value)) {
                KVUtils.setTelegramBotToken(value)
                reinitTelegram = true
            }
        }

        // 重新初始化对应通道
        if (reinitDingtalk) {
            ChannelManager.reinitDingTalkFromStorage()
        }
        if (reinitFeishu) {
            ChannelManager.reinitFeiShuFromStorage()
        }
        if (reinitQQ) {
            ChannelManager.reinitQQFromStorage()
        }
        if (reinitDiscord) {
            ChannelManager.reinitDiscordFromStorage()
        }
        if (reinitTelegram) {
            ChannelManager.reinitTelegramFromStorage()
        }

        // 通知 Settings 页面刷新绑定状态
        if (reinitDingtalk || reinitFeishu || reinitQQ || reinitDiscord || reinitTelegram) {
            ConfigServerManager.notifyConfigChanged()
        }

        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleGetLlm(): Response {
        val apiKey = KVUtils.getLlmApiKey()
        val data = JsonObject().apply {
            addProperty("llmApiKey", apiKey)
            addProperty("llmBaseUrl", KVUtils.getLlmBaseUrl())
            addProperty("llmModelName", KVUtils.getLlmModelName())
            addProperty("llmContextWindow", KVUtils.getLlmContextWindow())
            addProperty("confirmDangerousOps", KVUtils.getConfirmDangerousOps())
            addProperty("verifyResults", KVUtils.getVerifyResults())
            addProperty("visionEnabled", KVUtils.getVisionEnabled())
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handlePostLlm(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}"""
                )
            )
        }

        if (json.has("llmApiKey")) {
            val value = json.get("llmApiKey").asString
            if (!isMaskedValue(value)) {
                KVUtils.setLlmApiKey(value)
            }
        }
        if (json.has("llmBaseUrl")) {
            KVUtils.setLlmBaseUrl(json.get("llmBaseUrl").asString)
        }
        if (json.has("llmModelName")) {
            val value = json.get("llmModelName").asString.trim()
            KVUtils.setLlmModelName(if (value.isEmpty()) "" else value)
        }
        if (json.has("llmContextWindow")) {
            // 网页端以字符串提交，空串/非法值按未设置(0)处理
            val tokens = try {
                json.get("llmContextWindow").asInt
            } catch (_: Exception) {
                0
            }
            KVUtils.setLlmContextWindow(tokens.coerceAtLeast(0))
        }
        if (json.has("confirmDangerousOps")) {
            KVUtils.setConfirmDangerousOps(json.get("confirmDangerousOps").asBoolean)
        }
        if (json.has("verifyResults")) {
            KVUtils.setVerifyResults(json.get("verifyResults").asBoolean)
        }
        if (json.has("visionEnabled")) {
            KVUtils.setVisionEnabled(json.get("visionEnabled").asBoolean)
        }

        ConfigServerManager.notifyConfigChanged()

        val result = JsonObject().apply {
            addProperty("code", 0)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    // ==================== Persona ====================

    private fun handleGetPersona(): Response {
        val data = JsonObject().apply { addProperty("persona", PersonaStore.get()) }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handlePostPersona(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}"""
                )
            )
        }
        json.optString("persona")?.let { PersonaStore.set(it) }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, """{"code":0,"message":"ok"}"""))
    }

    // ==================== Skills ====================

    private fun handleGetSkills(): Response {
        val skills = SkillStore.list().map {
            JsonObject().apply {
                addProperty("name", it.name)
                addProperty("description", it.description)
            }
        }
        val data = JsonObject().apply { add("skills", gson.toJsonTree(skills)) }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handlePostSkill(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}"""
                )
            )
        }
        val name = json.optString("name") ?: ""
        val description = json.optString("description") ?: ""
        val content = json.optString("content") ?: ""
        val outcome = importSkillWithScan(name, description, content)
        return if (outcome.ok) {
            val message = if (outcome.warnings.isEmpty()) "ok"
                else "saved with warnings:\n${outcome.warnings}"
            corsResponse(newFixedLengthResponse(
                Response.Status.OK, MIME_JSON,
                """{"code":0,"message":"${jsonEscape(message)}"}"""
            ))
        } else {
            corsResponse(newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"${jsonEscape("import blocked by security scan:\n${outcome.warnings}")}"}"""
            ))
        }
    }

    // ==================== F11：技能/人格分发 ====================

    /** 导入结果：ok=是否成功；warnings=扫描报告（成功时为放行告警，失败时为拦截原因） */
    private data class ImportOutcome(val ok: Boolean, val warnings: String)

    /**
     * 统一的技能导入入口：先做提示注入安全审查（F11），
     * HIGH 命中直接拒绝并附报告；WARN 命中放行但返回告警内容。
     */
    private fun importSkillWithScan(name: String, description: String, body: String): ImportOutcome {
        val findings = SkillImportScanner.scan("$description\n$body")
        if (SkillImportScanner.hasBlocking(findings)) {
            return ImportOutcome(false, SkillImportScanner.report(findings))
        }
        if (!SkillStore.upsert(name, description, body)) {
            return ImportOutcome(false, "invalid skill name (a-z, 0-9, '-'; max 40)")
        }
        return ImportOutcome(true, SkillImportScanner.report(findings))
    }

    private fun jsonEscape(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    /** GET /api/skills/export?name=x → SKILL.md 原文下载 */
    private fun handleExportSkill(session: IHTTPSession): Response {
        val name = session.parameters["name"]?.firstOrNull() ?: ""
        val raw = SkillStore.exportRaw(name)
            ?: return badRequest("skill not found: $name")
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_MARKDOWN, raw)
                .apply { addHeader("Content-Disposition", "attachment; filename=\"$name.SKILL.md\"") }
        )
    }

    /** GET /api/skills/export-all → 全部技能打包 zip */
    private fun handleExportAllSkills(): Response {
        val zip = SkillPackager.exportAll(SkillStore.skillsDir())
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_ZIP, ByteArrayInputStream(zip), zip.size.toLong())
                .apply { addHeader("Content-Disposition", "attachment; filename=\"apkclaw-skills.zip\"") }
        )
    }

    /** GET /api/skills/qr?name=x → 深链二维码 PNG（扫码后经 SkillImportActivity 确认导入） */
    private fun handleSkillQr(session: IHTTPSession): Response {
        val name = session.parameters["name"]?.firstOrNull() ?: ""
        if (SkillStore.exportRaw(name) == null) return badRequest("skill not found: $name")
        val host = session.headers["host"] ?: "localhost:$PORT"
        val exportUrl = "http://$host/api/skills/export?name=${URLEncoder.encode(name, "UTF-8")}"
        val deepLink = "apkclaw://skill-import?url=${URLEncoder.encode(exportUrl, "UTF-8")}"
        val png = qrPng(deepLink) ?: return badRequest("failed to generate QR")
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_PNG, ByteArrayInputStream(png), png.size.toLong()))
    }

    /** zxing core 生成二维码 PNG（无 javase 模块，手动转 Bitmap） */
    private fun qrPng(content: String, size: Int = 360): ByteArray? {
        return try {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(matrix.width * matrix.height)
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    pixels[y * matrix.width + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
            bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            XLog.w(TAG, "QR generation failed: ${e.message}")
            null
        }
    }

    /** 字节内容导入结果：ok + 已导入技能名 + 告警（失败时 warnings 为失败原因） */
    private data class ImportBytesResult(val ok: Boolean, val imported: List<String>, val warnings: String)

    /** .md / zip 字节内容 → 解析 → 逐个安全扫描导入（URL 下载与 content 上传共用） */
    private fun importBytes(bytes: ByteArray, overrideName: String?): ImportBytesResult {
        val imported = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            val skills = SkillPackager.importZip(bytes)
            if (skills.isEmpty()) return ImportBytesResult(false, emptyList(), "no valid SKILL.md entries found in zip")
            for (skill in skills) {
                val outcome = importSkillWithScan(skill.name, skill.description, skill.body)
                if (outcome.ok) imported.add(skill.name) else warnings.add("${skill.name}: ${outcome.warnings}")
            }
        } else {
            val raw = bytes.toString(Charsets.UTF_8)
            val parsed = SkillPackager.parseSkillMd(raw)
                ?: return ImportBytesResult(false, emptyList(), "content is not a valid SKILL.md (frontmatter with name/description required)")
            val name = overrideName?.trim()?.takeIf { it.isNotEmpty() } ?: parsed.name
            val outcome = importSkillWithScan(name, parsed.description, parsed.body)
            if (!outcome.ok) return ImportBytesResult(false, emptyList(), outcome.warnings)
            imported.add(name)
            if (outcome.warnings.isNotEmpty()) warnings.add(outcome.warnings)
        }
        return ImportBytesResult(true, imported, warnings.joinToString("\n"))
    }

    private fun importBytesResponse(result: ImportBytesResult): Response {
        if (!result.ok) return badRequest(result.warnings)
        val data = JsonObject().apply {
            addProperty("imported", result.imported.joinToString(","))
            if (result.warnings.isNotEmpty()) addProperty("warnings", result.warnings)
        }
        val json = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.toString()))
    }

    /** POST /api/skills/import-url {url, name?}：从 URL 拉取 SKILL.md 或 zip 导入（内容仍过安全扫描） */
    private fun handleImportSkillFromUrl(session: IHTTPSession): Response {
        val json = readPostJson(session) ?: return badRequest("invalid json")
        val url = json.optString("url")?.trim() ?: ""
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return badRequest("url must start with http(s)://")
        }
        val bytes = try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "ApkClaw/${BuildConfig.VERSION_NAME}")
            try {
                SkillPackager.readBounded(conn.inputStream, MAX_IMPORT_BYTES)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            return badRequest("download failed: ${e.message}")
        }
        return importBytesResponse(importBytes(bytes, json.optString("name")))
    }

    /** POST /api/skills/import-content {contentB64, name?}：直接上传 .md / zip 内容导入（安全扫描同路） */
    private fun handleImportSkillContent(session: IHTTPSession): Response {
        val json = readPostJson(session) ?: return badRequest("invalid json")
        val b64 = json.optString("contentB64")?.trim() ?: ""
        if (b64.isEmpty()) return badRequest("contentB64 is required")
        val bytes = try {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            return badRequest("invalid base64 content")
        }
        if (bytes.isEmpty() || bytes.size > MAX_IMPORT_BYTES) {
            return badRequest("content is empty or too large (max ${MAX_IMPORT_BYTES / 1024}KB)")
        }
        return importBytesResponse(importBytes(bytes, json.optString("name")))
    }

    /** GET /api/skills/templates → 内置模板库列表 */
    private fun handleGetSkillTemplates(): Response {
        val templates = try {
            context.assets.list(SKILL_TEMPLATES_ASSET_DIR)?.sorted() ?: emptyList()
        } catch (_: Exception) { emptyList() }
        val items = templates.mapNotNull { fileName ->
            try {
                val raw = context.assets.open("$SKILL_TEMPLATES_ASSET_DIR/$fileName").bufferedReader().use { it.readText() }
                SkillPackager.parseSkillMd(raw)?.let {
                    JsonObject().apply {
                        addProperty("file", fileName.removeSuffix(".md"))
                        addProperty("name", it.name)
                        addProperty("description", it.description)
                    }
                }
            } catch (_: Exception) { null }
        }
        val data = JsonObject().apply { add("templates", gson.toJsonTree(items)) }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    /** POST /api/skills/install-template {name}：安装内置模板（仍过安全扫描） */
    private fun handleInstallSkillTemplate(session: IHTTPSession): Response {
        val json = readPostJson(session) ?: return badRequest("invalid json")
        val fileName = json.optString("name")?.trim() ?: ""
        if (!Regex("^[a-z0-9-]{1,40}$").matches(fileName)) return badRequest("invalid template name")
        val raw = try {
            context.assets.open("$SKILL_TEMPLATES_ASSET_DIR/$fileName.md").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return badRequest("template not found: $fileName")
        }
        val parsed = SkillPackager.parseSkillMd(raw) ?: return badRequest("template is not a valid SKILL.md")
        val outcome = importSkillWithScan(parsed.name, parsed.description, parsed.body)
        return if (outcome.ok) {
            val message = if (outcome.warnings.isEmpty()) "ok"
                else "saved with warnings:\n${outcome.warnings}"
            corsResponse(newFixedLengthResponse(
                Response.Status.OK, MIME_JSON,
                """{"code":0,"message":"${jsonEscape(message)}"}"""
            ))
        } else {
            badRequest("import blocked by security scan:\n${outcome.warnings}")
        }
    }

    /** GET /api/persona/export → persona.md 原文下载 */
    private fun handleExportPersona(): Response {
        val persona = PersonaStore.get()
        if (persona.isBlank()) return badRequest("persona is empty")
        return corsResponse(
            newFixedLengthResponse(Response.Status.OK, MIME_MARKDOWN, persona)
                .apply { addHeader("Content-Disposition", "attachment; filename=\"persona.md\"") }
        )
    }

    /** POST /api/persona/import {content}：人格导入（人格同为提示词注入载体，同策略扫描） */
    private fun handleImportPersona(session: IHTTPSession): Response {
        val json = readPostJson(session) ?: return badRequest("invalid json")
        val content = json.optString("content")?.trim() ?: ""
        if (content.isEmpty()) return badRequest("persona content is required")
        val findings = SkillImportScanner.scan(content)
        if (SkillImportScanner.hasBlocking(findings)) {
            return badRequest("import blocked by security scan:\n${SkillImportScanner.report(findings)}")
        }
        PersonaStore.set(content)
        val warnings = SkillImportScanner.report(findings)
        val message = if (warnings.isEmpty()) "ok" else "saved with warnings:\n$warnings"
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, """{"code":0,"message":"${jsonEscape(message)}"}"""))
    }

    // ==================== 任务历史（F3） ====================

    private fun handleGetTasks(): Response {
        val tasks = com.apk.claw.android.agent.store.TaskHistoryStore.list(100).map { record ->
            JsonObject().apply {
                addProperty("id", record.id)
                addProperty("startTime", record.startTime)
                addProperty("endTime", record.endTime)
                addProperty("channel", record.channel)
                // 发送者 ID 可能含平台身份信息，仅保留前缀用于区分
                addProperty("sender", record.sender.take(6))
                addProperty("task", record.task)
                addProperty("status", record.status)
                addProperty("rounds", record.rounds)
                addProperty("toolCalls", record.toolCalls)
                addProperty("tokens", record.tokens)
                addProperty("error", record.error)
                add("toolTrace", gson.toJsonTree(record.toolTrace))
            }
        }
        val data = JsonObject().apply { add("tasks", gson.toJsonTree(tasks)) }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    // ==================== 定时任务（F7） ====================

    private fun handleGetSchedules(): Response {
        val tasks = com.apk.claw.android.agent.store.ScheduledTaskStore.list().map { t ->
            JsonObject().apply {
                addProperty("id", t.id)
                addProperty("name", t.name)
                addProperty("task", t.task)
                addProperty("channel", t.channel)
                addProperty("hour", t.hour)
                addProperty("minute", t.minute)
                add("daysOfWeek", gson.toJsonTree(t.daysOfWeek))
                addProperty("enabled", t.enabled)
                addProperty("lastTriggerAt", t.lastTriggerAt)
            }
        }
        val data = JsonObject().apply { add("tasks", gson.toJsonTree(tasks)) }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
            addProperty("message", "ok")
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handlePostSchedule(session: IHTTPSession): Response {
        val json = readPostJson(session)
            ?: return badRequest("invalid json")
        val taskText = json.optString("task")?.trim() ?: ""
        val time = json.optString("time")?.trim() ?: ""
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: -1
        val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: -1
        if (taskText.isEmpty() || hour !in 0..23 || minute !in 0..59) {
            return badRequest("task and time ('HH:mm') are required")
        }
        val daysRaw = json.optString("daysOfWeek")?.trim() ?: ""
        val days = com.apk.claw.android.tool.impl.ScheduleTaskTool.parseDaysOfWeek(daysRaw)
            ?: return badRequest("daysOfWeek must be comma-separated 1-7")

        val channel = try {
            com.apk.claw.android.channel.Channel.valueOf(json.optString("channel")?.trim() ?: "IN_APP")
        } catch (_: Exception) {
            com.apk.claw.android.channel.Channel.IN_APP
        }
        val sender = json.optString("senderId")?.trim().takeUnless { it.isNullOrEmpty() } ?: "local"

        val scheduled = com.apk.claw.android.agent.store.ScheduledTaskStore.ScheduledTask(
            id = "st-${java.lang.Long.toString(System.currentTimeMillis(), 36)}-${(100..999).random()}",
            name = json.optString("name")?.trim().takeUnless { it.isNullOrEmpty() } ?: taskText.take(20),
            task = taskText,
            channel = channel.name,
            senderId = sender,
            hour = hour,
            minute = minute,
            daysOfWeek = days,
            enabled = true,
            createdAt = System.currentTimeMillis()
        )
        val added = try {
            com.apk.claw.android.agent.store.ScheduledTaskStore.add(scheduled)
        } catch (e: Exception) {
            return badRequest(e.message ?: "failed to add schedule")
        }
        com.apk.claw.android.service.TaskScheduler.schedule(context, added)
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, """{"code":0,"message":"ok","data":{"id":"${added.id}"}}"""))
    }

    private fun handlePostScheduleDelete(session: IHTTPSession): Response {
        val json = readPostJson(session) ?: return badRequest("invalid json")
        val id = json.optString("id") ?: ""
        if (com.apk.claw.android.agent.store.ScheduledTaskStore.remove(id)) {
            com.apk.claw.android.service.TaskScheduler.cancel(context, id)
            return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, """{"code":0,"message":"ok"}"""))
        }
        return badRequest("schedule not found: $id")
    }

    private fun handlePostScheduleToggle(session: IHTTPSession): Response {
        val json = readPostJson(session) ?: return badRequest("invalid json")
        val id = json.optString("id") ?: ""
        val enabled = json.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val updated = com.apk.claw.android.agent.store.ScheduledTaskStore.setEnabled(id, enabled)
            ?: return badRequest("schedule not found: $id")
        com.apk.claw.android.service.TaskScheduler.schedule(context, updated)
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, """{"code":0,"message":"ok"}"""))
    }

    private fun readPostJson(session: IHTTPSession): JsonObject? {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""
        return try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            null
        }
    }

    private fun badRequest(message: String): Response =
        corsResponse(newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_JSON, """{"code":-1,"message":"${jsonEscape(message)}"}"""))

    // ==================== Debug (仅 DEBUG 构建) ====================
    
    private fun handleGetScreenFull(): Response {
        val service = com.apk.claw.android.service.ClawAccessibilityService.getInstance()
            ?: return corsResponse(
                newFixedLengthResponse(
                    Response.Status.OK, MIME_JSON,
                    """{"code":-1,"message":"Accessibility service is not running"}"""
                )
            )
        val tree = service.screenTreeFull
        val data = JsonObject().apply {
            addProperty("success", tree != null)
            addProperty("data", tree ?: "")
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun serveDebugHtml(): Response {
        val inputStream = context.assets.open("web/debug.html")
        val html = inputStream.bufferedReader().use { it.readText() }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_HTML, html))
    }

    private fun handleGetTools(): Response {
        val tools = ToolRegistry.getAllTools()
        val arr = JsonArray()
        for (tool in tools) {
            val obj = JsonObject().apply {
                addProperty("name", tool.getName())
                addProperty("displayName", tool.getDisplayName())
                addProperty("description", tool.getDescription())
                val params = JsonArray()
                for (p in tool.getParameters()) {
                    params.add(JsonObject().apply {
                        addProperty("name", p.name)
                        addProperty("type", p.type)
                        addProperty("description", p.description)
                        addProperty("required", p.isRequired)
                    })
                }
                add("parameters", params)
            }
            arr.add(obj)
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", arr)
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleExecuteTool(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"code":-1,"message":"invalid json"}"""
                )
            )
        }

        val toolName = json.get("tool")?.asString ?: return corsResponse(
            newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"missing tool name"}"""
            )
        )

        val params = mutableMapOf<String, Any>()
        try {
            json.getAsJsonObject("params")?.entrySet()?.forEach { (key, value) ->
                when {
                    value.isJsonNull -> {}
                    !value.isJsonPrimitive -> params[key] = value.toString()
                    value.asJsonPrimitive.isNumber -> params[key] = value.asNumber
                    value.asJsonPrimitive.isBoolean -> params[key] = value.asBoolean
                    else -> params[key] = value.asString
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Debug param parse error: ${e.message}")
        }

        XLog.d(TAG, "Debug execute: $toolName params=$params")

        val toolResult = try {
            ToolRegistry.executeTool(toolName, params)
        } catch (e: Exception) {
            XLog.e(TAG, "Debug execute error", e)
            ToolResult.error("Exception: ${e.message}")
        }

        val data = JsonObject().apply {
            addProperty("success", toolResult.isSuccess)
            addProperty("data", toolResult.data)
            addProperty("error", toolResult.error)
        }
        val result = JsonObject().apply {
            addProperty("code", 0)
            add("data", data)
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString()))
    }

    private fun handleServeFile(session: IHTTPSession): Response {
        val path = session.parms["path"] ?: return corsResponse(
            newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_JSON,
                """{"code":-1,"message":"missing path param"}"""
            )
        )
        // 安全校验：只允许访问 cache 目录下的文件
        val cacheDir = context.cacheDir.absolutePath
        val file = java.io.File(path)
        if (!file.exists() || !file.absolutePath.startsWith(cacheDir)) {
            return corsResponse(
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_JSON,
                    """{"code":-1,"message":"file not found or access denied"}"""
                )
            )
        }
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
        return corsResponse(newFixedLengthResponse(Response.Status.OK, mime, file.inputStream(), file.length()))
    }

    /**
     * 脱敏：只显示后4位，前面用 * 替代
     */
    private fun maskSecret(secret: String): String {
        if (secret.isEmpty()) return ""
        if (secret.length <= 4) return secret
        return "*".repeat(secret.length - 4) + secret.takeLast(4)
    }

    /**
     * 判断是否为脱敏后的值（包含 *）
     */
    private fun isMaskedValue(value: String): Boolean {
        return value.contains("*")
    }

    private fun corsResponse(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return response
    }
}
