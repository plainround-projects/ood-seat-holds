# Git (`.git/`, `.gitignore`)

## What it is

Git is a **version control system**: a tool that records the history of a folder of files. Each saved snapshot is called a **commit**. A commit has a unique id, an author, a date and a message, and it points to the commit before it, so the commits form a chain you can walk back along. The folder that Git tracks is a **repository**; its history lives in a hidden folder called `.git`. Git works offline, on your own disk. GitHub is a separate website that stores copies of repositories.

## Why this repository uses it

The task asks for each project to be "its own git repository" with meaningful commits, ready to be pushed to GitHub later. The history is also part of the lesson in one place: the pricing repository proves the lesson's "open for extension" claim with a commit that adds a rule and changes only one existing file (and only by two lines).

## Where it appears

| Path | What it does with Git |
| --- | --- |
| `.git/` | The history: every commit, branch and the staging area. Created by `git init`. Never edited by hand. |
| `.gitignore` | Lists files Git must not track (build output, editor files, local settings). |
| `LICENSE`, `CHANGELOG.md` | Ordinary tracked files; the changelog is written by hand. |
| `.github/workflows/ci.yml` | Runs when commits reach GitHub (`github-actions.md`). |

## How it works underneath

Git has three places for your work:

1. The **working folder**: the files you see and edit.
2. The **staging area** (also called the index): a list of changes you chose to include in the next commit. `git add FILE` puts the current content of FILE there.
3. The **history**: the chain of commits. `git commit` takes whatever is staged and records it.

A commit stores a snapshot (not a diff) of every tracked file, as content-addressed objects: the name of each stored object is a **hash**, a long fingerprint computed from its content. Identical content is stored once. The commit id (for example `23f5756`, shortened) is the hash of the commit itself. A **branch** is a movable name that points at one commit; this repository uses one branch, `main`. `HEAD` is "where you are now".

`git status` compares the three places and tells you what differs. A file Git does not track and `.gitignore` does not mention shows as **untracked**.

## Walkthrough of this repository's use

### `.gitignore`

```text
target/
.idea/
*.iml
.vscode/
.env
.DS_Store
.omc/
*.log
```

Each line is a **pattern**. A trailing `/` means a folder.

- `target/`: Maven's build output (compiled classes, test reports, the jar, the copied libraries). It can be rebuilt, and it is large.
- `.idea/`, `*.iml`, `.vscode/`: settings files that editors (IntelliJ IDEA, Visual Studio Code) create. `*` matches any characters, so `*.iml` is every file ending in `.iml`.
- `.env`: a local file for settings and sometimes passwords. The repository commits `.env.example` instead, and never `.env` (`environment-variables.md`).
- `.DS_Store`: a file macOS creates in folders.
- `.omc/`: a local tool folder.
- `*.log`: log files.

The template's rule is "never commit build artifacts or local state", and `git status` after the last commit should show nothing untracked. `.gitignore` is how that rule holds.

### Commit messages

The commits in this repository use a **semantic** style: a type, an optional area in brackets, a colon, and a short summary:

```text
feat(domain): add TimeRange, OfficeHours, Booking, BookingCalendar and the sealed BookResult
fix(docker): drop dependency:go-offline and retry the package step on a dropped connection
docs: add README, ARCHITECTURE, requirements, design review, ADRs and CHANGELOG
```

`feat` adds a feature, `fix` repairs a problem, `docs` changes documentation, `test` adds tests, `ci` and `chore` are build and tooling. The area (`domain`, `docker`) says where. The body of a commit (after a blank line) says why. The last lines are **trailers** such as `Co-Authored-By: ...` that credit the tool that helped write the commit.

## Try it yourself

1. `git log --oneline` lists the commits, newest first, each with its short id and message.
2. `git status` shows `nothing to commit, working tree clean` after a fresh checkout.
3. `git show --stat HEAD` shows what the latest commit changed: files and counts of added and removed lines.
4. `git check-ignore -v target/` prints which line of `.gitignore` ignores `target/` (it exists only after a build).
5. `git ls-files | head` lists the files Git tracks; compare with `ls -a` to see the ignored ones are missing.

## Mistakes people make

- **Committing build output or secrets.** `.gitignore` keeps `target/` and `.env` out. Adding an ignore rule later does not remove a file already committed.
- **Vague messages** like "fix stuff". Here each message names the type, the area and the change.
- **Huge commits** that mix unrelated changes. Here each commit does one job (build setup, domain, tests, docs).
- **Rewriting history others have.** This repository is not pushed, but the rule applies once it is: do not change commits that have been shared.
- **Forgetting `git add` for new files.** A new file is untracked until you add it; `git status` shows it.

## Check yourself

1. *What are the three places Git keeps your work?* The working folder, the staging area, and the history of commits.
2. *What does `.gitignore` do?* Tells Git which files not to track or show as untracked.
3. *What is a commit id?* A hash that identifies one commit and everything it contains.
4. *Why is `.env` ignored but `.env.example` committed?* `.env` may hold local settings or secrets; the example documents the variable names with harmless values.
5. *What does `feat(domain): ...` tell a reader?* The commit adds a feature, in the domain layer.

## Further reading

- Pro Git book: <https://git-scm.com/book/en/v2>
- `.gitignore` pattern rules: <https://git-scm.com/docs/gitignore>
- Git documentation (2.x): <https://git-scm.com/docs>
