package dev.vibeforge.console

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * VibeVibeForge — push a folder from your phone, let GitHub build it, install the APK.
 *
 * The whole point is to close a loop that otherwise needs a computer: edit on
 * the phone, build in CI, install on the phone. Everything here serves that
 * one path, and the screen is deliberately a single scrolling column so the
 * order of the buttons is the order of the work.
 *
 * Built with views in code rather than XML or Compose. Not a style preference:
 * this app has to compile first time on a CI runner nobody can debug from a
 * phone, and every layout resource or compiler plugin is another thing that
 * can fail with a message you cannot read.
 */
class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var log: TextView
    private lateinit var status: TextView
    private lateinit var tokenField: EditText
    private lateinit var ownerField: EditText
    private lateinit var repoField: EditText
    private lateinit var branchField: EditText
    private lateinit var projectLabel: TextView
    private lateinit var pushButton: Button
    private lateinit var watchButton: Button
    private lateinit var installButton: Button

    private var projectUri: Uri? = null
    private var lastCommit: String = ""
    private var polling = false

    private val ink = Color.parseColor("#12161C")
    private val paper = Color.parseColor("#F5F3EC")
    private val line = Color.parseColor("#D8D2C2")
    private val accent = Color.parseColor("#1F4E79")
    private val brass = Color.parseColor("#A87722")
    private val soft = Color.parseColor("#5A6270")
    private val bad = Color.parseColor("#8E2C2C")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    // ── folder picker ────────────────────────────────────────────────────────

    private val pickFolder = 9001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }

        root.addView(title("VibeForge"))
        root.addView(body("Push a project folder to GitHub, let Actions build it, install the APK. No computer in the loop."))

        // ── 1. GitHub ────────────────────────────────────────────────────────
        root.addView(heading("1 · GitHub"))

        tokenField = field("Personal access token", password = true)
        root.addView(label("Token"))
        root.addView(tokenField)
        root.addView(hint("github.com → Settings → Developer settings → Personal access tokens → Fine-grained. Give it Contents: read and write, plus Actions: read, on the repo you'll use."))

        val ids = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ownerField = field("your-username")
        repoField = field("repo-name")
        ids.addView(column("Owner", ownerField))
        ids.addView(column("Repo", repoField))
        root.addView(ids)

        branchField = field("main")
        root.addView(label("Branch"))
        root.addView(branchField)

        root.addView(button("Check connection", accent) { checkConnection() })

        // ── 2. Project ───────────────────────────────────────────────────────
        root.addView(heading("2 · Project folder"))
        projectLabel = body("No folder chosen")
        root.addView(projectLabel)
        root.addView(button("Choose folder") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                              Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
                pickFolder
            )
        })

        // ── 3. Build ─────────────────────────────────────────────────────────
        root.addView(heading("3 · Build"))
        pushButton = button("Push and build", brass) { push() }
        root.addView(pushButton)
        watchButton = button("Check build status") { checkRuns() }
        root.addView(watchButton)

        status = body("")
        status.setTypeface(android.graphics.Typeface.MONOSPACE)
        root.addView(status)

        // ── 4. Install ───────────────────────────────────────────────────────
        root.addView(heading("4 · Install"))
        installButton = button("Download latest APK", accent) { installLatest() }
        root.addView(installButton)
        root.addView(hint("Play Protect blocks sideloaded installs the first time. Play Store → profile → Play Protect → turn scanning off, install, turn it back on. On Samsung also turn off Auto Blocker."))

        root.addView(divider())
        log = TextView(this).apply {
            setTextColor(soft)
            textSize = 11f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextIsSelectable(true)
        }
        root.addView(log)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(paper)
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        restore()
    }

    private fun restore() {
        tokenField.setText(Store.get(this, Store.TOKEN))
        ownerField.setText(Store.get(this, Store.OWNER))
        repoField.setText(Store.get(this, Store.REPO))
        branchField.setText(Store.get(this, Store.BRANCH, "main"))
        lastCommit = Store.get(this, Store.LAST_COMMIT)
        Store.get(this, Store.PROJECT_URI).takeIf { it.isNotEmpty() }?.let {
            projectUri = Uri.parse(it)
            projectLabel.text = "Folder: " + runCatching {
                Project.folderName(this, projectUri!!)
            }.getOrDefault("(saved)")
        }
    }

    private fun save() {
        Store.put(this, Store.TOKEN, tokenField.text.toString().trim())
        Store.put(this, Store.OWNER, ownerField.text.toString().trim())
        Store.put(this, Store.REPO, repoField.text.toString().trim())
        Store.put(this, Store.BRANCH, branchField.text.toString().trim().ifEmpty { "main" })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickFolder || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        projectUri = uri
        Store.put(this, Store.PROJECT_URI, uri.toString())
        projectLabel.text = "Folder: ${Project.folderName(this, uri)}"
        say("Folder selected: ${Project.folderName(this, uri)}")
    }

    // ── actions ──────────────────────────────────────────────────────────────

    private fun api(): GitHub? {
        save()
        val token = tokenField.text.toString().trim()
        if (token.isEmpty()) { say("Add a token first."); return null }
        return GitHub(token)
    }

    private fun checkConnection() {
        val gh = api() ?: return
        val owner = ownerField.text.toString().trim()
        val repo = repoField.text.toString().trim()
        run("Checking GitHub…") {
            val who = gh.whoAmI()
            say("Signed in as $who")
            if (owner.isEmpty() || repo.isEmpty()) {
                say("Fill in owner and repo to check the repository.")
                return@run
            }
            if (gh.repoExists(owner, repo)) {
                say("Repository $owner/$repo is reachable.")
            } else {
                say("$owner/$repo not found. Creating it…")
                val full = gh.createRepo(repo, private = false)
                say("Created $full")
            }
        }
    }

    private fun push() {
        val gh = api() ?: return
        val uri = projectUri ?: run { say("Choose a project folder first."); return }
        val owner = ownerField.text.toString().trim()
        val repo = repoField.text.toString().trim()
        val branch = branchField.text.toString().trim().ifEmpty { "main" }
        if (owner.isEmpty() || repo.isEmpty()) { say("Owner and repo are required."); return }

        run("Reading folder…") {
            val scan = Project.read(this, uri)
            say("${scan.files.size} file(s), ${scan.totalBytes / 1024} KB")
            scan.skipped.take(6).forEach { say("  skipped $it") }
            if (scan.skipped.size > 6) say("  …and ${scan.skipped.size - 6} more skipped")
            if (scan.files.isEmpty()) { say("Nothing to push."); return@run }

            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val sha = gh.pushFolder(owner, repo, branch, scan.files, "VibeForge push $stamp") { done, total, path ->
                ui.post { setStatus("uploading $done/$total  $path") }
            }
            lastCommit = sha
            Store.put(this, Store.LAST_COMMIT, sha)
            say("Pushed as ${sha.take(7)} — Actions should start within a few seconds.")
            ui.postDelayed({ checkRuns() }, 6000)
        }
    }

    private fun checkRuns() {
        val gh = api() ?: return
        val owner = ownerField.text.toString().trim()
        val repo = repoField.text.toString().trim()
        if (owner.isEmpty() || repo.isEmpty()) return

        run(null) {
            val runs = gh.latestRuns(owner, repo, 3)
            if (runs.isEmpty()) { setStatus("no workflow runs yet"); return@run }
            val r = runs.first()
            val state = r.conclusion ?: r.status
            setStatus("${r.name} · ${r.sha} · $state")

            when {
                r.status != "completed" -> {
                    // Poll rather than make the user tap: a build takes minutes
                    // and watching a static screen teaches nothing.
                    if (!polling) {
                        polling = true
                        ui.postDelayed({ polling = false; checkRuns() }, 15000)
                    }
                }
                r.conclusion == "success" -> {
                    say("Build succeeded (${r.sha}). Ready to install.")
                }
                else -> {
                    say("Build ${r.conclusion} — ${r.url}")
                    val detail = gh.failureLog(owner, repo, r.id)
                    say(detail)
                }
            }
        }
    }

    private fun installLatest() {
        val gh = api() ?: return
        val owner = ownerField.text.toString().trim()
        val repo = repoField.text.toString().trim()
        val token = tokenField.text.toString().trim()

        run("Looking for a release…") {
            val apks = gh.latestApks(owner, repo)
            if (apks.isEmpty()) {
                say("No APK in the latest releases. Does the workflow publish one?")
                return@run
            }
            val apk = apks.first()
            say("Downloading ${apk.name} (${apk.size / 1024} KB)…")
            val file = Installer.download(this, apk.url, token) { read, total ->
                if (total > 0) ui.post { setStatus("downloading ${read * 100 / total}%") }
            }
            say("Downloaded. Opening the installer…")
            ui.post { Installer.install(this, file) }
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    /** Runs work off the main thread and reports failures where you can read them. */
    private fun run(startMessage: String?, block: () -> Unit) {
        startMessage?.let { setStatus(it) }
        io.execute {
            try {
                block()
            } catch (e: GitHub.ApiError) {
                val hint = when (e.status) {
                    401 -> "  → the token is wrong or expired"
                    403 -> "  → the token lacks permission for this repo, or you hit a rate limit"
                    404 -> "  → check the owner and repo spelling, and that the token can see it"
                    409 -> "  → the branch moved since you started; push again"
                    422 -> "  → GitHub rejected the contents; usually an empty repo with no branch yet"
                    else -> ""
                }
                say("Error ${e.status}: ${e.body.take(200)}\n$hint")
            } catch (e: Exception) {
                say("Failed: ${e.message ?: e.toString()}")
            }
        }
    }

    private fun say(text: String) = ui.post {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.append("$time  $text\n")
    }

    private fun setStatus(text: String) = ui.post {
        status.text = text
        status.setTextColor(if (text.contains("fail", true)) bad else soft)
    }

    // ── view helpers ─────────────────────────────────────────────────────────

    private fun title(text: String) = TextView(this).apply {
        setText(text); setTextColor(ink); textSize = 30f
        setPadding(0, 0, 0, dp(4))
    }

    private fun heading(text: String) = TextView(this).apply {
        setText(text); setTextColor(accent); textSize = 13f
        setTypeface(android.graphics.Typeface.MONOSPACE)
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun body(text: String) = TextView(this).apply {
        setText(text); setTextColor(soft); textSize = 13f
        setPadding(0, 0, 0, dp(10))
    }

    private fun label(text: String) = TextView(this).apply {
        setText(text); setTextColor(ink); textSize = 12f
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun hint(text: String) = TextView(this).apply {
        setText(text); setTextColor(soft); textSize = 11f
        setPadding(0, dp(4), 0, dp(6))
    }

    private fun field(hintText: String, password: Boolean = false) = EditText(this).apply {
        hint = hintText
        setTextColor(ink); setHintTextColor(Color.parseColor("#9AA0AC"))
        textSize = 15f
        setBackgroundColor(Color.WHITE)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine()
    }

    private fun column(labelText: String, input: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = dp(6) }
        addView(label(labelText))
        addView(input)
    }

    private fun button(text: String, colour: Int = Color.parseColor("#3A4250"), onClick: () -> Unit) =
        Button(this).apply {
            setText(text)
            setTextColor(Color.WHITE)
            setBackgroundColor(colour)
            isAllCaps = false
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener { onClick() }
        }

    private fun divider() = View(this).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            .apply { topMargin = dp(24); bottomMargin = dp(12) }
    }
}
