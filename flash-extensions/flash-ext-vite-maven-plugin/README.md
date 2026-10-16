# flash-ext-vite-maven-plugin

Builds a Vite project during `mvn package` and puts the result in the jar where
[`flash-ext-vite`](../flash-ext-vite/README.md) serves it, so the jar runs on its own.

```xml
<plugin>
    <groupId>dev.relism.Flash</groupId>
    <artifactId>flash-ext-vite-maven-plugin</artifactId>
    <version>${flash.version}</version>
    <executions>
        <execution>
            <goals><goal>build</goal></goals>
        </execution>
    </executions>
</plugin>
```

The plugin is published on JitPack like the rest of Flash, so the build also needs JitPack as a
plugin repository:

```xml
<pluginRepositories>
    <pluginRepository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </pluginRepository>
</pluginRepositories>
```

| Page | Content |
|---|---|
| [docs/build.md](docs/build.md) | The `build` goal, parameters, output |
