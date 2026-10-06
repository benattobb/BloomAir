# BloomAir handoff

## Saved state

- Local branch: `main`
- Current commit before this handoff note: `0ab1f13` (`Prepare BloomAir for sharing`)
- No Git remote is configured.
- The worktree was clean at handoff.
- The receiver fix was built, installed on the Android TV, and tested with a live Mac mirror. The TV stayed connected during a 30-second check; the capture looked clear and decoder logs showed no input-stall recovery.
- Marketing images: `artwork/marketing/bloomair-easy-connect.png` and `artwork/marketing/bloomair-watch-on-tv.png`.
- X and LinkedIn drafts: `docs/SOCIAL_POSTS.md` (still contains `REPOSITORY_URL`).

## User intent

The user confirmed BloomAir is a new project, wants a new **public** repository under `https://github.com/benattobb`, and wants people to be able to use it. They plan to continue in Antigravity.

## Publication work still needed

1. Create a new public GitHub repository named `BloomAir` and push a clean public snapshot. No BloomAir repository existed on the account profile when checked.
2. Do not push the current full Git history unchanged. It includes a tracked `local.properties` with the developer’s local Android SDK path, `scripts/__pycache__/make_icons.cpython-314.pyc`, and a committed 26 MB `AirPlayServer.apk`. These are not needed in a public source snapshot. Preserve the local APK if useful, but omit it from the public repo; exclude local properties and Python cache files too. The repo `.gitignore` already ignores `local.properties`, but it is tracked in history.
3. Check license and attribution before publishing. BloomAir contains code in the `io.github.jqssun.airplay` package and is based on the upstream [android-airplay-server](https://github.com/jqssun/android-airplay-server), whose [LICENSE](https://github.com/jqssun/android-airplay-server/blob/main/LICENSE) is GPL-3.0. A request to “let people use it” was given, but do not replace the upstream terms with MIT. Add appropriate GPL-3.0 licensing and attribution if it applies to the combined project. A network attempt to retrieve the license text failed, so no `LICENSE` file has been added yet.
4. Replace `REPOSITORY_URL` in `docs/SOCIAL_POSTS.md` after the new repo exists, then publish the posts with the matching images. Neither post has been published.

The two marketing images are AI-generated illustrations, not literal screenshots of the app. The square image is intended for X and the landscape image for LinkedIn/README use.
