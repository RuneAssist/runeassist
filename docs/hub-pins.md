# Plugin Hub pins

The Plugin Hub publishes one commit of this repository, named in
`plugins/runeassist-flipping` in `runelite/plugin-hub`. Its bot reviews the diff
between the commit it has published and the one a pull request proposes, and it
packages `src/main` only.

## Making the commit to pin

Do not pin `main` directly. Run

```
scripts/hub-commit.sh main
```

It builds a commit on `hub/<date>` with plugin code, build files, properties and
README from `main`, and `src/test`, `.github` and `scripts` as the published commit
has them, so the reviewer reads plugin code and nothing else. The published commit
is its second parent, so the Hub can diff from it. The script prints the commit;
push the branch and put that commit in the manifest:

```
git push origin hub/<date>
```

Then open or update the pull request on `runelite/plugin-hub` changing only the
`commit=` line. The bot labels the size within seconds; a merge usually follows in
about an hour if nothing is flagged.

## Before pinning

- `main` builds and tests against the RuneLite version in the Hub's
  `runelite.version`, and `scripts/version-check.sh` passes.
- The change set has been run in a real client (see `pre-hub-checklist.md`).
- `scripts/pr-size.sh --hub main` shows the size of what the Hub will read.

## Rules that keep the diff small

- Every pull request into `main` needs the `size`, `rules`, `build` and
  `hub-review` checks green; branch protection enforces this for everyone.
- Never rewrite a file's line endings. Several files have mixed endings; an editor
  or script that normalises them makes every line a change.
- Keep plugin changes under about 300 real lines per pull request.
