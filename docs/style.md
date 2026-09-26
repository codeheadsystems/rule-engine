# Documentation style

The register, the emphasis rules, and the punctuation conventions the documentation is written to,
together with the terminology it uses. A reference manual describes a system; it does not argue for
one.

Most of what follows is subtractive. Where a rule and a habit disagree, the rule wins and the habit
is the defect.

## Scope

| Under this guide | Exempt, and why |
|---|---|
| `README.md`, `rule-engine-example/README.md` | |
| every file under `docs/` except the specification | |
| `RELEASING.md`, `SECURITY.md` | |
| `CHANGELOG.md`, from the 1.1.0 entry onward | |
| `site/index.html`, the published project site | |
| | `docs/rule-engine-spec.md` is the design of record. Source comments cite its section numbers and wording, and its amendments record decisions as they were made |
| | a `CHANGELOG.md` entry for a version already released is the record of what that release said |
| | `CLAUDE.md` and `.claude/` are session tooling rather than product documentation |
| | source comments and Javadoc follow the conventions in `CLAUDE.md` |

The records are exempt because editing them for register would falsify them. They are quoted
history, and history is allowed to sound like itself.

On the site, the HTML forms are the same marks: `&mdash;` is an em dash, and `<strong>` and `<b>`
are bold.

## Register

Documentation is written in the third person, in the present tense, about the engine.

- The subject of a sentence is the thing being described, not the reader and not the author. Write
  "an `update` that changes no tested path propagates nothing", not "you will see nothing happen".
- Second person is correct in three places. The first is the numbered steps of a procedure, where
  the imperative is the clearest form: "Tag the commit." "Push the tag." Everything around those
  steps returns to the third person.
- The second is `docs/dsl-guide.md`. It is a tutorial addressed to a rule author with a rule to
  write, and a guide that told them "an author writes a `where:` block" when it means "write a
  `where:` block" is being formal at the reader's expense. So instructions there are second person
  and descriptions stay third: "Add `noLoop: true` to the rule. The rule then does not re-fire on a
  change its own actions made." Every other rule in this guide applies to it unchanged.
- The third is this guide, whose rules are instructions to whoever writes the documentation.
- Do not address the reader's expectations, assumptions, or feelings. "This will surprise you" and
  "worth knowing before you start" describe a conversation rather than a system.
- Do not write in the first person, singular or plural. The documentation has no narrator.

## Emphasis

Bold marks the first occurrence of a defined term in the document that defines it. It has no other
use. Bold applied to a clause for stress has a cumulative effect: where a fifth of the text is
emphasised, emphasis carries no information.

- No bold for stress, contrast, warning, or surprise, and no bold run-in lead at the start of a
  paragraph or list item. A lead that labels what follows is a heading, a table row, or the first
  sentence.
- No capitalised words for stress: `NOT`, `ONE`, `NEVER`, `ALWAYS`. Capitals are for acronyms,
  enum constants such as `NOT_EXISTS` and `RETHROW`, environment variables, and other identifiers
  that are genuinely spelled that way.
- No italics for stress. Italics mark a term quoted as a term, and little else.

Where a fact is important, give it its own sentence, its own paragraph, or its own heading. Position
carries emphasis in a reference manual; typography does not. The eviction hazard that `README.md`
keeps inline is the example: it is important because of where it sits, not because of how it is set.

## Headings

A heading is an index entry and a link target. It labels the material beneath it.

- Write a noun phrase. "Truth maintenance", not "A logical insert withdraws itself when its match
  stops holding".
- No commas, no conjunctions joining two clauses, no question forms, no verbs of judgement
  ("deliberately", "on purpose", "worth", "why").
- Eight words is the practical ceiling, counting a code span as one word.
- Headings are link anchors, and renaming one is an interface change. A rename updates every
  reference to the old anchor in the same change: other documents, `CHANGELOG.md`, the site, source
  comments, and `CLAUDE.md`. Where a document this guide exempts links the old anchor, the renamed
  heading keeps it working with an `<a id="old-anchor"></a>` line directly above it.

## Justification

State what the engine does. Explain a decision only where a reader who does not know it would draw a
wrong conclusion, and then explain it plainly, in its own sentence.

- Do not defend a design against an imagined objection. "That is deliberate rather than
  unfinished", "rather than an oversight" and "this is the design working" all answer a criticism
  nobody reading a manual has made.
- Do not certify a claim's provenance in passing. "Measured, not reasoned about", "read out of the
  source" and "and none of it is hedged" are assurances about the author's diligence. Where
  provenance genuinely matters, such as a benchmark's conditions, it is content: give it a sentence
  that says what was measured, on what, and when.
- The argument for a design, and the alternatives it rejected, live in the specification. A document
  that needs them states the consequence and links the section, such as
  [§4.4](rule-engine-spec.md), rather than restating the argument.
- Do not write about the document. A document does not explain why it exists, why it is separate
  from another document, how many times it has been corrected, or what it is not. Routing belongs in
  `README.md`'s documentation table.

## Punctuation and mechanics

- No em dashes and no spaced double hyphens. Use a comma, a semicolon, a colon, parentheses, or two
  sentences.
- Use a serial comma.
- British spelling in prose: `behaviour`, `serialise`, `materialise`, `optimisation`. Technical
  terms keep the spelling of the thing they name, and an identifier is quoted exactly as the source
  spells it.
- Code formatting for anything a machine reads: identifiers, file paths, module names, Gradle tasks,
  rule-file keys and operators, system properties, and literal values.
- Wrap prose at 100 columns.
- Tables take a header row that names the columns. A table is for material with a repeating shape;
  prose with pipes in it is not a table.

## Examples as fixtures

Every rule file printed in `README.md` or under `docs/` is compiled by `DocExamplesTest`, or by
`CelDocExamplesTest` when it uses a `condition:` or `$expr`, and `rule-engine-example/README.md` is
read by `ReadmeExamplesTest`. An edit made for style changes prose and leaves code blocks alone. A
code block that changes is a change to a fixture, and the build decides whether it still holds.

## Terminology

| Use | Instead of |
|---|---|
| the rule engine, on first mention in a document; the engine thereafter | this engine, the library, the product, the system |
| `rule-engine`, when naming the repository or the Gradle build; `rule-engine-core` and so on for a module | |
| a host (the application that embeds the engine), a rule author, an operator | you, the user, the developer |
| a consumer, only for a build that depends on a published module | a client, a user of the library |
| a caller, for whatever invokes a specific method | |
| refuses, returns, throws, fires | will refuse, is going to return |

A term defined in the specification's [glossary](rule-engine-spec.md#glossary) (fact, rule set,
session, working memory, activation, refraction and the rest) is used as the glossary defines it,
everywhere, and is not redefined in passing.

## Review

Nothing in the build enforces this guide. It is applied when a document is written and checked when
one is reviewed. The mechanical rules (dashes, capitals, bold, heading shape) are cheap to check by
eye; register, justification, and terminology need a reader.
