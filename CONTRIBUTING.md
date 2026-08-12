# Contributing

Changes in this subtree must remain Apache-2.0 compatible, Java 17, and
within `repos/reality-foundation/**`. Do not add gameplay rules, persistence,
database migrations, third-party assets, binary artifacts, hybrid server
adapters, or unbounded/dynamic dependencies.

Use the reviewed sibling `reality-core` source at the exact ref in
`gradle.properties`. Keep Forge at 47.4.10 until the complete release train
is requalified. Do not change core APIs from this repository.

Before proposing a change, run the fixed Java/Gradle checks in `README.md`,
including API tests, `clean check`, the GameTestServer, and the dedicated
server smoke. Run the source scanner, artifact validator, SBOM validator,
`git diff --check`, and the scope check. If a real client was not launched,
say so and do not provide a synthetic screenshot.

Commit messages should explain protocol, lifecycle, security, packaging, or
documentation changes. Pull requests should include test commands and exact
runtime evidence. Never merge, push, tag, or release from an implementation
worktree.
