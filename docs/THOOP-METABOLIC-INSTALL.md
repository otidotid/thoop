# Install the THOOP metabolic update on Windows

This creates `C:\Projects\THOOP-metabolic-v3` and updates your existing debug app in place. Keep `thoop-recovery`, `THOOP-lab-ai-v2` and the installed app. Do not uninstall THOOP or clear its app data. The matching signing key from your working v2 build is required to preserve the in-place update path.

## 1. Create the separate checkout

Open PowerShell and paste this block. If the new folder or branch already exists, stop and use that checkout; do not delete or reset it.

```powershell
$previousAndroid = 'C:\Projects\THOOP-lab-ai-v2\android'
$newProject = 'C:\Projects\THOOP-metabolic-v3'
if (Test-Path -LiteralPath $newProject) { throw 'THOOP-metabolic-v3 already exists. Keep it and stop here.' }
foreach ($privateFile in @('local.properties', 'fork-debug.keystore')) {
    if (!(Test-Path -LiteralPath (Join-Path $previousAndroid $privateFile))) {
        throw "Missing v2 file: $privateFile. Stop and locate the configuration/signing key used for your installed v2 app."
    }
}
Set-Location 'C:\Projects\thoop-recovery'
git fetch origin feature/lab-marks-ai-integration-v1
if ($LASTEXITCODE -ne 0) { throw 'Fetch failed; stop here.' }
git worktree add -b review/metabolic-v3 $newProject origin/feature/lab-marks-ai-integration-v1
if ($LASTEXITCODE -ne 0) { throw 'Worktree creation failed; stop here.' }
Copy-Item -LiteralPath (Join-Path $previousAndroid 'local.properties') -Destination (Join-Path $newProject 'android\local.properties')
Copy-Item -LiteralPath (Join-Path $previousAndroid 'fork-debug.keystore') -Destination (Join-Path $newProject 'android\fork-debug.keystore')
Set-Location (Join-Path $newProject 'android')
```

These two copied files stay private and are ignored by Git. `local.properties` preserves your Android SDK and Cloud Coach endpoint settings. The keystore must be the one used to sign the app currently on your phone.

## 2. Build and test

In Android Studio, choose **Open** and select `C:\Projects\THOOP-metabolic-v3\android`. Let Gradle sync finish. Select the `fullDebug` build variant. In its Terminal, use PowerShell and run:

```powershell
Set-Location 'C:\Projects\THOOP-metabolic-v3\android'
.\gradlew.bat testFullDebugUnitTest
if ($LASTEXITCODE -ne 0) { throw 'Tests failed; do not install this build.' }
.\gradlew.bat assembleFullDebug --no-build-cache --rerun-tasks
if ($LASTEXITCODE -ne 0) { throw 'Build failed; do not install this build.' }
```

Both commands must finish with **BUILD SUCCESSFUL**. These are instructions to verify your build, not a claim that your local checks have already run.

## 3. Update the phone without deleting its records

Connect your phone with USB debugging enabled. Run:

```powershell
$adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adbPath devices
```

Use the serial shown next to your phone. Its status must be `device`.

```powershell
$phoneSerial = Read-Host 'Phone serial from adb devices'
& $adbPath -s $phoneSerial install -r 'C:\Projects\THOOP-metabolic-v3\android\app\build\outputs\apk\full\debug\app-full-debug.apk'
```

Wait for **Success**, then open THOOP and check that your existing history is present. If Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, stop: the signing key does not match. Build with the original matching key; do not uninstall the app to bypass this error.

## 4. Update the existing Cloudflare Worker

Use the separately supplied **THOOP-Cloud-Coach-v3-worker.js** from the delivery's `outputs` folder. It is the complete single-file bundle for dashboard pasting; the repository's `cloud-coach/worker.js` alone imports other files.

In Cloudflare, open your existing **thoop-cloud-coach** Worker, choose **Edit code**, paste the entire supplied bundle and deploy. Keep its existing **AI** and **DB** bindings and other settings.

Open that Worker's **Settings → Variables and Secrets** (your dashboard may say **Runtime variables and secrets**). In **Production**, retain the existing flag and add the second flag below as plain text variables, then deploy the settings:

| Name | Value |
| --- | --- |
| `ENABLE_LAB_INTERPRETATION` | `true` |
| `ENABLE_METABOLIC_ESTIMATE` | `true` |

In the “Add environment variable” form, enter the name in **Key**, `true` in **Value**, leave **Secret** unchecked and select **Add variable and deploy**. See [Cloudflare's environment-variable instructions](https://developers.cloudflare.com/workers/configuration/environment-variables/).

## 5. Enable and use the feature in THOOP

The new AI metabolic feature starts **off**. Enable these controls only if you want the described evidence sent to Cloud Coach:

1. **More → Settings → Health & wellness → Share on-device signals with the Coach**: on.
2. **Coach → tuning/sliders icon → Coach settings → Let the coach use my data**: on.
3. On the same **Coach settings** screen, **Share exact Lab Book evidence**: on. This includes original values, units, dates, sources, sample context, notes and reported ranges.
4. On the same screen, **AI metabolic estimate**: on. Tap **Review labs and update Fitness Age**.

If Coach says it is not configured, check that the copied `local.properties` contains the working `THOOP_CLOUD_COACH_URL`, then rebuild. For missing lab evidence, open **More → Lab Book** and review your existing entries.

The review uses the latest reading for each marker **on or before the current weekly score date: the most recent Saturday**. A reading dated after that Saturday becomes eligible for the next weekly score. Fitness Age still needs sufficient resting-heart-rate history; AI does not bypass that gate.

When a valid estimate is accepted, Fitness Age uses **80% of the existing fitness-domain age + 20% of (chronological age + AI metabolic delta)**. The delta is limited to **0–4 years**. It replaces the local body-metabolic component and the separate custom-glucose addition, avoiding double counting. The Fitness Age detail shows **AI METABOLIC ESTIMATE** and **AI · Experimental**; the displayed delta is the raw metabolic input, not the final age change.

This is an experimental wellness estimate, not a validated clinical conversion. A saved estimate expires after **30 days**, or becomes unusable when its source readings or age/sex profile change. Without a current accepted estimate, missing consent, invalid output, unavailable AI or an oversized request leaves the existing local calculation active. Lab interpretation uses safe batches; the metabolic estimate uses one bounded latest-marker snapshot rather than averaging batch scores. Original lab records remain unchanged, and AI cannot alter THOOP's formula or weights.

To stop using it, switch **Coach settings → AI metabolic estimate** off. THOOP recalculates the current Fitness Age locally when enough history is available; otherwise the saved score remains until a new result can be computed. Original readings and the experiment history are retained.
