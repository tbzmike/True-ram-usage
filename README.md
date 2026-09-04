# True RAM Usage

True RAM Usage is an Android memory-inspection app focused on presenting RAM, ZRAM and swap activity in language ordinary users can understand.

## Goals

- Show total, used and available physical RAM clearly.
- Detect ZRAM and explain its usage without exposing raw kernel counters.
- Show compressed data, actual physical RAM consumed, compression savings and efficiency.
- Detect all active swap devices separately.
- With root access, attribute per-process private swapped memory to installed Android apps.
- Support normal, Shizuku-assisted and root capability levels where appropriate.
- Never claim a capability that the current device/kernel does not expose.

## App identity

- Name: True RAM Usage
- Package: `com.tbzmike.trueramusage`
- Current development version: `0.3.2`

## App swap attribution

The app list uses the kernel's `VmSwap` value from `/proc/<pid>/status`. This is intentionally used for the fast list because reading `smaps`/`smaps_rollup` for many processes can be extremely slow on Android devices with many mappings. `VmSwap` reports private anonymous process memory that is currently swapped. Shared tmpfs/shmem swap is not attributed to a single app in this view.

## Development signing

Debug APKs are signed with the repository's fixed **development/test key** so every future debug build has the same Android signing identity.

Development certificate SHA-256:

`1A:3D:86:93:35:1E:65:36:FB:A6:C9:00:52:68:50:A8:78:A0:82:7B:C1:AA:80:6C:ED:99:4E:C4:B9:DA:36:EC`

The development key is intentionally public and must never be used as a production release key. A future production release should use a separate private signing key stored outside the repository.

## Planned screens

1. Memory overview
2. ZRAM details
3. Swap devices
4. Apps using swap
5. Live memory pressure/activity
6. Settings and capability status

## Safety

Process-closing and memory-tuning actions are separated from read-only monitoring. Critical Android processes are protected from casual termination, and potentially destructive root operations require explicit user action.
