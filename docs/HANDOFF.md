# BloomAir Handover & Release Status

## Current State

- Public repository: **https://github.com/benattobb/BloomAir**
- Branch: `main` (tracked with `origin/main`)
- Build status: Verified cleanly with `./gradlew assembleDebug` (JDK 17).
- License: Official GNU General Public License v3.0 (`LICENSE`) with upstream attribution to `android-airplay-server` by `jqssun` and Google Oboe in `README.md`.
- App icon artwork: Explicitly credited to `koboyo` in `README.md` and social post drafts.
- Sensitive & Binary cleanups:
  - Untracked `AirPlayServer.apk` (26 MB APK).
  - Untracked `local.properties` (developer's local SDK path).
  - Untracked `scripts/__pycache__/` and `*.pyc`.
  - Updated `.gitignore`.
- Social posts: Final drafts in `docs/SOCIAL_POSTS.md`.
- Marketing image: `artwork/marketing/bloomair-cinematic-social.png` (1200×630; shared X and LinkedIn banner; editable source: `bloomair-cinematic-social.svg` and `bloomair-cinematic-background.png`).
