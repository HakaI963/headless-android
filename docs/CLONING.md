# Clone engine

The cloner turns an installed app (or a picked APK) into a second, independent
installation in **user 0**. The clone has a new package name, a new signing
certificate, fresh data, no launcher icon, and it runs on the virtual display.

## Why not a cloned Android user?

Android supports multiple users (`pm create-user`), and on paper a clone user is
the cleanest isolation boundary. On the tested HyperOS/Android 16 device it
does **not** work for a headless display:

- `pm create-user` succeeds, `install-existing` installs the package, and
  `am start --user <U> --display <D>` creates the task.
- The task stays `visible=false` / `app=null`; the process is never started.
  HyperOS stops activities of every non-foreground Android user. MIUI's recents
  model then removes the invisible task.
- `am start-user --display <D> <U>` (Android's "visible background user on a
  display" API) returns **"Not supported"** on this ROM.

A shell clone user therefore installs but can never render. That is also the
failure recorded by the earlier Bliss project (round 13: cross-user launches
dropped by HyperOS). The only ways to put a clone in front of the compositor
are (a) a virtualized container such as BlackBox/Island inside user 0, or
(b) a second real installation in user 0. This project implements (b), which
is self-contained and needs no native hooks.

## Pipeline

1. **Collect APKs**
   - Installed app: `ApplicationInfo.sourceDir` + `splitSourceDirs`.
   - File picker: the app receives a `ParcelFileDescriptor` from SAF/HyperOS's
     file picker and hands it to the shell service, which copies it to
     `/data/local/tmp/headless/incoming-*.apk`.
2. **Repackage each APK** (`CloneManager.patchApk`)
   - Load with ARSCLib and rewrite the binary `AndroidManifest.xml`:
     - change the `package` attribute to `com.bliss.hclone.h########`;
     - turn relative component class names (`.Main`, `Main`) into absolute
       names of the original package **before** the package changes;
     - rewrite provider `authorities`, custom `<permission>` /
       `<uses-permission>` names, `taskAffinity`, `process` names and
       instrumentation `targetPackage` values that contain the original
       package (so they do not collide with the original app);
     - reject `sharedUserId` apps;
     - on the base APK only: set a literal application label and remove
       `android.intent.category.LAUNCHER` from every intent-filter (the clone
       does not appear in the phone's launcher).
   - Write the modified APK with ARSCLib.
3. **Sign** (`CloneManager.signApk`)
   - apksig v1+v2+v3 signature with the key in
     `app/src/main/assets/clone.keystore` (PKCS#12, password `headless`,
     alias `clone`).
   - This is a test/convenience key embedded in the APK; it is not secret.
4. **Install**
   - Single APK: `pm install -r -t <file>`.
   - Splits: `pm install-create` → `pm install-write` for the base and each
     split → `pm install-commit`.
   - Shell/root identity (Shizuku) is required for silent installs.
5. **Record** in `/data/local/tmp/headless/clones.json`:
   `package` (clone), `original`, `label`, `activity` (launcher class),
   `source`, `created`.
6. **Launch**
   - `CloneManager.launch` resolves the recorded activity class and runs
     `am start --display <displayId> -f 0x10000000 -n <clone>/<activity>`
     from the shell service. The clone is a normal user-0 package, so its
     windows are visible on the virtual display and receive injected input.
   - The `LAUNCHER` category was removed only from the manifest; the activity
     itself remains exported and can be started explicitly.

The clone package is never derived from the original name, so multiple clones
of the same app can coexist and no name-length/character problems arise.

## Data and updates

- The clone gets a **fresh data directory** under the clone package. The
  original app's private data is not copied (even though the shell service can
  read it, copying databases across a package identity change is not reliable).
- Updating a clone is not implemented; delete and clone again.
- Uninstalling the clone (`pm uninstall` / **Delete**) removes its data.
- The registry is only a convenience list. If it is lost, the packages remain
  installed; they can be removed in Settings → Apps or with `pm uninstall`.

## Known limitations

| Case | Result |
|---|---|
| Banking / WhatsApp / Google sign-in / Play Integrity | Usually refuse to run: signature and package identity changed |
| `android:sharedUserId` | Rejected with a clear error |
| Base APK picked from file | Works |
| Split APK picked from file | Rejected (pick the installed app instead; the engine copies all splits) |
| XAPK/APKS bundles | Not supported as a file; clone the installed app |
| Apps with push providers / GMS | Push notifications and Google services usually unavailable |
| Apps that verify `getPackageName()` or signature at runtime | May refuse or crash |
| Very large APKs | Works, but needs staging space (~2× APK size) and time |
| Secure keyguard | The virtual display cannot bypass it; unlock the phone first |

## Security

- The automation receiver rejects every action except `START_ENGINE`,
  `STOP_ENGINE` and `PING` unless the broadcast carries the engine token.
- Token file: `/data/local/tmp/headless/control.token`, directory mode 0770,
  file mode 0640; only shell/root can read it.
- The clone keystore is public by design (it is packaged in the APK). Clones
  are not a security boundary against a malicious local app; they are a
  compatibility/data-isolation convenience.

## Verification commands

```sh
# clone list (JSON in logcat)
am broadcast -a com.bliss.headless.action.CLONE_LIST \
  --es token "$(cat /data/local/tmp/headless/control.token)" \
  -n com.bliss.headless/.ControlReceiver

# confirm the clone has no launcher entry
cmd package query-activities -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER -p com.bliss.hclone.hXXXXXXXX

# confirm it is a real package with fresh data
pm path com.bliss.hclone.hXXXXXXXX
dumpsys package com.bliss.hclone.hXXXXXXXX | grep -E 'versionName|firstInstallTime'

# take a frame and check its mean/NonBlack values in logcat
am broadcast -a com.bliss.headless.action.SNAPSHOT --es name check.jpg \
  --es token "$(cat /data/local/tmp/headless/control.token)" \
  -n com.bliss.headless/.ControlReceiver
```
