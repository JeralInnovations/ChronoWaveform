# Recording storage and app updates

## Why earlier builds could lose or overlap recordings

The previous app used visible folder names (`Test1`, `Test2`, etc.) as recording
identity. An incomplete Android MediaStore listing could make an existing name
appear available. Renaming a label also moved its folder, and refreshing a partial
public listing could remove private records from the app's history.

The previous GitHub workflow built a debug APK on a fresh runner without retaining
the signing key. Android requires an update to have the same signing identity as
the installed application. Uninstalling to get past a signature conflict removes
private app data and can also remove the new installation's automatic access to
older public files. **Do not uninstall an existing app before backing up its data.**

## Storage in 2.2.0

- Every new recording uses `Project/Label--<UUID>/`. Labels can repeat; folder IDs
  cannot be reused by resetting preferences or by failing to see older folders.
- Editing a label changes the metadata, not the owning folder or photo URIs.
- Existing folders are kept in place. A canonical `shot.json` cannot be rewritten
  with a different recording UID.
- The private library is committed using `AtomicFile` and an explicit disk sync.
  Public `shot.json`, waveform JSON and PNG files are portable copies. A partial
  public scan imports missing records without deleting or reverting saved records.
- A corrupt private library is preserved and blocks replacement with an empty
  library. Storage failures are shown with a Retry action.
- New device results are acknowledged only after their reading and, for waveform
  firmware, their complete trace have been committed locally. Distinct results in
  a reconnect batch are all retained. Waveform downloads run one at a time.
- Simulation has separate private and public storage.

Public files remain under `Documents/ChronoData` on Android 10 and newer. Android
8–9 use the app's external files directory, which is removed by uninstalling.
The private library is also removed on uninstall. Atomic saves protect against
interrupted writes, not uninstall, storage failure, or destruction of the phone.

Use the app to edit or delete library records. Editing or deleting public copies
outside the app no longer changes the authoritative private library automatically.

## Find, export and recover readings

Open the test log to search labels, tools, targets, notes and dates, or select a
project. **Export shown** exports the filtered selection, including a lossless
`chrono_readings_*.json` file, CSV and waveform transition CSV. The existing Export
button on the dashboard exports all readings.

**Import** accepts the JSON export or individual older `shot.json` files selected
through Android's file picker. Existing UIDs are kept, so importing the same file
twice does not duplicate or overwrite a reading. Imported copies go into the
`Imported` project and preserve labels, timing, metadata and waveform review.

JSON exports do not embed photographs. Copy the entire `ChronoData` folder as well
when backing up photos. Imported readings do not automatically reconnect photo
URIs from a previous installation; those photos can be attached again in the app.

## Safe migration from an older debug build

1. Before changing the installed app, use its Export action and copy the entire
   `Documents/ChronoData` folder to a computer or another backup location. Keep all
   `shot.json` files, waveform files and photos; CSV alone is not a full backup.
2. If the original signing key is available, use it to sign the new APK so Android
   can perform an in-place update. Do not change `applicationId`.
3. If that key is unavailable, a new signing identity cannot update the old app
   in place. Verify the backup before any uninstall. The new app's Import action
   can recover the copied `shot.json` files afterward.
4. Retain the new signing key permanently. All subsequent updates must use it,
   the same application ID, and a higher `versionCode`.

## Persistent release signing

The workflow still builds a debug artifact for development, but **does not publish
another debug APK as the latest user release**. Publishing requires these GitHub
Actions repository secrets:

| Secret | Value |
| --- | --- |
| `CHRONO_KEYSTORE_BASE64` | Base64 encoding of the persistent JKS keystore |
| `CHRONO_KEYSTORE_PASSWORD` | Keystore password |
| `CHRONO_KEY_ALIAS` | Signing alias (defaults to `chrono`) |
| `CHRONO_KEY_PASSWORD` | Key password (defaults to the keystore password) |

Keep an offline backup of the keystore and passwords. Do not commit them. If you
already have the installed app's key, use that key rather than generating another.
Without configured secrets, CI reports a warning and skips release publication.
Previous release assets are not deleted by this change.

For a local signed build, set `CHRONO_KEYSTORE_FILE` to the absolute keystore path,
set the matching password/alias environment variables, and run
`gradle -p android assembleRelease`. Unsigned builds are not installable updates.

Android references: [app signing](https://developer.android.com/studio/publish/app-signing),
[document access through the file picker](https://developer.android.com/training/data-storage/shared/documents-files),
[AtomicFile](https://developer.android.com/reference/android/util/AtomicFile).

## Firmware 3.3

All three sketch variants refuse another ARM when all 16 pending slots are in use;
they no longer evict the oldest unacknowledged reading. The ACK ring has capacity
for all 16 results. Result ID zero remains reserved for the trace request sentinel.
The measurement path, sensor pins and BLE packet layout are unchanged.

Pending firmware results are still held in RAM. Collect them before powering off
or flashing the logger. Power-loss persistence in MCU flash is not part of this
change.

## Verification

Run `gradle -p android testDebugUnitTest lintDebug assembleDebug`. Regression tests
cover repeated labels, reset preferences, folder ownership, interrupted saves,
partial public listings, simulation separation, duplicate result delivery,
reconnect batches, and corrupt/truncated Bluetooth packets.

Compile the board-specific sketches with the FQBNs in `.github/workflows/build-firmware.yml`.
Before field use, also verify on the actual phone: updating without uninstalling,
recording two identically labeled tests, renaming a label, reconnecting with queued
results, exporting/importing, and viewing the associated photos. Software tests
do not validate physical sensor timing or radio behavior on every handset.
