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
- Marketing images: `artwork/marketing/bloomair-easy-connect.png` (X) and `artwork/marketing/bloomair-watch-on-tv.png` (LinkedIn).
