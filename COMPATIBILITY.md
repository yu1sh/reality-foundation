# Compatibility and reproducibility

| Input | Exact value | Evidence |
| --- | --- | --- |
| Minecraft | 1.20.1 | `gradle.properties`, `mods.toml` |
| Forge | 47.4.10 | `gradle.properties`, Forge metadata |
| ForgeGradle | 6.0.54 | `forge-1.20.1/build.gradle` |
| mappings | official 1.20.1 | `forge-1.20.1/build.gradle` |
| Java vendor | Eclipse Temurin | `supply-chain/toolchain-manifest.json` |
| Java | 17.0.20+8, Linux x64 | exact setup-java selector and manifest |
| JDK archive SHA-256 | `be7668bc030d578b83d6d5ef9221d6d6729bbbca8cf94a7d52e16ac68b5a5a35` | verified archive acquisition evidence |
| class major | 61 | compiler/artifact gate |
| Gradle | 8.8 | wrapper and manifest |
| Gradle distribution SHA-256 | `a4b4158601f8636cdeeab09bd76afb640030bb5b144aafe261a5e8af027dc612` | launcher/manifest |
| Forge MDK SHA-256 | `73e0122becd05e39b47eced54e030380d66411850ed86786a2d58ecd886b0451` | manifest |
| reality-core | `aa9956e03a8d163d1d0a2bbdcf38cc328fc37397` | required sibling source ref |

The workflow verifies `java -version` at 17.0.20 and
`java.runtime.version` at 17.0.20+8. It rejects `17`, `17.x`, `latest`, and
other relaxed selectors. The setup-java cache is not treated as archive
verification; the archive SHA is separately recorded as acquisition evidence.

The public source must use environment expressions for local tool locations.
Local absolute paths are rejected by the source-policy self-test.
