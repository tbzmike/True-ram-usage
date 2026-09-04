# True RAM Usage

True RAM Usage is an Android memory-inspection app focused on presenting RAM, ZRAM and swap activity in language ordinary users can understand.

## Goals

- Show total, used and available physical RAM clearly.
- Detect ZRAM and explain its usage without exposing raw kernel counters.
- Show compressed data, actual physical RAM consumed, compression savings and efficiency.
- Detect all active swap devices separately.
- With sufficient privileges, attribute swapped memory to running applications/processes.
- Support normal, Shizuku-assisted and root capability levels where appropriate.
- Never claim a capability that the current device/kernel does not expose.

## App identity

- Name: True RAM Usage
- Package: `com.tbzmike.trueramusage`
- Initial version: `0.1.0`

## Planned screens

1. Memory overview
2. ZRAM details
3. Swap devices
4. Apps using swap
5. Live memory pressure/activity
6. Settings and capability status

## Safety

Process-closing and memory-tuning actions will be separated from read-only monitoring. Critical Android processes will be protected from casual termination, and potentially destructive root operations will require explicit user action.
