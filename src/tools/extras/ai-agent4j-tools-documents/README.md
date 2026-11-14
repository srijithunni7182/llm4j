# ai-agent4j-tools-documents

> 🧰 **Browse every ready-made tool, with what it can do for an agent, in the [Tools catalog](../../../../docs/TOOLS_CATALOG.md).**

The `documents` tool kind: the text of PDF, DOCX, HTML and plain text files under a folder, with the page or paragraph
each part came from. Add it when a workflow starts from documents people send: invoices, contracts, reports, forms.

It is the extras module with a real dependency: **Apache PDFBox** 3.0.8 for PDF (Apache License 2.0; it brings
`pdfbox-io`, `fontbox` and `commons-logging`, all Apache 2.0, about 3.8 MB together). DOCX is read with the JDK (its zip
and XML parsers) and HTML with the JDK's own HTML parser, so neither adds anything.

## From a Loom script

```text
tool Docs { use: documents  root: "inbox"  max_chars: 200000 }
agent Clerk { model: "gemini-2.5-flash"  system: "Read the invoice and give its total, citing the page."  tools: [Docs] }
```

## From Java

```java
Tool docs = new DocumentsKind().create("Docs", Map.of("root", "inbox"), Path.of("."));
String result = docs.execute(Map.of("path", "invoice.pdf"));
```

`new DocumentsKind().check(options, baseDir)` says what is wrong with the options without reading any file.

## Options and arguments

| Option | Meaning |
|---|---|
| `root` | **Required.** The folder the agent may read from, inside the script's directory |
| `max_bytes` | Largest file read (and largest a DOCX may unpack to), default 20m, at most 100m |
| `max_chars` | Most characters of text returned, default 200000, at most 2000000 |
| `max_pages` | Most PDF pages read, default 500, at most 5000 |

The agent gives `path`, relative to the root. The result:

```json
{"path": "invoice.pdf", "format": "pdf", "chars": 1834, "truncated": false,
 "parts": [{"page": 1, "start": 0, "end": 912}, {"page": 2, "start": 913, "end": 1834}],
 "text": "..."}
```

**Positions.** For a PDF each part is a page (numbered from 1); for a DOCX each part is a paragraph (numbered from 1, in
document order, table cells included, an empty paragraph as an empty part). `text` from `start` to `end` is exactly that
page's or paragraph's text, and two parts are separated by one line break. A cut text (`truncated: true`) keeps this true:
a part past the cut is left out and the one the cut falls in is shortened. HTML and text files have no parts. A scanned
PDF with no text layer gives empty pages: this tool does no OCR.

## What it reaches, and what the audit sees

- **Files under the root only:** a path that climbs out, a hidden file or a link out of the folder is refused, and so is
  the run's own state.
- **Untrusted, private text:** `weave audit` sees a tool that reads private data and untrusted content ("reads PDF, DOCX
  and HTML files under inbox"); `text` stays unchecked, since a document can carry instructions. It reaches no network
  and changes nothing.
- **Hostile files:** PDFBox keeps its scratch data in temporary files rather than memory; a DOCX's body is read with a cap
  on its unpacked size (a small zip can unpack to gigabytes) and parsed with no DTD and no external entities; an
  encrypted PDF is refused with the reason. Scripts and styles in HTML are left out of its text.

## Adding it

To a Maven project:

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>ai-agent4j-tools-documents</artifactId>
    <version>6.2.0</version>
</dependency>
```

To `weave`: its `--classes` option brings no transitive dependencies, so the module also publishes a jar with PDFBox
inside it: `weave run flow.loom --classes ai-agent4j-tools-documents-6.2.0-weave.jar`. `weave check` then lists the kind
and where it came from; without the jar it names this module.
