# Credentials and release packaging

Current public CI APKs contain no MFi accessory identity. Historical upstream preview APKs included an experimental certificate/key pair; that history is recorded in docs/THIRD_PARTY_NOTICES.md. Local car-test builds can explicitly inject external identity files with DIPLAY_AUTH_ASSETS_DIR. Anyone receiving such an APK can extract them; removing files or rewriting Git history does not revoke previously distributed credentials.

The public Git tree and corresponding source archive exclude accessory keys and Android release-signing secrets. CI checks reject credential containers and private-key blocks in tracked files. Synthetic test identities are generated at runtime. Source builds have no automatic private-asset import; optional local identity injection requires an explicit directory and permits only the two expected runtime files. The assembleStandaloneDebug task requires those inputs; ordinary Debug/Release builds do not. Public Release CI unsets DIPLAY_AUTH_ASSETS_DIR.

The Android APK-signing key is separate, stays local and is never bundled in the APK. A locally injected accessory identity does not establish Apple certification or guarantee future compatibility.

Review diagnostic reports before posting. Never include credentials or pairing records in public issues. Use GitHub private vulnerability reporting for sensitive findings.
