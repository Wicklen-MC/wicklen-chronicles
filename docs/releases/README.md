# Release workflow

The **Release** GitHub Actions workflow builds the selected source, runs the
configured core/Fabric checks, compiles native test scenarios, validates the mod
identity and packages the player JAR, sources JAR and `SHA256SUMS.txt`. Publishing
is a separate job with `contents: write`, after the build succeeds. It refuses to
overwrite an existing published release or move a tag to another commit.

## New versions

1. Update `version` in `gradle.properties` and add `docs/releases/<version>.md`.
2. Commit and push the source.
3. Push the matching stable tag, for example `v0.4.3`. The workflow publishes it
   and marks it Latest after all checks pass.

Alternatively, open **Actions → Release → Run workflow**. Set `tag` to
`v<version>` and `target_ref` to the reviewed source commit or branch. The Gradle
version must match the tag. The workflow creates the tag at the verified commit
when publishing. Disable `make_latest` for an older release.

This publishes the mod only. It does not update a game server, modpack, website,
world or client installation. Downloaded release assets inherit repository access.

## Historical 0.4 releases

The initial 0.4.0–0.4.2 publications preserve the exact existing player JARs. A fresh
build has identical code/resources but can differ in empty ZIP directory records
and Loom's client-only manifest list. In particular, pack 0.4.2 already pins the
original JAR hash; publishing a rebuilt replacement under that identity would
break the existing artifact contract.

`historical-artifacts.json` records the approved source commits, filenames, sizes
and SHA-256 values. For this one-time backfill, stage those three assets in an
unpublished GitHub draft and run **Release** with `approved_draft=true`, the exact
source commit and the desired `make_latest` value. This is an explicit manual mode;
normal tag releases always use their fresh build.

Before publishing a historical draft, the workflow:

- Verifies the registered artifact hashes and source commit.
- Rebuilds and tests that exact source.
- Compares every non-directory JAR entry against the build, including compiled
  classes and resources. A manifest difference is accepted only for a complete
  client-only class list proven from the compiled client output.
- Compares the sources JAR contents exactly, ignoring empty directory records.
- Downloads the draft in the publish job immediately before publication and
  verifies all three files against the registered checksums. The build job has
  read-only access and never needs to read drafts or publish assets.

No API credentials, private configuration or runtime saves are included. The
workflow does not run opt-in live-provider tests or start a graphical game client.

The cached 0.4.0 sources archive had one stale Russian label (`Отметить здесь`
instead of the committed `Отметка`). Its player JAR already had the correct
resource. The sources archive was regenerated from the verified CI build before
its first publication; its reviewed checksum is recorded in the registry. This
changes no published player JAR or existing pack artifact.
