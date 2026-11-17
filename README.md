# Navic (build fork)

A personal build fork of [Navic](https://codeberg.org/paige/Navic) — a Subsonic / Navidrome
music streaming client for Android.

**Upstream is at [codeberg.org/paige/Navic](https://codeberg.org/paige/Navic).** This
repository is not the upstream project, is not affiliated with it, and does not accept
issues or pull requests about Navic itself. Go upstream for that.

---

## Why this exists

Not a rebrand, and not a redistribution: it is one experiment.

Navic already publishes a proper media session — `ExoPlaybackService` is an exported
`androidx.media3.session.MediaSessionService` with a `MediaBrowserService` intent filter and a
registered `MediaButtonReceiver`. On HarmonyOS it still never shows up in the media control
centre.

So the open question is *what HarmonyOS keys off*. This fork changes the install identity to a
package name that HarmonyOS recognises as a media app, and the answer only exists on the device:

| Hypothesis | Verdict |
|---|---|
| HarmonyOS matches on package name | what this fork tests |
| HarmonyOS also validates the signing certificate | if true, this fork changes nothing |

Nothing here claims to get around a signature check. The APK is signed with a throwaway
self-signed key (see [Signing](#signing)), so its signature proves nothing and matches nothing.

## What differs from upstream

Three commits, deliberately kept separate so upstream rebases stay clean:

| Change | File | Why |
|---|---|---|
| `applicationId` → `fm.qingting.qtradio`<br>`versionCode` 11000400, `versionName` 11.0.4 | `app/build.gradle.kts` | The experiment. The real app must be uninstalled first — same package, different signature, so they cannot coexist. |
| `abiFilters` → `arm64-v8a` only | `app/build.gradle.kts` | Every extra ABI is another full FFmpeg cross-compile. |
| Heaps 8g/8g → 4g/2g | `gradle.properties` | Upstream asks for 16 GB of JVM heap in total; the CI runner has 16 GB of RAM. |
| New workflow | `.github/workflows/build-arm64.yml` | Not upstream. Builds the **fdroid** flavour's release variant. |

The rebrand really is just that one `applicationId` line. Every identifier in
`AndroidManifest.xml` that needs to be unique — the playback permission, the `fileprovider`
authority, the launcher activity aliases, the `NOW_PLAYING_UPDATED` broadcast — is written as
`${applicationId}`, and the Kotlin code reads `context.packageName` at runtime. No source
package renames, no directory moves.

Two details worth knowing before changing them back:

- **fdroid flavour, not default.** `BuildConfig.FDROID` gates off the upstream update checker.
  That checker parses digit-runs out of the release tag and compares digit *counts* against the
  local `versionName`, so a spoofed `11.0.4` never equals upstream's tag and would report "update
  available" on every launch forever.
- **Release, not debug.** A debug APK signed by the runner's throwaway keystore gets a different
  signature on every build, so each rebuild would have to be uninstalled first — which takes the
  app's data with it.

## Building

### Locally

Upstream prerequisites apply (JDK 21, Android SDK, NDK, a C toolchain for FFmpeg) — see
[.forgejo/BUILDING.md](.forgejo/BUILDING.md). Two things to know:

- Submodules are build inputs, not optional: `vendor/androidx-media` is an
  `includeBuild` and is ~775 MB.
- `gradle.properties` here is tuned for a 16 GB CI runner. On a smaller machine, lower the heaps
  again.

```sh
git clone --recurse-submodules https://github.com/zglinus-for-agent/Navic.git
cd Navic
# signing material is not in the repo; either supply it or build unsigned
./gradlew :app:assembleFdroidRelease
```

### On GitHub Actions

Actions → **build-arm64** → Run workflow. The APK arrives as the `navic-arm64-release` artifact.
The final step dumps `package:`, `application-label:`, `native-code:` and `sdkVersion:` out of
the built APK with `aapt2 dump badging`, so the artifact is verified rather than assumed.

Builds take a while: the first run cross-compiles FFmpeg from source.

## Signing

Signing material is **not** in this repository (`.gitignore` excludes `signing/*.jks` and
`signing/keystore.properties`). CI reconstructs the keystore from three Actions secrets:

| Secret | Contents |
|---|---|
| `SIGNING_KEYSTORE_B64` | base64 of the `.jks` |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_PASSWORD` | key password |

The key is a throwaway self-signed RSA-4096, alias `navic`, valid 10000 days. Its only purpose is
a *stable* signature so rebuilds upgrade in place. It is not trust material, it is not the
author's key, and the APK it produces should not be trusted by anyone but its owner.

## Installing

```sh
adb install -r Navic.fdroid.apk
```

Uninstall the real `fm.qingting.qtradio` first, if it is installed — Android will refuse the
install otherwise, because the signatures differ.

## Licence

[GPL-3.0-only](LICENSE), unchanged from upstream. The original copyright is the author's; the
changes here are trivial and are offered under the same licence. Source for every build
produced from this repository is this repository.
