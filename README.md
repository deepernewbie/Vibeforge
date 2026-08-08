# VibeForge

Push a project folder from your phone, let GitHub Actions build it, install the
APK — without touching a computer.

## Bootstrap (the one manual step)

VibeForge cannot build itself the first time. Do this once:

1. Create a **public** repository on GitHub, e.g. `forge`.
2. Upload the contents of this folder to it. On a phone: open github.com in a
   browser, switch on "Desktop site", then **Add file → Upload files**. Keep the
   folder structure — `app/`, `.github/`, and the three gradle files at the root.
3. The **Build APK** workflow runs on its own. When it finishes, open
   **Releases → latest** and install `VibeForge-*.apk`.
   Play Protect will block the first install: Play Store → profile →
   Play Protect → turn off scanning, install, turn it back on. On Samsung also
   turn off Auto Blocker under Security and privacy.

From then on VibeForge builds itself and everything else.

## Using it

**Token.** github.com → Settings → Developer settings → Personal access tokens →
Fine-grained. Repository access: the repos you'll build. Permissions:
*Contents* read and write, *Actions* read. Paste it into VibeForge.

**Folder.** Choose the project folder on your phone. VibeForge skips `build/`,
`.gradle/`, `node_modules/`, `data/`, APKs and keystores automatically.

**Push and build.** Every file goes up as one commit — not one commit per file —
so Actions never fires against a half-written tree. Files the push doesn't
include are left alone in the repo.

**Status.** Polls every 15 seconds while a run is going. On failure it names the
step that broke and links the run.

**Install.** Downloads the newest APK from Releases and opens the installer.

## Writing a project for it

`prompt.md` in this repo is a specification you can hand to an LLM in a fresh
conversation. It covers the required layout, the workflow template, the Android
traps that only appear when nobody can attach a debugger, and how to read a
failed build. Paste it, describe the app you want, and what comes back should
push and build without a round trip.

## What a buildable project needs

A workflow at `.github/workflows/*.yml` that produces an APK and publishes it to
a release. Copy the one in this repo and change the app module if needed. If a
project has no workflow, VibeForge will push it happily and nothing will build.

## Signing

Builds are signed with a throwaway key unless you add a persistent one, and a
throwaway key means each APK refuses to install over the last — you have to
uninstall first, losing that app's data.

To fix it, generate a keystore once and add four repository secrets:
`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Any machine
with `keytool` can make one; base64 the `.jks` and paste it in.

## What this is not

It doesn't edit code — use an editor app, or unzip what someone sends you.
It doesn't build iOS. It can't get around Play Protect: sideloading stays
awkward by Google's design, and VibeForge just makes the awkward part short.
