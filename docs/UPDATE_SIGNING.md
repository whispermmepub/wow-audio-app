# WoW Audio update signing contract

WoW Audio package: `com.whisper.wowaudio`

Starting with v1.1.0, distributable APKs must use one stable signing key so future versions can be installed as updates without uninstalling the app.

## Permanent certificate

Expected SHA-256 certificate fingerprint:

`5F:58:AB:46:3C:3A:43:27:5D:D7:68:FB:3B:DB:2A:AE:F3:88:B5:C1:8A:4D:60:76:B0:C5:33:9B:4A:EE:0F:E1`

Do not replace this key for ordinary updates. Losing the private key means sideloaded installs signed with it cannot be updated in place by a differently signed APK.

## GitHub Actions secrets

The private keystore must never be committed to this public repository. Configure these repository Actions secrets:

- `WOW_AUDIO_KEYSTORE_B64` — base64 of the private JKS keystore
- `WOW_AUDIO_STORE_PASSWORD`
- `WOW_AUDIO_KEY_ALIAS`
- `WOW_AUDIO_KEY_PASSWORD`

The workflow decodes the keystore only inside the temporary GitHub Actions runner. When all four secrets are present, both debug and release builds use the stable update key and CI verifies the certificate fingerprint above.

When secrets are absent, CI still builds and lints but the debug artifact is only a test build and must not be treated as update-stable.

## Migration note

Older v1.0.x debug APKs were produced by fresh GitHub-hosted runners with transient debug signing keys. Those builds can have different certificates, so Android may require one final uninstall/reinstall when moving to the v1.1.0 stable-signing line. After the stable-signed v1.1.0 install, later APKs signed with the same certificate can update it in place as long as their `versionCode` increases.
