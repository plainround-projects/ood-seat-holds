# Markdown (`README.md`, `ARCHITECTURE.md` and the other `.md` files)

## What it is

Markdown is a way of writing formatted text in a plain text file. You mark headings with `#`, lists with `-`, emphasis with `*` or `` ` ``, and a viewer (GitHub, an editor preview) turns the marks into headings, lists, bold text and tables. Because it is plain text, Git can show exactly what changed, and anyone can read it even without a viewer. This repository uses the **GitHub-flavored** version (**GFM**), which adds tables and fenced code blocks.

## Why this repository uses it

The template asks for a detailed `README.md`, an `ARCHITECTURE.md`, a `CHANGELOG.md` and decision records (`docs/adr/`). Markdown is the standard for those on GitHub: the README shows up as the front page of the repository.

## Where it appears

| Path | What it does with Markdown |
| --- | --- |
| `README.md` | Purpose, setup, commands, "Done when" proof table, project structure, verification. |
| `ARCHITECTURE.md` | Components, diagrams (Mermaid, `mermaid.md`), decisions, limits. |
| `CHANGELOG.md` | What changed in each version. |
| `docs/` | Decision records in `docs/adr/`. |
| `tech/` | These notes. |

## How it works underneath

Markdown is only text until a program **renders** it. GitHub's renderer reads each line:

- A line starting with `#`, `##`, `###` is a heading of level 1, 2, 3.
- A line starting with `- ` or `1. ` starts a list item. Indenting nests lists.
- Text between backticks `` `like this` `` is shown as code. Three backticks on a line of their own start and end a **fenced code block**; a word after the opening fence (```` ```java ````) names the language for colors. A block fenced as `mermaid` is drawn as a diagram by GitHub.
- `**bold**` and `*italic*` style text. `[text](address)` is a link.
- A **table** is rows of `|`-separated cells with a second row of dashes: `| a | b |` then `| --- | --- |`.
- A blank line separates paragraphs. Two spaces at the end of a line force a line break.

## Walkthrough of this repository's use

### A table that carries the proof

```md
## Done when: the lesson's checklist and the proof for each line

| # | Done when (from the lesson) | Command | What proves it |
| --- | --- | --- | --- |
| 1 | The diagram shows which component guarantees "a seat is sold at most once", and it is not the domain object. | Read the [class diagram](ARCHITECTURE.md#class-diagram), the ["Components"](ARCHITECTURE.md#components-and-what-each-owns) table and [ADR 0001](docs/adr/0001-the-guarantee-lives-in-the-store.md) | `SeatStore.update` is the guarantee; `PaymentGateway` is drawn as a named port with no adapter. `ArchitectureTest.theDomainHasNoLocksOrThreadsBecauseTheGuaranteeLivesInTheStore` fails the build if the domain imports `java.util.concurrent`. |
```

The "Done when" section is a four-column table: the number, the lesson's line, the command to run, and what proves it. The row of `| --- |` marks above the first data row is what makes it a table, and the row before them is the header. Putting the proof in a table lets a reader scan each line of the lesson against its evidence.

### Every Markdown file in this repository

| File | Its title (the first heading) | What the file is for |
| --- | --- | --- |
| `ARCHITECTURE.md` | Architecture | The design: layers and the dependency rule, the Mermaid class diagram with the lesson's names, the main flows, and where the design bends (`mermaid.md`). |
| `CHANGELOG.md` | Changelog | What changed in each released version, in the Keep a Changelog layout: a heading per version (`## [0.1.0] - date`) and a sub-heading per kind of change (`### Added`). Version numbers follow Semantic Versioning: MAJOR.MINOR.PATCH, where a breaking change raises MAJOR, a new compatible feature raises MINOR and a fix raises PATCH. |
| `README.md` | ood-seat-holds | The front page: what the project is, how to run it, the lesson's Done-when lines with their proof, the project tree, the quality gates and the verification record. |
| `docs/adr/0001-the-guarantee-lives-in-the-store.md` | 0001. "Sold at most once" lives in the store's compare-and-set | One **architecture decision record** (ADR): a short page that saves a decision and the reason for it, in four parts: a Status line, **Context** (the problem and the options), **Decision** (what was chosen) and **Consequences** (what gets easier and what gets harder). A later reader learns why the code looks as it does without asking the author. |
| `docs/adr/0002-compare-the-state-not-a-version.md` | 0002. The conditional UPDATE compares the state, not a version number | One **architecture decision record** (ADR): a short page that saves a decision and the reason for it, in four parts: a Status line, **Context** (the problem and the options), **Decision** (what was chosen) and **Consequences** (what gets easier and what gets harder). A later reader learns why the code looks as it does without asking the author. |
| `docs/adr/0003-seat-states-are-data-and-transitions-are-pure.md` | 0003. Seat states are data and transitions are pure functions | One **architecture decision record** (ADR): a short page that saves a decision and the reason for it, in four parts: a Status line, **Context** (the problem and the options), **Decision** (what was chosen) and **Consequences** (what gets easier and what gets harder). A later reader learns why the code looks as it does without asking the author. |

The `LICENSE` file is plain text, not Markdown: it holds the MIT License, which lets anyone use, copy, change and share the code as long as the copyright line and the licence text stay with it, and which says the software comes with no warranty.

Each file is ordinary Markdown: headings, paragraphs, lists, tables and fenced blocks as explained above. The decision records are the plainest: a `#` title, a `- Status:` list item, then three `##` headings (Context, Decision, Consequences). The changelog is a `#` title, a sentence, and one `##` block per version.

### Headings as structure

`README.md` uses `##` headings in a fixed order (prerequisites, setup and run, done when, stretch goals, simplified for teaching, project structure, quality gates, technology notes, verification, license). Those are the headings the template asks for. A reader (or a tool) can jump from heading to heading.

### Fenced code

Commands appear in blocks fenced with `bash`, so the Claude desktop app and GitHub show a copy button, and expected output appears in blocks fenced with `text`. The language word has no effect on what the block says; it chooses the coloring.

### A tree in a code block

The "Project structure" section draws the folder tree with box characters inside a `text` block. A fenced block keeps the spacing exactly, which a normal paragraph would not.

## Try it yourself

1. Open `README.md` in an editor's preview (or on GitHub after pushing). Compare it with `cat README.md`: the symbols disappear in the preview.
2. `grep -n '^## ' README.md` lists every heading: the outline of the document.
3. `grep -c '^| ' README.md` counts table rows.
4. Add two spaces at the end of a line in a copy of a paragraph and see the preview break the line.
5. `git diff` after editing a README shows the change line by line: the reason plain text is used.

## Mistakes people make

- **A table without the separator row.** It shows as plain text with bars.
- **Forgetting the blank line** before a list or table. Some renderers then glue it to the paragraph above.
- **Unclosed code fences.** Everything after turns into code. Count the fences.
- **Using `|` inside a table cell** without escaping it (`\|`).
- **Line-length habits from other formats.** GitHub wraps text, so one paragraph can be one long line; this repository keeps each paragraph on one line for clean diffs.

## Check yourself

1. *How do you write a level-two heading?* `## Heading`.
2. *What do three backticks do?* Start (and end) a fenced code block.
3. *What does the word after the opening fence mean?* The language, used for coloring; a `mermaid` fence is drawn as a diagram.
4. *What turns a row of `|` cells into a table?* The separator row of dashes under the first row.
5. *Why is Markdown good for Git?* It is plain text, so differences between versions are readable.

## Further reading

- GitHub Flavored Markdown spec: <https://github.github.com/gfm/>
- GitHub Docs, basic writing and formatting syntax: <https://docs.github.com/get-started/writing-on-github/getting-started-with-writing-and-formatting-on-github/basic-writing-and-formatting-syntax>
