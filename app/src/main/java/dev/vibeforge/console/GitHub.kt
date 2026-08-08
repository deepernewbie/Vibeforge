package dev.vibeforge.console

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Everything VibeForge needs from GitHub, over plain HttpURLConnection.
 *
 * No HTTP library on purpose: one fewer dependency to resolve on a phone
 * build, and the API surface here is small enough that a client buys nothing.
 *
 * Pushing uses the Git Data API rather than the Contents API. The Contents
 * route means one commit per file — twenty files, twenty commits, twenty
 * chances for Actions to fire mid-upload and build a half-written tree. This
 * way every file becomes a blob, the blobs become one tree, the tree becomes
 * one commit, and the branch moves once.
 */
class GitHub(private val token: String) {

    class ApiError(val status: Int, val body: String) :
        Exception("GitHub $status: ${body.take(300)}")

    private fun request(
        method: String,
        url: String,
        body: String? = null,
        accept: String = "application/vnd.github+json"
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "VibeForge")
            connectTimeout = 20000
            readTimeout = 60000
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        body?.let { conn.outputStream.use { os -> os.write(it.toByteArray()) } }

        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""
        conn.disconnect()
        if (status !in 200..299) throw ApiError(status, text)
        return text
    }

    private fun get(url: String) = request("GET", url)
    private fun delete(url: String) = request("DELETE", url)
    private fun post(url: String, body: JSONObject) = request("POST", url, body.toString())
    private fun patch(url: String, body: JSONObject) = request("PATCH", url, body.toString())

    // ── identity ─────────────────────────────────────────────────────────────

    fun whoAmI(): String = JSONObject(get("https://api.github.com/user")).optString("login")

    fun repoExists(owner: String, repo: String): Boolean = try {
        get("https://api.github.com/repos/$owner/$repo"); true
    } catch (e: ApiError) {
        if (e.status == 404) false else throw e
    }

    /** Creates the repo under the authenticated user. Public by default. */
    fun createRepo(repo: String, private: Boolean = false): String {
        val body = JSONObject()
            .put("name", repo)
            .put("private", private)
            .put("auto_init", true)
            .put("description", "Built from a phone with VibeForge")
        return JSONObject(post("https://api.github.com/user/repos", body)).optString("full_name")
    }

    /** The user's repositories, newest activity first, for the picker. */
    fun repos(limit: Int = 100): List<Repo> {
        val arr = JSONArray(get(
            "https://api.github.com/user/repos?per_page=$limit&sort=pushed&affiliation=owner"))
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            Repo(
                name = r.optString("name"),
                owner = r.optJSONObject("owner")?.optString("login") ?: "",
                private = r.optBoolean("private"),
                pushedAt = r.optString("pushed_at").take(10),
                defaultBranch = r.optString("default_branch", "main")
            )
        }
    }

    data class Repo(
        val name: String, val owner: String, val private: Boolean,
        val pushedAt: String, val defaultBranch: String
    )

    /** What is already in a repo, so an overwrite can be described before it happens. */
    fun treeSummary(owner: String, repo: String, branch: String): Set<String> {
        val head = branchHead(owner, repo, branch) ?: return emptySet()
        val treeSha = JSONObject(get("${base(owner, repo)}/git/commits/$head"))
            .getJSONObject("tree").getString("sha")
        val tree = JSONObject(get("${base(owner, repo)}/git/trees/$treeSha?recursive=1"))
            .optJSONArray("tree") ?: return emptySet()
        return (0 until tree.length())
            .map { tree.getJSONObject(it) }
            .filter { it.optString("type") == "blob" }
            .map { it.optString("path") }
            .toSet()
    }

    // ── pushing ──────────────────────────────────────────────────────────────

    private fun base(owner: String, repo: String) = "https://api.github.com/repos/$owner/$repo"

    fun branchHead(owner: String, repo: String, branch: String): String? = try {
        JSONObject(get("${base(owner, repo)}/git/ref/heads/$branch"))
            .getJSONObject("object").getString("sha")
    } catch (e: ApiError) {
        if (e.status == 404) null else throw e
    }

    private fun createBlob(owner: String, repo: String, bytes: ByteArray): String {
        val body = JSONObject()
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("encoding", "base64")
        return JSONObject(post("${base(owner, repo)}/git/blobs", body)).getString("sha")
    }

    /**
     * Commit an entire folder in one go.
     *
     * @param files path → bytes, paths relative to the repo root
     * @param onProgress called per blob so the UI can show movement on a slow
     *        connection; uploading twenty files in silence looks like a hang
     */
    fun pushFolder(
        owner: String,
        repo: String,
        branch: String,
        files: Map<String, ByteArray>,
        message: String,
        onProgress: (done: Int, total: Int, path: String) -> Unit = { _, _, _ -> }
    ): String {
        if (files.isEmpty()) throw IllegalArgumentException("Nothing to push")

        val parent = branchHead(owner, repo, branch)
        val baseTree = parent?.let {
            JSONObject(get("${base(owner, repo)}/git/commits/$it")).getJSONObject("tree").getString("sha")
        }

        val tree = JSONArray()
        var done = 0
        for ((path, bytes) in files) {
            onProgress(done, files.size, path)
            val sha = createBlob(owner, repo, bytes)
            tree.put(JSONObject()
                .put("path", path)
                .put("mode", "100644")
                .put("type", "blob")
                .put("sha", sha))
            done++
        }
        onProgress(done, files.size, "building tree")

        val treeBody = JSONObject().put("tree", tree)
        // With a base_tree, files not in this push are left alone — which is
        // what you want when the repo also holds things VibeForge never sees.
        if (baseTree != null) treeBody.put("base_tree", baseTree)
        val treeSha = JSONObject(post("${base(owner, repo)}/git/trees", treeBody)).getString("sha")

        val commitBody = JSONObject()
            .put("message", message)
            .put("tree", treeSha)
        if (parent != null) commitBody.put("parents", JSONArray().put(parent))
        val commitSha = JSONObject(post("${base(owner, repo)}/git/commits", commitBody)).getString("sha")

        val refUrl = "${base(owner, repo)}/git/refs/heads/$branch"
        if (parent == null) {
            post("${base(owner, repo)}/git/refs",
                JSONObject().put("ref", "refs/heads/$branch").put("sha", commitSha))
        } else {
            patch(refUrl, JSONObject().put("sha", commitSha).put("force", false))
        }
        return commitSha
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    data class Run(
        val id: Long, val name: String, val status: String,
        val conclusion: String?, val sha: String, val url: String
    )

    fun latestRuns(owner: String, repo: String, limit: Int = 5): List<Run> {
        val json = JSONObject(get("${base(owner, repo)}/actions/runs?per_page=$limit"))
        val arr = json.optJSONArray("workflow_runs") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            Run(
                id = r.getLong("id"),
                name = r.optString("name", "workflow"),
                status = r.optString("status"),
                conclusion = r.optString("conclusion").takeIf { it.isNotEmpty() && it != "null" },
                sha = r.optString("head_sha").take(7),
                url = r.optString("html_url")
            )
        }
    }

    fun runForCommit(owner: String, repo: String, sha: String): Run? =
        latestRuns(owner, repo, 10).firstOrNull { sha.startsWith(it.sha) }

    /**
     * The actual log of the failing job.
     *
     * The run-level logs endpoint returns a zip, which is why this originally
     * settled for step names — but the *job*-level endpoint returns plain text,
     * which is all that was ever needed. It answers with a redirect to a signed
     * URL that must be fetched without the Authorization header, or the CDN
     * refuses it.
     */
    fun jobLog(owner: String, repo: String, jobId: Long): String {
        val url = "${base(owner, repo)}/actions/jobs/$jobId/logs"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "VibeForge")
            connectTimeout = 20000
            readTimeout = 60000
        }
        val status = conn.responseCode
        val location = conn.getHeaderField("Location")
        val direct = if (status in 200..299) {
            BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } else null
        conn.disconnect()
        if (direct != null) return direct
        if (location.isNullOrEmpty()) return "(no log available — status $status)"

        val signed = (URL(location).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "VibeForge")
            connectTimeout = 20000
            readTimeout = 60000
        }
        val text = try {
            BufferedReader(InputStreamReader(signed.inputStream)).use { it.readText() }
        } catch (e: Exception) {
            "(could not download the log: ${e.message})"
        }
        signed.disconnect()
        return text
    }

    /**
     * Trim a build log to the part that explains the failure.
     *
     * A Gradle log runs to thousands of lines of dependency resolution. What
     * matters is a handful: Kotlin's `e:` lines, Gradle's FAILURE block, and
     * whichever task died. Everything else is noise you would have to scroll
     * past on a phone before you could paste anything useful to anyone.
     */
    fun failureReport(owner: String, repo: String, runId: Long, maxChars: Int = 6000): String {
        val jobs = try {
            JSONObject(get("${base(owner, repo)}/actions/runs/$runId/jobs"))
                .optJSONArray("jobs") ?: JSONArray()
        } catch (e: Exception) {
            return "Could not list jobs: ${e.message}"
        }

        val report = StringBuilder()
        for (i in 0 until jobs.length()) {
            val job = jobs.getJSONObject(i)
            if (job.optString("conclusion") == "success") continue

            report.append("job: ${job.optString("name")} — ${job.optString("conclusion")}\n")
            val steps = job.optJSONArray("steps") ?: JSONArray()
            for (j in 0 until steps.length()) {
                val step = steps.getJSONObject(j)
                if (step.optString("conclusion") in listOf("failure", "cancelled")) {
                    report.append("failing step: ${step.optString("name")}\n")
                }
            }

            val log = try { jobLog(owner, repo, job.getLong("id")) } catch (e: Exception) {
                "(log fetch failed: ${e.message})"
            }
            report.append("\n").append(interesting(log)).append("\n")
        }

        if (report.isEmpty()) return "No failing job reported yet — the run may still be going."
        return report.toString().take(maxChars)
    }

    private fun interesting(log: String): String {
        // GitHub prefixes every line with an ISO timestamp. Stripping it after
        // filtering — as this did at first — means `^e:` never matches and the
        // compiler errors, the one thing worth reading, are dropped.
        val lines = log.lines().map {
            it.replace(Regex("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z\\s?"), "").trimEnd()
        }

        val marks = Regex(
            "(?i)^\\s*e: |^\\s*w: |error:|FAILURE:|> Task .*FAILED|Caused by:|Execution failed|" +
            "Unresolved reference|Type mismatch|None of the following|Cannot infer|" +
            "Overload resolution|Expecting |::error::|what went wrong"
        )

        val keep = sortedSetOf<Int>()
        lines.forEachIndexed { i, line ->
            if (marks.containsMatchIn(line)) {
                // A compiler error alone is rarely enough; the line after it
                // usually carries the caret and the explanation.
                for (k in (i - 1)..(i + 3)) if (k in lines.indices) keep.add(k)
            }
        }

        val picked = if (keep.isEmpty()) {
            // Nothing matched, so the tail is the best guess at what happened.
            lines.takeLast(50)
        } else {
            var previous = -2
            buildList {
                for (idx in keep) {
                    if (idx != previous + 1 && previous >= 0) add("   …")
                    add(lines[idx])
                    previous = idx
                }
            }
        }

        return picked.joinToString("\n").take(5000)
    }

    /** Kept for the status line — the short version. */
    fun failureLog(owner: String, repo: String, runId: Long, maxLines: Int = 60): String =
        failureReport(owner, repo, runId).lines().take(maxLines).joinToString("\n")

    // ── releases ─────────────────────────────────────────────────────────────

    data class Asset(val name: String, val url: String, val size: Long, val updated: String)

    /**
     * Delete every APK on the release except the newest.
     *
     * A build that names its output after the commit adds an asset each time
     * rather than replacing one, so the release quietly accumulates and any
     * client picking "the first" installs something weeks old. Tidying up is
     * the tool's job — nobody should have to open a browser to keep their own
     * build pipeline honest.
     */
    fun pruneReleaseAssets(owner: String, repo: String): Int {
        val releases = JSONArray(get("${base(owner, repo)}/releases?per_page=5"))
        var removed = 0
        for (i in 0 until releases.length()) {
            val assets = releases.getJSONObject(i).optJSONArray("assets") ?: continue
            val apks = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk") }
                .sortedByDescending { it.optString("updated_at").ifEmpty { it.optString("created_at") } }
            // Keep the newest; everything behind it is a trap.
            for (stale in apks.drop(1)) {
                try {
                    delete("${base(owner, repo)}/releases/assets/${stale.getLong("id")}")
                    removed++
                } catch (e: Exception) { /* a failed tidy-up must not stop an install */ }
            }
            if (apks.isNotEmpty()) break
        }
        return removed
    }

    fun latestApks(owner: String, repo: String): List<Asset> {
        val releases = JSONArray(get("${base(owner, repo)}/releases?per_page=5"))
        for (i in 0 until releases.length()) {
            val assets = releases.getJSONObject(i).optJSONArray("assets") ?: continue
            val apks = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk") }
                .map {
                    Asset(
                        name = it.getString("name"),
                        // browser_download_url works unauthenticated for public
                        // repos; private repos need the api url with an Accept
                        // of application/octet-stream, handled by the caller.
                        url = it.getString("browser_download_url"),
                        size = it.optLong("size"),
                        updated = it.optString("updated_at").ifEmpty { it.optString("created_at") }
                    )
                }
                .sortedByDescending { it.updated }
            if (apks.isNotEmpty()) return apks
        }
        return emptyList()
    }
}
