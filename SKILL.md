---
name: github-release-publish
description: Publish GitHub releases with verified build artifacts, release notes, checksums, and safe cleanup. Use when asked to publish a release, including when uploads.github.com rejects local credentials but GitHub API and Git access work.
---

# Publish a GitHub release

Complete the release the user authorized. A request to publish a release authorizes publication; do not ask for the same approval again. Preparing this skill or testing its helper does not authorize a real release. Keep publication separate from preparation until artifacts and their destination are verified.

This workflow works across project languages and build systems. The included optional helper targets GitHub.com and needs Python 3.10+, Git, and an existing Git author identity. GitHub CLI (`gh`) is required for release operations. Use the current checkout; do not create a worktree unless requested.

## 1. Establish the release contents

1. Read repository instructions and inspect Git status, origin, existing tags/releases, version files, and release conventions. Preserve user changes. Resolve the intended source to an exact commit SHA that exists on GitHub. Do not release artifacts from different or unexplained source changes.
2. Run the project's appropriate tests and release build, or reuse previously tested artifacts only after verifying they match the intended commit and recorded checksums. This skill does not prescribe Gradle, npm, Cargo, or any project-specific version.
3. Select only intended public release artifacts. Include source/debug/example packages only when appropriate; clearly label optional components. Exclude credentials, environment files, caches, test worlds, and unrelated outputs.
4. Prepare concise notes: installation requirements, changes, validation, limitations, and relevant developer documentation. Use a file for multiline notes.
5. Generate SHA-256 checksums using asset basenames. Ensure asset basenames are unique. Do not label source archives as installable binaries.

Example variables below are placeholders, not values to reuse:

```sh
REPO=owner/project
TAG=v1.2.3
TARGET=full_source_commit_sha
```

Use shell quoting or argument arrays for actual values. Never use `eval` or interpolate untrusted release notes into shell code.

## 2. Check existing authentication and releases

Use the environment's existing GitHub authentication and HTTPS proxy. If working in a managed cloud environment, follow its runtime/network skill. Inspect variable presence and credential metadata only; never print token values or read credential files to extract tokens.

```sh
git ls-remote origin
gh api "repos/$REPO" --jq .full_name
gh release list --repo "$REPO"
```

Successful Git access does not prove API access. Successful API access does not prove upload access: GitHub uploads use **uploads.github.com**, a separate host from **api.github.com**.

If the tag exists, verify its peeled commit matches `TARGET`; never force-move it. If the release is already published, report its URL rather than creating duplicates or overwriting assets without authorization. Reuse a matching draft carefully. Do not enable Actions or change organization policies to work around a denial.

## 3. Draft first; try native uploads

Create a draft with an explicit target commit and complete notes:

```sh
gh release create "$TAG" --repo "$REPO" --target "$TARGET" \
  --title "Project 1.2.3" --notes-file /tmp/release-notes.md --draft
```

Then upload the selected files and `SHA256SUMS`:

```sh
gh release upload "$TAG" /path/to/package.zip /path/to/SHA256SUMS --repo "$REPO"
```

Alternatively, supply assets to `gh release create --draft`. A failed upload can leave a valid draft with zero or partial assets. Inspect the existing draft before retrying; do not create another release. Avoid `--clobber`: accept an existing asset only when its name, size, uploaded state, and SHA-256 digest match; otherwise stop and resolve the conflict.

Find the numeric draft ID with a read-only API call. URL-encode the tag when constructing the API path if it contains `/`:

```sh
gh api "repos/$REPO/releases/tags/$TAG" --jq '{id, draft, tag_name, target_commitish}'
```

## 4. Diagnose upload failures

For `401 Bad credentials` on `uploads.github.com`, check whether the existing API identity and repository access still work. Do not assume that another login or token is needed merely because the upload host fails. Do not expose/recover proxy-held credentials, spoof Host headers, disable TLS verification, or attempt direct connections around the session proxy.

If API/Git writes work and repository Actions are available, use the runner fallback below. The runner uses its normal repository-scoped `github.token` with `contents: write`; no personal token is extracted or copied. This is a normal authorized release upload, not a way to override repository restrictions. An administration-settings API returning 403 does not, by itself, establish that workflow runs are disabled. Test the actual supported operation.

If workflows cannot be pushed, Actions are disabled, or the run requires outside approval, finish preparation, keep the release draft, and report the exact blocker. If a new credential is genuinely required, request it through secure environment settings with the narrowest repository/release permission and necessary destinations; never ask for token values in chat. Do not repeatedly retry unchanged rejected operations.

## 5. Optional GitHub runner fallback

The fallback puts **only the selected release assets**, checksums, and a small upload workflow on a unique temporary branch whose parent is `TARGET`. Its temporary tree contains no project source or existing project workflows, preventing unrelated push workflows from being copied/run. It does not move HEAD, touch the current index/worktree, or update the default branch. Deleting the temporary branch does not erase Git history or workflow retention: include only files already authorized for release. GitHub's Git blob limit applies; the helper rejects files over 95 MiB. For larger assets, use normal upload authentication or a separately authorized build/upload workflow instead.

Locate this skill's directory and run its helper from the project's checkout:

```sh
python3 /path/to/github-release-publish/scripts/prepare_runner_upload.py \
  --repo "$REPO" --tag "$TAG" --target "$TARGET" \
  --release-id 123456789 --output-dir /tmp/release-upload-plan \
  --asset /path/to/package.zip --asset /path/to/package-sources.zip
```

Do not pass an existing `SHA256SUMS` as an asset: the helper generates it. It writes `plan.json` and `SHA256SUMS` locally and prepares an isolated Git commit. **It does not push, upload, or publish.** Confirm the generated checksums match any already prepared/uploaded ones. The output directory must not already contain those files.

Read `commit` and `branch` from `plan.json`. Once the release operation is authorized, push exactly that prepared commit:

```sh
git push origin "PREPARED_COMMIT:refs/heads/PREPARED_BRANCH"
gh run list --repo "$REPO" --branch PREPARED_BRANCH \
  --json databaseId,status,conclusion,url
gh run watch RUN_ID --repo "$REPO" --interval 10 --exit-status
```

Allow indexing/queue time; poll at reasonable intervals and keep the user informed. Use the correct branch/run ID, not an unrelated successful run. The push-triggered workflow does not need to be installed on the default branch. It verifies local checksums, confirms the existing release is still the intended draft/commit, skips identical uploaded assets, rejects conflicting assets, and uploads missing files. It never publishes the release or uses `--clobber`.

If a job fails, inspect its actual error and diagnose before retrying. Do not delete an in-progress job's branch or leave temporary branches without explaining their status.

## 6. Verify, publish, and clean up

Before publication, require a successful upload run when using the fallback, then fetch the draft through `gh api repos/OWNER/REPO/releases/RELEASE_ID`. Verify:

- Intended repository, tag, exact target commit, and draft state.
- Exactly the expected assets, all `uploaded`, with correct sizes.
- Each API `digest` equals `sha256:` plus the locally calculated hash. If digests are unavailable, download assets through a supported authenticated route and hash them. Size alone is insufficient.
- Correct notes and stable/prerelease designation.

Publish after those checks, without another approval request when the user already asked to publish:

```sh
gh release edit "$TAG" --repo "$REPO" --draft=false --latest
```

For a prerelease, use `--prerelease` and do not mark it latest. For an older maintenance release, preserve the repository's latest-release policy.

Confirm the published URL, non-draft state, assets, and tag's resolved commit. For a fallback branch, first verify its remote tip is still the helper's prepared commit and the job completed, then delete only that branch:

```sh
git ls-remote origin refs/heads/PREPARED_BRANCH
git push origin --delete PREPARED_BRANCH
git status --short --branch
```

If its tip changed, do not delete someone else's work. Never force-push the default branch or a release tag. If cleanup fails after successful publication, report both outcomes accurately.

Finish with the release link and primary download link, label optional packages, and mention any remaining blocker or cleanup. Claim publication only after checking it; a draft or successful workflow alone is not a published release.
