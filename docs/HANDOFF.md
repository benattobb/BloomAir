# BloomAir handoff & Release Status

## Current State

- Local branch: `main` (commit: `8e235a4`)
- Build status: `./gradlew assembleDebug` builds cleanly (verified with JDK 17).
- License: Added official GNU General Public License v3.0 (`LICENSE`) and added GPL-3.0 attribution for upstream `android-airplay-server` in `README.md`.
- Sensitive & Binary cleanups:
  - Untracked `AirPlayServer.apk` (26 MB APK).
  - Untracked `local.properties` (developer's local SDK path).
  - Untracked `scripts/__pycache__/` and `*.pyc`.
  - Updated `.gitignore` to keep them ignored.
- Social posts: Updated `docs/SOCIAL_POSTS.md` with the repo URL (`https://github.com/benattobb/BloomAir`).
- Marketing images: `artwork/marketing/bloomair-easy-connect.png` and `artwork/marketing/bloomair-watch-on-tv.png`.

## Publishing to GitHub

To publish the clean public repository to `https://github.com/benattobb/BloomAir`:

1. Create a new public repository named **BloomAir** on GitHub (`https://github.com/new`).
2. If pushing a clean snapshot without historical binaries / paths:
   ```sh
   git remote add origin https://github.com/benattobb/BloomAir.git
   git push -u origin main
   ```
3. Share the posts from `docs/SOCIAL_POSTS.md` with the matching artwork images.
