# True RAM Usage

True RAM Usage is an Android memory-inspection and recovery-diagnostics app focused on showing what is actually consuming physical RAM, ZRAM and swap, which apps have memory in each tier, and what can be safely reclaimed when a phone becomes unusually memory-constrained.

## Core goals

- Show total, used and available physical RAM clearly using the kernel's `MemAvailable` accounting.
- Detect ZRAM and explain compressed data, actual physical RAM consumed by ZRAM, compression savings and efficiency.
- Detect every active swap device separately.
- With root access, attribute per-process physical RAM with PSS and swapped memory with SwapPss when `/proc/<pid>/smaps_rollup` is available.
- Fall back per process to RSS and `VmSwap` without pretending the fallback is proportional attribution.
- Show native or unmapped processes that cannot safely be assigned to an installed Android package.
- Expose memory categories such as anonymous pages, cache, shmem, slab, kernel stacks, page tables and unevictable memory so abnormal kernel/system RAM use can be diagnosed.
- Compare total attributed process RAM with system used RAM to expose the diagnostic gap that may be explained by kernel memory, caches, shmem or other non-process categories.
- Provide guarded recovery actions: force-stop non-system user apps, reclaim clean file/kernel caches, and cycle ZRAM only when a safety estimate shows enough physical RAM for `swapoff`.
- Minimize the monitor's own observer effect by stopping periodic kernel polling while the Activity is backgrounded and lazily composing long process lists.
- Publish every passed `main` build as the latest green GitHub Release and let the app retrieve verified updates directly from that release feed.
- Never claim that physical RAM can be made literally empty; Android and the Linux kernel will immediately retain or reuse memory required for services, kernel structures and caches.
- Never claim a capability that the current device/kernel does not expose.

## App identity

- Name: True RAM Usage
- Package: `com.tbzmike.trueramusage`
- Current development version: `0.6.0`

## Latest green build baseline

The Android Build workflow remains the release gate. Pull requests compile and verify the fixed development signature but do not publish releases. A successful push build on `main` performs these extra steps only after APK assembly and signature verification have passed:

1. Copy the exact passed debug APK to the stable release asset name `true-ram-usage.apk`.
2. Generate `update.json` containing versionCode, versionName, CI run number, commit SHA, APK SHA-256 and signing-certificate SHA-256.
3. Force-move the repository tag `latest-green` to that exact passed commit.
4. Create a versioned GitHub Release, mark it as GitHub's latest release and upload both `true-ram-usage.apk` and `update.json`.
5. Query GitHub's `/releases/latest` endpoint and verify that the published manifest and APK asset are present and that the manifest matches the build that just passed.

Older green releases remain available for rollback while `latest-green` always identifies the newest passed `main` commit.

## App updates

True RAM Usage reads the public GitHub Releases API for `tbzmike/True-ram-usage` and treats GitHub's latest release as the update source. The app does not install a downloaded file merely because it came from GitHub. Before installation it verifies:

- the APK SHA-256 against `update.json`,
- the APK package name against `com.tbzmike.trueramusage`,
- the APK versionCode against the release manifest,
- the release signing-certificate SHA-256 against the currently installed app,
- the downloaded APK signing certificate against the currently installed app.

Manual updates are available from the floating **Updates** control. **Check for updates** queries the latest green release. A newer verified build can be downloaded and installed immediately. Root installation is attempted first; if root is unavailable, Android's normal package installer is opened. Android 8 and later may require the user to allow True RAM Usage as an install source before the normal installer can proceed.

Automatic updates are enabled by default. WorkManager checks for a newer green release every six hours when network connectivity is available. A newer APK is downloaded and verified in the app's private storage. If root has previously been granted to True RAM Usage, unattended root installation is attempted. If unattended installation is unavailable, the verified APK remains staged and the Updates control offers manual installation the next time the app is opened.

## Physical RAM accounting

The system overview uses `MemTotal - MemAvailable` from `/proc/meminfo` for effective used physical RAM. Detailed mode also exposes direct kernel counters including `AnonPages`, `Cached`, `Buffers`, `Shmem`, `Slab`, `SReclaimable`, `SUnreclaim`, `KernelStack`, `PageTables`, `Unevictable`, `Mlocked`, `Dirty` and `Writeback`.

These counters are diagnostic categories and some overlap; they are not added together as a second RAM total.

A low-available-RAM warning is shown when available physical RAM falls below the larger of 512 MiB or 10% of total RAM, matching the same reserve scale used by the guarded ZRAM-clear safety check.

## Per-app RAM and swap attribution

The process scanner first reads the fast `/proc/<pid>/status` counters for UID, `VmRSS` and `VmSwap`. With root, it then attempts to read `/proc/<pid>/smaps_rollup` and uses:

- `Pss` for proportionally attributed physical RAM.
- `SwapPss` for proportionally attributed swapped pages.

If proportional counters are unavailable for a process, that process falls back to RSS and `VmSwap`, and the UI labels the fallback rather than presenting it as equivalent to PSS.

Processes using isolated or otherwise non-package UIDs are also checked by process name where a safe installed-package match exists. Remaining native/unmapped processes are shown separately in detailed mode instead of being silently discarded.

Long running-app, swap-app and native-process lists use bounded lazy Compose lists so the memory monitor does not eagerly compose every row at once.

## Process accounting coverage

After a root process scan, the app compares:

- mapped app attributed physical RAM,
- native/unmapped attributed physical RAM,
- their combined process total,
- system used physical RAM.

A positive gap is not automatically a leak; it can include kernel memory, caches, shmem and other non-process categories. A negative gap can occur when some processes fall back to RSS because RSS can double-count shared pages. The comparison is therefore a diagnostic clue, not a replacement for `/proc/meminfo`.

## ZRAM accounting

Detailed ZRAM information is read from `/sys/block/zram*/mm_stat`, `disksize`, `comp_algorithm` and `/proc/swaps`. The app distinguishes:

- Uncompressed data stored in ZRAM.
- Compressed payload size.
- Actual physical RAM consumed by ZRAM (`mem_used_total`).
- RAM saved by compression.
- Active ZRAM swap usage and priority.

ZRAM's own physical-memory cost is already included in system used RAM; the app displays it separately only to explain where that portion of physical RAM went.

## Recovery and diagnosis

When RAM is unusually full, the recommended sequence is:

1. Refresh the process scan and inspect mapped apps plus native/unmapped processes.
2. Compare the process-accounting coverage gap with the detailed kernel/cache breakdown.
3. Force-stop running non-system user apps when an aggressive diagnostic reclaim is intentionally desired.
4. Reclaim clean page cache and reclaimable filesystem metadata with `sync` and `/proc/sys/vm/drop_caches` when the kernel/SELinux policy permits it.
5. Refresh the physical-RAM breakdown to see what remains.
6. Cycle ZRAM only when the app's guarded safety estimate shows enough available physical RAM to bring swapped pages back during `swapoff`.

If physical RAM remains abnormally high after user apps and reclaimable caches have been removed, the remaining `/proc/meminfo` categories, process-coverage gap and native/unmapped process list are intended to help distinguish kernel/slab/shmem/unevictable/system-process pressure from ordinary application use.

## Monitoring overhead

The 2-second system memory poll runs only while the Activity is started. It is cancelled in the background and restarted when the app returns to the foreground. Expensive PSS/SwapPss process scans remain explicit rather than running continuously.

## Capability levels

The current implementation supports normal read-only kernel counters where Android permissions allow them and root-assisted process/ZRAM diagnostics, recovery actions and unattended self-updates. Shizuku-assisted access remains a planned capability and is not currently implemented.

## Development signing

Debug APKs are signed with the repository's fixed **development/test key** so every future debug build has the same Android signing identity and can update an earlier debug build.

Development certificate SHA-256:

`1A:3D:86:93:35:1E:65:36:FB:A6:C9:00:52:68:50:A8:78:A0:82:7B:C1:AA:80:6C:ED:99:4E:C4:B9:DA:36:EC`

The development key is intentionally public and must never be used as a production release key. A future production release should use a separate private signing key stored outside the repository.

## Safety

Process-closing and memory-tuning actions are separated from read-only monitoring. True RAM Usage protects itself and system apps from the bulk close actions. Cache reclaim does not change VM tuning values. ZRAM cycling validates the device path and refuses to start when the available-RAM safety estimate is insufficient. Update installation only proceeds after the downloaded APK passes hash, package-name, version and signing-certificate verification.
