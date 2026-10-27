# EditorConfig (`.editorconfig`)

## What it is

EditorConfig is a tiny file format that tells every text editor the same basic habits for a project: how to indent, which line endings to use, whether to trim spaces at the end of lines. Editors read the file when you open a file in the folder. Most editors need a small plugin for this; some support it built in.

## Why this repository uses it

The template requires a `.editorconfig` in every repository. It keeps the files the formatter does not touch (the `Makefile`, YAML, XML, Markdown) tidy and consistent, and it protects the one place where the wrong indentation breaks things: a `Makefile` needs a tab.

## Where it appears

| Path | What it does with EditorConfig |
| --- | --- |
| `.editorconfig` | The whole file. |
| `Makefile` | Is covered by the `[Makefile]` section (tabs). |
| `pom.xml`, `.github/workflows/ci.yml`, `config/checkstyle.xml`, `docker-compose.yml` | Covered by the 2-space rule for `yml`, `yaml`, `json` and `xml`. |
| `*.java` | Also 2 spaces, which is what `google-java-format` produces (`spotless-google-java-format.md`). The formatter, not EditorConfig, is the authority for Java. |

## How it works underneath

When you open a file, your editor looks for `.editorconfig` in the file's folder, then in each parent folder, until it finds one with `root = true`. It applies every section whose pattern matches the file name (patterns like `*.md` or `[*.{yml,yaml}]`). Later matching sections override earlier ones. The settings only affect what the editor does as you type or save; nothing enforces them at build time, which is why the real format check is Spotless.

## Walkthrough of this repository's use

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 2

[*.{yml,yaml,json,xml}]
indent_size = 2

[Makefile]
indent_style = tab

[*.md]
trim_trailing_whitespace = false
```

- `root = true`: stop searching parent folders. This repository's settings are complete.
- `[*]` applies to every file. `charset = utf-8` saves text as UTF-8 (so accented letters and symbols are stored the same way everywhere). `end_of_line = lf` uses the Unix line ending, one "line feed" character, instead of Windows' two. `insert_final_newline = true` makes files end with a newline, which Unix tools expect. `trim_trailing_whitespace = true` removes spaces at the end of lines on save.
- `indent_style = space` and `indent_size = 2` indent with two spaces per level.
- `[*.{yml,yaml,json,xml}]` repeats the indent size for those four kinds of file. (It matches the default already; it documents that these formats are indented by two spaces on purpose.)
- `[Makefile]` overrides the style for the file called `Makefile`: `indent_style = tab`. A Makefile recipe line must start with a tab character (`make.md`).
- `[*.md]` turns **off** trimming for Markdown. In Markdown, two spaces at the end of a line mean "line break", so trimming would change the meaning.

## Try it yourself

1. `cat .editorconfig` shows the file above.
2. `grep -c "$(printf '\t')" Makefile` counts the lines that contain a tab character (the recipe lines); the count is more than zero.
3. `grep -n ' $' pom.xml` prints nothing: no line of the file ends in a space.
4. `tail -c1 pom.xml | xxd` prints `0a`: the file ends with a newline.
5. Open the repository in an editor with EditorConfig support and press Tab in the `Makefile`: you get a tab, not two spaces.

## Mistakes people make

- **Assuming EditorConfig is enforced.** It only guides editors. The build enforces formatting through Spotless and checkstyle.
- **Spaces in a Makefile.** The `[Makefile]` section exists to stop this.
- **Trimming spaces in Markdown** and losing hard line breaks. Hence the `[*.md]` exception.
- **Mixed line endings** (Windows and Unix) in one file. `end_of_line = lf` prevents it.
- **Forgetting `root = true`,** so a stray `.editorconfig` in a parent folder changes your settings.

## Check yourself

1. *What does `root = true` do?* It stops editors from reading `.editorconfig` files in parent folders.
2. *Why does `[Makefile]` say `indent_style = tab`?* Make requires recipe lines to start with a tab.
3. *Why not trim whitespace in `*.md`?* Two trailing spaces are a line break in Markdown.
4. *Does EditorConfig fail the build if a file is mis-indented?* No; it guides editors only.
5. *Which file settles Java formatting?* The `google-java-format` rules applied by Spotless, not EditorConfig.

## Further reading

- EditorConfig specification: <https://spec.editorconfig.org/> and <https://editorconfig.org/>
