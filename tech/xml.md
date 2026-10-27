# XML (`pom.xml`, `config/checkstyle.xml`)

## What it is

XML is a text format for structured data written with **tags**. An **element** is a piece of data wrapped in an opening tag and a closing tag: `<version>0.1.0</version>`. Elements can sit inside other elements, which makes a tree. An element can also carry **attributes**: `<module name="Checker">`. XML is older and wordier than YAML or JSON, but it is strict, so tools can check it automatically. Maven's project file and checkstyle's settings file are both XML.

## Why this repository uses it

The tools demand it. Maven reads `pom.xml` and checkstyle reads its rule file, both in XML. There is no choice, and the format is the same in both.

## Where it appears

| Path | What it does with XML |
| --- | --- |
| `pom.xml` | The Maven project description (`maven.md`). |
| `config/checkstyle.xml` | The style rules for the checkstyle plugin (`checkstyle.md`). |

## How it works underneath

A program that reads XML uses a **parser**. The parser walks the text and rebuilds the tree: the outermost element (the **root**) holds its children, which hold theirs. The rules a parser enforces:

- Every opening tag has a matching closing tag, properly nested: `<a><b></b></a>` is fine, `<a><b></a></b>` is an error. An element with nothing inside can be written `<requirePluginVersions/>` (self-closing).
- Exactly one root element.
- Special characters in text must be written as **entities**: `&lt;` for `<`, `&amp;` for `&`.
- `<!-- ... -->` is a comment.
- The first line `<?xml version="1.0" encoding="UTF-8"?>` is the **prolog**: the XML version and how the bytes are read as text.
- A **namespace** (`xmlns="http://maven.apache.org/POM/4.0.0"`) names the vocabulary an element belongs to, so tools can tell Maven's `<version>` from someone else's. An `xsi:schemaLocation` attribute points to a **schema**, a file that says which elements are allowed; editors use it to check and complete the file.

## Walkthrough of this repository's use

### The top of `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
```

- The prolog says XML 1.0 and UTF-8 text.
- `<project ...>` is the root. Its attributes are namespace declarations: `xmlns` says every element is in Maven's POM vocabulary, `xmlns:xsi` declares the prefix `xsi` (the standard "XML Schema instance" namespace), and `xsi:schemaLocation` links version 4.0.0 of Maven's schema `maven-4.0.0.xsd`, which lets an editor flag mistakes.
- `<modelVersion>4.0.0</modelVersion>` is the version of the POM format itself. Maven 3 requires `4.0.0`.

### Elements, nesting and comments in the same file

```xml
<!-- One slf4j-api for HikariCP and Testcontainers, which ask for different lines. -->
```

A comment explains why a setting exists (this one says why `slf4j-api` is pinned). Comments are ignored by Maven.
Elements nest to show ownership: a `<plugin>` is inside `<plugins>`, inside `<build>`, inside `<project>`. Text such as `${junit.version}` inside an element is plain text; Maven (not the XML parser) replaces it with the property's value.

### Attributes in `config/checkstyle.xml`

```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC
    "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
    "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
```

Here `<!DOCTYPE ...>` points at a **DTD** (an older kind of schema) that checkstyle uses to check the file. `<module name="Checker">` has an **attribute**, `name`, with a value in quotes. Each rule is a `<module>` with its `name`, and settings are `<property name="..." value="..."/>` elements. The whole file is explained in `checkstyle.md`.

## Try it yourself

1. `xmllint --noout pom.xml` (macOS has `xmllint`) prints nothing if the file is well-formed. Delete a closing tag in a copy and run it again to see the error message.
2. `xmllint --xpath "string(/*[local-name()='project']/*[local-name()='artifactId'])" pom.xml` prints `ood-seat-holds`: reading one element out of the tree.
3. `grep -c "<dependency>" pom.xml` counts how many libraries are listed.
4. `./mvnw help:effective-pom | head -5` shows the same kind of tree with Maven's defaults added.
5. `xmllint --format config/checkstyle.xml | head -20` prints the file re-indented, showing the nesting.

## Mistakes people make

- **A missing or mismatched closing tag.** The whole file fails to load, and the error points at the line where the parser noticed, not where the mistake began.
- **An unescaped `&` or `<` in text.** Write `&amp;` and `&lt;`.
- **Editing generated XML in `target/`.** It is rebuilt output.
- **Forgetting the namespace** when querying with XPath tools. Elements in a namespace need it in the query (the `local-name()` trick above avoids it).
- **Tabs versus spaces confusion.** XML does not care, but the `.editorconfig` sets two spaces for consistency.

## Check yourself

1. *What makes an XML file well-formed?* One root element; every opening tag closed, properly nested; special characters escaped.
2. *What is the root element of `pom.xml`?* `<project>`.
3. *What is an attribute?* A `name="value"` pair inside an opening tag.
4. *What does `<requirePluginVersions/>` mean?* An element with no content, written in self-closing form.
5. *What is the `xsi:schemaLocation` for?* It points to the schema, so editors and tools can validate and complete the file.

## Further reading

- XML 1.0 (Fifth Edition): <https://www.w3.org/TR/xml/>
- Maven POM reference (the vocabulary of `pom.xml`): <https://maven.apache.org/pom.html>
