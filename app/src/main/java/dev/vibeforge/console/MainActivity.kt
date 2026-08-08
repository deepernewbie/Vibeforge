package dev.vibeforge.console

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * VibeForge — push a folder, let GitHub build it, install the APK.
 *
 * The screen follows the four things you actually do, in order, and everything
 * configured once has moved into Settings. The first version put a token field
 * and two identifier boxes at the top, which meant scrolling past three inputs
 * you had already filled in every time you wanted to press one button.
 */
class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private val paper = Color.parseColor("#F5F3EC")
    private val surface = Color.parseColor("#FFFFFF")
    private val ink = Color.parseColor("#12161C")
    private val soft = Color.parseColor("#5A6270")
    private val faint = Color.parseColor("#8B93A1")
    private val accent = Color.parseColor("#1F4E79")
    private val brass = Color.parseColor("#A87722")
    private val good = Color.parseColor("#2E6B4F")
    private val bad = Color.parseColor("#8E2C2C")

    private lateinit var repoButton: Button
    private lateinit var projectButton: Button
    private lateinit var iconButton: Button
    private lateinit var statusLine: TextView
    private lateinit var statusDetail: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logBox: LinearLayout
    private lateinit var logButton: Button
    private lateinit var copyButton: Button

    private var projectUri: Uri? = null
    private var lastFailure = ""
    private var polling = false
    private var logVisible = false
    private var iconSource: Bitmap? = null

    private val PICK_FOLDER = 9001
    private val PICK_IMAGE = 9002

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** ui.post returns Boolean; this keeps expression bodies returning Unit. */
    private fun post(block: () -> Unit) { ui.post(block) }

    // ── layout ───────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashReporter()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
        }
        root.addView(header())

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(28))
        }

        body.addView(step("1", "Repository"))
        repoButton = choice("Choose a repository") { chooseRepo() }
        body.addView(repoButton)

        body.addView(step("2", "Project folder"))
        projectButton = choice("Choose a folder") { chooseFolder() }
        body.addView(projectButton)

        body.addView(step("3", "App icon", optional = true))
        iconButton = choice("No icon set") { chooseIcon() }
        body.addView(iconButton)

        body.addView(step("4", "Build"))
        body.addView(primary("Push and build") { confirmAndPush() })
        body.addView(secondary("Check build status") { checkRuns() })

        statusLine = TextView(this).apply {
            setTextColor(soft); textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(14), 0, 0)
        }
        body.addView(statusLine)

        statusDetail = TextView(this).apply {
            setTextColor(faint); textSize = 11f
            setPadding(0, dp(2), 0, 0)
            visibility = View.GONE
        }
        body.addView(statusDetail)

        copyButton = secondary("Copy build log") { copyFailure() }
        copyButton.visibility = View.GONE
        copyButton.setOnLongClickListener { shareFailure(); true }
        body.addView(copyButton)

        // Two separate intentions: keep a copy, or change what is running.
        val installRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        installRow.addView(half("Download", accent) { fetchApk(install = false) })
        installRow.addView(half("Download & install", good) { fetchApk(install = true) })
        body.addView(installRow)

        logButton = secondary("Show log") { toggleLog() }
        body.addView(logButton)

        logBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        logView = TextView(this).apply {
            setTextColor(soft); textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(surface)
        }
        logScroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
        }
        logBox.addView(logScroll)
        logBox.addView(secondary("Clear log") { logView.text = "" })
        body.addView(logBox)

        root.addView(ScrollView(this).apply {
            addView(body, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        setContentView(root)
        restore()
        loadSavedIcon()
        showLastCrash()
    }

    private fun header(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(10), dp(10))
        }
        bar.addView(TextView(this).apply {
            text = "VibeForge"
            setTextColor(ink); textSize = 24f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(Button(this).apply {
            text = "Settings"
            isAllCaps = false; textSize = 13f
            setTextColor(accent)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showSettings() }
        })
        return bar
    }

    private fun step(number: String, title: String, optional: Boolean = false) = TextView(this).apply {
        text = "$number · ${title.uppercase(Locale.ROOT)}" + if (optional) "   optional" else ""
        setTextColor(accent); textSize = 11f
        typeface = Typeface.MONOSPACE
        setPadding(0, dp(22), 0, dp(8))
    }

    private fun choice(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false; textSize = 15f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(ink)
        setBackgroundColor(surface)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun primary(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false; textSize = 16f
        setTextColor(Color.WHITE)
        setBackgroundColor(brass)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun secondary(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false; textSize = 13f
        setTextColor(soft)
        setBackgroundColor(Color.TRANSPARENT)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun half(label: String, colour: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false; textSize = 14f
        setTextColor(Color.WHITE)
        setBackgroundColor(colour)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun toggleLog() {
        logVisible = !logVisible
        logBox.visibility = if (logVisible) View.VISIBLE else View.GONE
        logButton.text = if (logVisible) "Hide log" else "Show log"
    }

    // ── configuration ────────────────────────────────────────────────────────

    private fun restore() {
        io.execute {
            val owner = runCatching { Store.get(this, Store.OWNER) }.getOrDefault("")
            val repo = runCatching { Store.get(this, Store.REPO) }.getOrDefault("")
            val branch = runCatching { Store.get(this, Store.BRANCH, "main") }.getOrDefault("main")
            val saved = runCatching { Store.get(this, Store.PROJECT_URI) }.getOrDefault("")
            post {
                if (owner.isNotEmpty() && repo.isNotEmpty()) {
                    repoButton.text = "$owner/$repo   ·   $branch"
                }
                if (saved.isNotEmpty()) {
                    projectUri = Uri.parse(saved)
                    projectButton.text = runCatching {
                        Project.folderName(this, projectUri!!)
                    }.getOrDefault("(saved folder)")
                }
            }
        }
    }

    private fun showSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        fun caption(text: String) = content.addView(TextView(this).apply {
            this.text = text
            setTextColor(soft); textSize = 12f
            setPadding(0, dp(10), 0, dp(4))
        })

        caption("GitHub personal access token")
        val tokenField = EditText(this).apply {
            setText(Store.get(this@MainActivity, Store.TOKEN))
            hint = "github_pat_…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setBackgroundColor(surface)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        content.addView(tokenField)
        content.addView(TextView(this).apply {
            text = "Settings → Developer settings → Personal access tokens → Fine-grained. " +
                   "Needs Contents: read and write, plus Actions: read."
            setTextColor(faint); textSize = 11f
            setPadding(0, dp(4), 0, 0)
        })

        caption("Branch")
        val branchField = EditText(this).apply {
            setText(Store.get(this@MainActivity, Store.BRANCH, "main"))
            setBackgroundColor(surface)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        content.addView(branchField)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Save") { _, _ ->
                val token = tokenField.text.toString().trim()
                val branch = branchField.text.toString().trim().ifEmpty { "main" }
                io.execute {
                    Store.put(this, Store.TOKEN, token)
                    Store.put(this, Store.BRANCH, branch)
                    post { say("Settings saved."); restore() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── step 1: repository ───────────────────────────────────────────────────

    private fun api(): GitHub? {
        val token = Store.get(this, Store.TOKEN)
        if (token.isBlank()) {
            say("Add a GitHub token in Settings first.")
            showSettings()
            return null
        }
        return GitHub(token)
    }

    private fun chooseRepo() {
        val gh = api() ?: return
        setStatus("Loading your repositories…")
        io.execute {
            try {
                val repos = gh.repos()
                post { setStatus(""); showRepoPicker(repos) }
            } catch (e: Exception) { report(e) }
        }
    }

    private fun showRepoPicker(repos: List<GitHub.Repo>) {
        val labels = mutableListOf("＋   New repository…")
        repos.forEach {
            labels.add("${it.name}${if (it.private) "   (private)" else ""}\n     pushed ${it.pushedAt}")
        }
        AlertDialog.Builder(this)
            .setTitle("Repository")
            .setItems(labels.toTypedArray()) { _, index ->
                if (index == 0) promptNewRepo() else selectRepo(repos[index - 1])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectRepo(repo: GitHub.Repo) {
        io.execute {
            Store.put(this, Store.OWNER, repo.owner)
            Store.put(this, Store.REPO, repo.name)
            if (Store.get(this, Store.BRANCH).isBlank()) Store.put(this, Store.BRANCH, repo.defaultBranch)
            val branch = Store.get(this, Store.BRANCH, repo.defaultBranch)
            post {
                repoButton.text = "${repo.owner}/${repo.name}   ·   $branch"
                say("Repository: ${repo.owner}/${repo.name}")
            }
        }
    }

    private fun promptNewRepo() {
        val field = EditText(this).apply {
            hint = "my-new-app"
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle("New repository")
            .setMessage("Created public, under your account, with a starting commit.")
            .setView(field)
            .setPositiveButton("Create") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val gh = api() ?: return@setPositiveButton
                setStatus("Creating $name…")
                io.execute {
                    try {
                        val full = gh.createRepo(name, private = false)
                        say("Created $full")
                        selectRepo(GitHub.Repo(name, full.substringBefore('/'), false, "just now", "main"))
                        post { setStatus("") }
                    } catch (e: Exception) { report(e) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── step 2: folder ───────────────────────────────────────────────────────

    private fun chooseFolder() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
            PICK_FOLDER)
    }

    // ── step 3: icon ─────────────────────────────────────────────────────────

    private fun chooseIcon() {
        if (iconSource == null) { pickImage(); return }
        AlertDialog.Builder(this)
            .setTitle("App icon")
            .setMessage("An icon is ready and will be written into the project on the next push.")
            .setPositiveButton("Choose another") { _, _ -> pickImage() }
            .setNeutralButton("Remove") { _, _ -> clearIcon() }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun pickImage() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }, PICK_IMAGE)
    }

    /**
     * Frame the icon by moving the picture under a fixed square.
     *
     * The sliders this replaces were the kind of thing that reads fine in code
     * and is miserable in the hand — you cannot see what you are doing while
     * dragging a bar underneath the image.
     */
    private fun showCropDialog(source: Bitmap) {
        val crop = CropView(this, source)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(crop, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(340)))
            addView(TextView(this@MainActivity).apply {
                text = "Drag to move, pinch to zoom. The circle shows what a launcher will keep."
                setTextColor(faint); textSize = 11f
                setPadding(dp(20), dp(8), dp(20), dp(12))
            })
        }

        AlertDialog.Builder(this)
            .setTitle("Frame the icon")
            .setView(content)
            .setPositiveButton("Use this") { _, _ ->
                val bitmap = crop.result()
                iconSource = bitmap
                saveIcon(bitmap)
                showIconState()
                say("Icon set — five densities plus an adaptive icon will be generated on push.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Keep the chosen icon on disk.
     *
     * Held only in a field it vanished whenever Android recreated the activity
     * — after a rotation, or after the file picker took the app out of memory
     * — and the next push silently carried no icon at all.
     */
    private fun saveIcon(bitmap: Bitmap) {
        try {
            java.io.File(filesDir, "icon.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (e: Exception) { say("Could not store the icon: ${e.message}") }
    }

    private fun loadSavedIcon() {
        val file = java.io.File(filesDir, "icon.png")
        if (!file.exists()) return
        iconSource = try {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) { null }
        showIconState()
    }

    private fun clearIcon() {
        iconSource = null
        java.io.File(filesDir, "icon.png").delete()
        showIconState()
        say("Icon cleared.")
    }

    /** Show the actual picture on the button, so "is it set?" needs no answer. */
    private fun showIconState() {
        val icon = iconSource
        if (icon == null) {
            iconButton.text = "No icon set"
            iconButton.setCompoundDrawables(null, null, null, null)
            return
        }
        iconButton.text = "  Icon ready — added on next push"
        val side = dp(34)
        val thumb = android.graphics.drawable.BitmapDrawable(
            resources, Bitmap.createScaledBitmap(icon, side, side, true))
        thumb.setBounds(0, 0, side, side)
        iconButton.setCompoundDrawables(thumb, null, null, null)
    }

    // ── step 4: push ─────────────────────────────────────────────────────────

    /**
     * Describe the damage before doing it.
     *
     * Pushing into a repository that already has files replaces them, and on a
     * phone there is no `git diff` to check first. So: count what is there,
     * count what would be replaced, and name the files.
     */
    private fun confirmAndPush() {
        val gh = api() ?: return
        val uri = projectUri ?: run { say("Choose a project folder first."); return }
        val owner = Store.get(this, Store.OWNER)
        val repo = Store.get(this, Store.REPO)
        val branch = Store.get(this, Store.BRANCH, "main")
        if (owner.isBlank() || repo.isBlank()) { say("Choose a repository first."); return }

        setStatus("Reading folder and checking the repository…")
        io.execute {
            try {
                val scan = Project.read(this, uri)
                if (scan.files.isEmpty()) { post { setStatus("Nothing to push.") }; return@execute }
                val existing = try { gh.treeSummary(owner, repo, branch) } catch (e: Exception) { emptySet<String>() }
                val overwritten = scan.files.keys.filter { it in existing }
                val added = scan.files.size - overwritten.size
                val untouched = existing.size - overwritten.size

                post {
                    val message = buildString {
                        append("$owner/$repo · $branch\n\n")
                        append("${scan.files.size} file(s), ${scan.totalBytes / 1024} KB\n")
                        append("   $added new\n")
                        append("   ${overwritten.size} replaced\n")
                        if (untouched > 0) append("   $untouched left alone in the repo\n")
                        if (iconSource != null) {
                            append("\nIcon: 17 files will be generated and added.\n")
                        } else {
                            append("\nNo icon set — the app will use Android's default.\n")
                        }
                        if (overwritten.isNotEmpty()) {
                            append("\nReplacing:\n")
                            overwritten.take(8).forEach { append("   $it\n") }
                            if (overwritten.size > 8) append("   …and ${overwritten.size - 8} more\n")
                        }
                        if (scan.skipped.isNotEmpty()) {
                            append("\nSkipping ${scan.skipped.size} item(s): build output, archives, large files.")
                        }
                    }
                    AlertDialog.Builder(this)
                        .setTitle(if (overwritten.isEmpty()) "Push" else "Replace ${overwritten.size} file(s)?")
                        .setMessage(message)
                        .setPositiveButton("Push") { _, _ -> doPush(gh, owner, repo, branch, scan) }
                        .setNegativeButton("Cancel") { _, _ -> setStatus("") }
                        .show()
                }
            } catch (e: Exception) { report(e) }
        }
    }

    private fun doPush(gh: GitHub, owner: String, repo: String, branch: String, scan: Project.Scan) {
        lastFailure = ""
        copyButton.visibility = View.GONE
        setStatus("Pushing…")
        io.execute {
            try {
                val files = LinkedHashMap(scan.files)

                val icon = iconSource
                if (icon != null) {
                    val generated = IconMaker.generate(icon, IconMaker.edgeColour(icon))
                    files.putAll(generated.files)
                    // Icons in the tree do nothing unless the manifest points
                    // at them, and most templates never do.
                    val manifestPath = files.keys.firstOrNull { it.endsWith("AndroidManifest.xml") }
                    if (manifestPath == null) {
                        say("No AndroidManifest.xml in this folder — the icon files went up but nothing points at them.")
                    }
                    if (manifestPath != null) {
                        val patched = IconMaker.patchManifest(String(files[manifestPath]!!))
                        if (patched != null) {
                            files[manifestPath] = patched.toByteArray()
                            say("Manifest updated: android:icon added.")
                        } else {
                            say("Manifest already names an icon — make sure it is @mipmap/ic_launcher.")
                        }
                    }
                    say(generated.note)
                    say("First icon path: ${generated.files.keys.first()}")
                }

                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                val sha = gh.pushFolder(owner, repo, branch, files, "VibeForge push $stamp") { done, total, path ->
                    post { setStatus("Uploading $done/$total"); setDetail(path) }
                }
                Store.put(this, Store.LAST_COMMIT, sha)
                say("Pushed ${sha.take(7)} — ${files.size} file(s).")

                if (icon != null) {
                    val inRepo = try { gh.treeSummary(owner, repo, branch) } catch (e: Exception) { emptySet<String>() }
                    val icons = inRepo.count { it.contains("/mipmap-") }
                    val manifest = inRepo.firstOrNull { it.endsWith("AndroidManifest.xml") }
                    val declared = manifest != null &&
                        files[manifest]?.let { String(it).contains("android:icon=") } == true
                    say(if (icons > 0) "Verified: $icons icon file(s) are in the repository."
                        else "Warning: no mipmap files found in the repository after the push.")
                    say(if (declared) "Verified: the manifest declares android:icon."
                        else "Warning: the manifest does not declare android:icon — the launcher will use the default.")
                }
                post { setDetail("Actions should start within a few seconds.") }
                ui.postDelayed({ checkRuns() }, 6000)
            } catch (e: Exception) { report(e) }
        }
    }

    private fun checkRuns() {
        val gh = api() ?: return
        val owner = Store.get(this, Store.OWNER)
        val repo = Store.get(this, Store.REPO)
        if (owner.isBlank() || repo.isBlank()) { say("Choose a repository first."); return }

        io.execute {
            try {
                val runs = gh.latestRuns(owner, repo, 3)
                if (runs.isEmpty()) { post { setStatus("No workflow runs yet.") }; return@execute }
                val r = runs.first()

                when {
                    r.status != "completed" -> {
                        post { setStatus("Building…  ${r.sha}"); setDetail(r.status) }
                        if (!polling) {
                            polling = true
                            ui.postDelayed({ polling = false; checkRuns() }, 15000)
                        }
                    }
                    r.conclusion == "success" -> {
                        say("Build succeeded (${r.sha}).")
                        post {
                            setStatus("Build succeeded  ·  ${r.sha}")
                            statusLine.setTextColor(good)
                            setDetail("Ready to download.")
                        }
                    }
                    else -> {
                        post { setStatus("Build ${r.conclusion} — fetching the log…") }
                        val detail = gh.failureReport(owner, repo, r.id)
                        lastFailure = "Build ${r.conclusion} for ${r.sha}\n${r.url}\n\n$detail"
                        say(detail)
                        post {
                            setStatus("Build failed  ·  ${r.sha}")
                            statusLine.setTextColor(bad)
                            setDetail("Copy the log and send it to whoever writes the code.")
                            copyButton.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) { report(e) }
        }
    }

    // ── install ──────────────────────────────────────────────────────────────

    private fun fetchApk(install: Boolean) {
        val gh = api() ?: return
        val owner = Store.get(this, Store.OWNER)
        val repo = Store.get(this, Store.REPO)
        val token = Store.get(this, Store.TOKEN)
        if (owner.isBlank() || repo.isBlank()) { say("Choose a repository first."); return }
        setStatus("Looking for a release…")

        io.execute {
            try {
                val apks = gh.latestApks(owner, repo)
                if (apks.isEmpty()) {
                    post { setStatus("No APK in the latest releases.") }
                    say("Does the workflow publish one? Check its Collect APK step.")
                    return@execute
                }
                val apk = apks.first()
                if (apks.size > 1) {
                    say("${apks.size} APKs on the release — taking the newest (${apk.updated.take(16)}).")
                    say("Older ones are still there; a build that names its APK after the commit " +
                        "adds a file each time instead of replacing it.")
                }
                say("Downloading ${apk.name}, uploaded ${apk.updated.take(16)} (${apk.size / 1024} KB)…")
                val file = Installer.download(this, apk.url, token) { read, total ->
                    if (total > 0) post { setStatus("Downloading ${read * 100 / total}%") }
                }
                if (install) {
                    say("Opening the installer…")
                    post { setStatus("Ready to install"); Installer.install(this, file) }
                } else {
                    val saved = Installer.keep(this, file, apk.name)
                    say("Saved to $saved")
                    post { setStatus("Downloaded  ·  ${apk.name}"); setDetail(saved) }
                }
            } catch (e: Exception) { report(e) }
        }
    }

    // ── results ──────────────────────────────────────────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            PICK_FOLDER -> {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                projectUri = uri
                val name = Project.folderName(this, uri)
                projectButton.text = name
                io.execute { Store.put(this, Store.PROJECT_URI, uri.toString()) }
                say("Folder: $name")
            }
            PICK_IMAGE -> {
                val bitmap = IconMaker.load(this, uri)
                if (bitmap == null) say("Could not read that image.") else showCropDialog(bitmap)
            }
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private fun report(e: Exception) {
        val message = if (e is GitHub.ApiError) {
            val hint = when (e.status) {
                401 -> "the token is wrong or expired"
                403 -> "the token lacks permission, or you hit a rate limit"
                404 -> "check the repository exists and the token can see it"
                409 -> "the branch moved since you started; push again"
                422 -> "GitHub rejected the contents; usually an empty repo with no branch"
                else -> ""
            }
            "GitHub ${e.status}" + if (hint.isNotEmpty()) " — $hint" else ""
        } else e.message ?: e.toString()

        post { setStatus(message); statusLine.setTextColor(bad) }
        say(message)
        if (e is GitHub.ApiError) say(e.body.take(300))
    }

    private fun say(text: String) = post {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logView.append("$time  $text\n")
        // Only chase the tail when the log is on screen; scrolling a hidden
        // view is what made the page jump around while you were reading it.
        if (logVisible) logScroll.post {
            val child = logScroll.getChildAt(0)
            if (child != null) logScroll.scrollTo(0, maxOf(0, child.height - logScroll.height))
        }
    }

    private fun setStatus(text: String) {
        statusLine.text = text
        statusLine.setTextColor(soft)
        if (text.isEmpty()) setDetail("")
    }

    private fun setDetail(text: String) {
        statusDetail.text = text
        statusDetail.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun copyFailure() {
        val text = lastFailure.ifBlank { logView.text.toString() }
        val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clip.setPrimaryClip(android.content.ClipData.newPlainText("Build failure", text))
        Toast.makeText(this, "Copied — paste it to whoever writes the code", Toast.LENGTH_LONG).show()
    }

    private fun shareFailure() {
        val text = lastFailure.ifBlank { logView.text.toString() }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "VibeForge build failure")
        }, "Send the build log"))
    }

    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                java.io.File(filesDir, "last-crash.txt").writeText(
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                    "  thread=${thread.name}\n" + android.util.Log.getStackTraceString(error))
            } catch (e: Throwable) { /* nothing useful left to do */ }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun showLastCrash() {
        val file = java.io.File(filesDir, "last-crash.txt")
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrDefault("")
        file.delete()
        if (text.isBlank()) return
        say("Previous run crashed:\n$text")
        AlertDialog.Builder(this)
            .setTitle("VibeForge crashed last time")
            .setMessage(text.take(2000))
            .setPositiveButton("Copy") { _, _ ->
                val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }
}
