# Manta Browser for Android

Universal Android Browser built in zero-bloat Kotlin and powered by the [Manta Rust Core Engine (opentjcodes/Manta)](https://github.com/opentjcodes/Manta).

## Pull Manta Core & Build via 1 Command

Run `./scripts/fetch-manta-core.sh opentjcodes/Manta && gradle assembleRelease` to pull the latest compiled Rust Core and build the universal Android APK.

## Key Architecture Highlights
* **Bolt-On Rust Core (`libmanta_core.so`)**: Automatically pulls the compiled multi-ABI Rust engine (`arm64-v8a`, `armeabi-v7a`, `x86_64`) from `opentjcodes/Manta`.
* **Hot-Active Tab Multiplexing**: All open tabs stay active in memory while the app is open—switching tabs is 0ms and never loses form inputs, scroll position, or JS state.
* **MantaDB & Clearable Virtual File Vault**: Inspect every byte stored per site (`localStorage`, Cookies, MantaDB tables, Virtual Files) and permanently shred individual origins or all data in 1 tap.
* **Full File Downloads & `<input type="file">` Uploads**: Upload files/photos to any website and download any file or save full web pages offline.
