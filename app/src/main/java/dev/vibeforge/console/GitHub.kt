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

    /** Failure logs, trimmed to the lines that explain what went wrong. */
    fun failureLog(owner: String, repo: String, runId: Long, maxLines: Int = 60): String {
        val jobs = JSONObject(get("${base(owner, repo)}/actions/runs/$runId/jobs"))
            .optJSONArray("jobs") ?: return "(no jobs)"
        val sb = StringBuilder()
        for (i in 0 until jobs.length()) {
            val job = jobs.getJSONObject(i)
            if (job.optString("conclusion") == "success") continue
            sb.append("job: ${job.optString("name")}\n")
            val steps = job.optJSONArray("steps") ?: continue
            for (j in 0 until steps.length()) {
                val step = steps.getJSONObject(j)
                if (step.optString("conclusion") !in listOf("failure", "cancelled")) continue
                sb.append("  ✕ ${step.optString("name")}\n")
            }
        }
        // Raw logs come back as a zip, which is more machinery than this is
        // worth on a phone; the failing step name plus the run link gets you
        // to the answer nearly as fast.
        return if (sb.isEmpty()) "(no failing step reported yet)" else sb.toString().lines()
            .take(maxLines).joinToString("\n")
    }

    // ── releases ─────────────────────────────────────────────────────────────

    data class Asset(val name: String, val url: String, val size: Long)

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
                        size = it.optLong("size")
                    )
                }
            if (apks.isNotEmpty()) return apks
        }
        return emptyList()
    }
}
