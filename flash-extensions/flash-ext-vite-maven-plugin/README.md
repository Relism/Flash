# flash-ext-vite-maven-plugin

Builds a Vite project during `mvn package` and puts the result in the jar where
[`flash-ext-vite`](../flash-ext-vite/README.md) serves it, so the jar runs on its own.

```xml
<plugin>
    <groupId>dev.relism</groupId>
    <artifactId>flash-ext-vite-maven-plugin</artifactId>
    <version>${flash.version}</version>
    <executions>
        <execution>
            <goals><goal>build</goal></goals>
        </execution>
    </executions>
</plugin>
```

| Page | Content |
|---|---|
| [docs/build.md](docs/build.md) | The `build` goal, parameters, output |
