# WoW Audio permanent signing identity

This repository intentionally does **not** contain the private signing key, passwords, or keystore base64.

All release/update APKs from the v1.4.0 permanent baseline onward must be signed with this certificate:

- Signer: `CN=WoW Audio Update, O=Whisper of Words, C=MM`
- Certificate SHA-256: `5F:58:AB:46:3C:3A:43:27:5D:D7:68:FB:3B:DB:2A:AE:F3:88:B5:C1:8A:4D:60:76:B0:C5:33:9B:4A:EE:0F:E1`
- Certificate SHA-1: `E8:FF:52:0F:BD:14:5D:44:02:AE:AB:06:D2:EB:8D:1A:38:09:19:B9`

## Release rule

1. Increase `versionCode` for every release.
2. Build from the approved source.
3. Sign with the private permanent WoW Audio signing kit stored offline by the owner.
4. Run `apksigner verify --verbose --print-certs` on the final APK.
5. Do not distribute it as an update unless the SHA-256 signer fingerprint exactly matches the value above.
6. Never generate a replacement key for a routine update.

The Nilar/Thiha v1.1 build immediately before this baseline used a different signing certificate whose private key was lost. Therefore moving from that specific APK to the v1.4.0 permanent baseline requires one uninstall/reinstall. After the v1.4.0 baseline is installed, future builds signed with the identity above can update in place when their `versionCode` is higher.
