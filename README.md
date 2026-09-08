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
- Provide guarded recovery actions: force-stop non-system user apps, reclaim clean file/kernel caches, cycle ZRAM, and run an explicitly aggressive boot-like reclaim that also attempts to empty every active swap device.
- Provide a real Settings surface for appearance, monitoring cadence, root access, manual updates and automatic-update behavior.
- Minimize the monitor's own observer effect by stopping periodic kernel polling while the Activity is backgrounded, making the foreground refresh interval configurable, and lazily composing long process lists.
- Publish every passed `main` build as the latest green GitHub Release and let the app retrieve verified updates directly from that release feed.
- Never claim that physical RAM can be made literally empty or guaranteed to match a reboot; Android and the Linux kernel retain required services, kernel structures and caches and may immediately reuse freed memory.
- Never claim a capability that the current device/kernel does not expose.

## App identity

- Name: True RAM Usage
- Package: `com.tbzmike.trueramusage`
- Current development version: `0.8.1`

## Settings

The floating **Settings** control is the central configuration and update surface. It is wired directly to the same persisted preferences and ViewModels used by the live monitor rather than keeping a separate copy of settings state.

Settings currently includes:

- **Display detail:** Simple or Detailed.
- **Theme:** System, Light or Dark.
- **Automatic RAM refresh:** on/off.
- **Foreground refresh interval:** 1, 2, 5 or 10 seconds; 2 seconds remains the default.
- **Refresh RAM now:** immediate `/proc`/ZRAM refresh even when automatic refresh is disabled.
- **Refresh app/process memory now:** explicit rooted PSS/SwapPss scan.
- **Root access:** current session status plus grant/retry action.
- **App updates:** installed version, latest green build, staged update state, manual check/install actions and GitHub release access.
- **Automatic green-build checks:** periodically discover, download and verify newer passed `main` builds.
- **Automatic verified installation:** independently controls whether an automatically discovered verified APK may be installed unattended with previously granted root. Turning this off still allows automatic checks/downloads and leaves the APK staged for manual installation.

The aggressive RAM-reclaim action remains separate from Settings because it is an explicit operational action, not a passive preference.

## Latest green build baseline

The Android Build workflow remains the release gate. Pull requests compile and verify the fixed development signature but do not publish releases. A successful push build on `main` performs these extra steps only after APK assembly and signature verification have passed:

1. Copy the exact passed debug APK to the stable release asset name `true-ram-usage.apk`.
2. Generate `update.json` containing versionCode, versionName, CI run number, commit SHA, APK SHA-256 and signing-certificate SHA-256.
3. Force-move the repository tag `latest-green` to that exact passed commit.
4. Create a versioned GitHub Release, mark it as GitHub's latest release and upload both `true-ram-usage.apk` and `update.json`.
5. Query GitHub's `/releases/latest` endpoint and verify that the published manifest and APK asset are present and that the manifest matches the build that just passed.

Older green releases remain available for rollback while `latest-green` always identifies the newest passed `main` commit.

## App updates

True RAM Usage now treats the normal GitHub Releases site as its primary update-discovery source instead of depending only on `api.github.com`. It first opens `https://github.com/tbzmike/True-ram-usage/releases/latest`, follows GitHub's redirect to the current versioned release tag, then retrieves that release's `update.json` and `true-ram-usage.apk` directly from the normal `github.com` release-download host. The public GitHub Releases API remains a fallback if the normal release-page path fails.

This dual-source discovery prevents `api.github.com` DNS or filtering failures from being the only path to updates. Settings also keeps **Open latest GitHub release** available even when automatic discovery fails, giving the user a manual fallback to the stable `github.com/.../releases/latest` page.

The app does not install a downloaded file merely because it came from GitHub. Before installation it verifies:

- the APK SHA-256 against `update.json`,
- the APK package name against `com.tbzmike.trueramusage`,
- the APK versionCode against the release manifest,
- the release signing-certificate SHA-256 against the currently installed app,
- the downloaded APK signing certificate against the currently installed app.

Manual updates live in **Settings → App updates**. **Check for updates now** resolves the latest green release. A newer verified build can be downloaded and installed immediately. Root installation is attempted first; if root is unavailable, Android's normal package installer is opened. Android 8 and later may require the user to allow True RAM Usage as an install source before the normal installer can proceed.

Automatic green-build checks are enabled by default. WorkManager checks for a newer green release every six hours when network connectivity is available. A newer APK is downloaded and verified in the app's private storage. Automatic installation is a separate persisted setting and is also enabled by default to preserve the existing updater behavior. When automatic installation is enabled and root has previously been granted, unattended root installation is attempted. When it is disabled or root is unavailable, the verified APK remains staged and Settings offers manual installation.

## Physical RAM accounting

The system overview uses `MemTotal - MemAvailable` from `/proc/meminfo` for effective used physical RAM. Detailed mode also exposes direct kernel counters including `AnonPages`, `Cached`, `Buffers`, `Shmem`, `Slab`, `SReclaimable`, `SUnreclaim`, `KernelStack`, `PageTables`, `Unevictable`, `Mlocked`, `Dirty` and `Writeback`.

These counters are diagnostic categories and some overlap; they are not added together as a second RAM total.

A low-available-RAM warning is shown when available physical RAM falls below the larger of 512 MiB or 10% of total RAM, matching the reserve scale used by guarded swapoff operations.

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

## Aggressive boot-like reclaim

The floating **Aggressive reclaim** control is intended for the specific case where RAM has become abnormally full and a reboot would otherwise be used simply to reclaim memory. The operation deliberately does more than the normal recovery controls but still verifies dangerous steps before running them.

The sequence is:

1. Capture a fresh before-state `/proc/meminfo` and `/proc/swaps` snapshot.
2. Run a fresh rooted process scan instead of trusting an earlier app list.
3. Force-stop current mapped non-system user apps while protecting True RAM Usage itself and mapped system apps.
4. Run Android's `am kill-all` to kill remaining background processes that Android considers background-killable.
5. Run `sync`, then request `drop_caches=3` so clean page cache plus reclaimable dentries/inodes can be released when the kernel/SELinux policy permits it.
6. Request `compact_memory=1` when the kernel exposes that interface. This compacts memory zones but does not create more total RAM by itself.
7. Re-read `MemAvailable`, active swap devices and current swap usage after the earlier reclaim steps.
8. Calculate whether all currently swapped pages can fit back in physical RAM while retaining a reserve equal to the larger of 512 MiB or 10% of total RAM.
9. Only when that fresh safety check passes, temporarily lower swappiness to 0 where permitted, disable every active swap/ZRAM device, verify that all swap was off together at one point, re-enable every device with its recorded priority, and restore the original swappiness value.
10. If Android immediately repopulates swap and a second fresh safety check still passes, one additional all-swap pass is allowed. The operation never loops indefinitely.
11. Run one final background/cache reclaim and capture a final kernel snapshot plus process scan.
12. Show the measured before/after RAM and swap values, whether swap was actually verified empty, whether all swap devices were disabled together, whether swappiness was restored, and any reason a swap phase was blocked or failed.

This is intentionally described as **boot-like**, not identical to a reboot. A real reboot reconstructs kernel state and restarts framework/services from scratch. True RAM Usage cannot safely discard required kernel memory or stop essential Android services just to make a number smaller. The aggressive action instead removes current user/background processes and reclaimable caches, compacts memory, and empties swap when the kernel reports enough physical headroom to do so without knowingly driving the device into OOM pressure.

If all active swap cannot be safely emptied after the first reclaim stages, the app reports the exact additional `MemAvailable` headroom required and does not run `swapoff`.

## Recovery and diagnosis

When RAM is unusually full, the normal diagnostic sequence is:

1. Refresh the process scan and inspect mapped apps plus native/unmapped processes.
2. Compare the process-accounting coverage gap with the detailed kernel/cache breakdown.
3. Force-stop running non-system user apps when an aggressive diagnostic reclaim is intentionally desired.
4. Reclaim clean page cache and reclaimable filesystem metadata with `sync` and `/proc/sys/vm/drop_caches` when the kernel/SELinux policy permits it.
5. Refresh the physical-RAM breakdown to see what remains.
6. Cycle ZRAM only when the app's guarded safety estimate shows enough available physical RAM to bring swapped pages back during `swapoff`.

The **Aggressive reclaim** control combines and extends these steps when the goal is to avoid a reboot used only for memory reclamation. If physical RAM remains abnormally high even after the aggressive action, the final `/proc/meminfo` categories, process-coverage gap and native/unmapped process list are intended to help distinguish kernel/slab/shmem/unevictable/system-process pressure from ordinary application use.

## Monitoring overhead

Automatic system-memory polling runs only while the Activity is started. It is cancelled in the background and restarted when the app returns to the foreground. Settings can disable automatic polling entirely or choose a 1, 2, 5 or 10 second foreground interval; the default remains 2 seconds. When automatic polling is disabled, opening the Activity still performs one fresh memory read, and manual refresh remains available. Expensive PSS/SwapPss process scans remain explicit rather than running continuously.

## Capability levels

The current implementation supports normal read-only kernel counters where Android permissions allow them and root-assisted process/ZRAM diagnostics, recovery actions and unattended self-updates. Shizuku-assisted access remains a planned capability and is not currently implemented.

## Development signing

Debug APKs are signed with the repository's fixed **development/test key** so every future debug build has the same Android signing identity and can update an earlier debug build.

Development certificate SHA-256:

`1A:3D:86:93:35:1E:65:36:FB:A6:C9:00:52:68:50:A8:78:A0:82:7B:C1:AA:80:6C:ED:99:4E:C4:B9:DA:36:EC`

The development key is intentionally public and must never be used as a production release key. A future production release should use a separate private signing key stored outside the repository.

## Safety

Process-closing and memory-tuning actions are separated from read-only monitoring. True RAM Usage protects itself and mapped system apps from bulk force-stop actions. Cache reclaim does not permanently change VM tuning values. The aggressive all-swap path validates every active swap path, uses a fresh `MemAvailable` headroom check, attempts rollback if swapoff fails partway through, re-enables all previously active swap devices with their recorded priorities, and restores the original swappiness value when it was temporarily changed. Update installation only proceeds after the downloaded APK passes hash, package-name, version and signing-certificate verification.
