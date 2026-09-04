# Bundled JARs

| File | Source | Notes |
|------|--------|-------|
| `common-data-1.0.0.jar` | `be-golden-boat-bonanza/app/libs/common-data-1.0.0.jar` | Proprietary GaaS library. Used by the gRPC simulation for MessagePack encoding of `PluginRequest.data` and `ConnectAndCallRequest.user.parameters`. Java 21 bytecode — `:core:build.gradle` runs a `downgradeGaasJar` task that rewrites class-file versions to Java 17 (output: `build/libs-jdk17/`). |
