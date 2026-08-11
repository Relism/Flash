# Build-Time Manifest Generation

`WebBundlerBuild` turns an already-built directory into the `asset-manifest.json` that
`ClasspathAssetsSource` needs (see `asset-sources.md`). It does not run a frontend build itself —
it only scans a directory that already contains the final files:

- `VITE`: point it at whatever `dist/` the existing frontend build tooling already produces.
- `STATIC`: point it directly at the static asset folder — there's no separate build step.

It's meant to run once per build, from the consumer project's own build, not from the running
application (`ClasspathAssetsSource` is explicitly unsupported in DEV — see `dev-lifecycle.md`).

## Wiring it into a Maven build

No dedicated Flash5 Maven plugin — `WebBundlerBuild` is a plain class with a `main`, invoked via
the standard `exec-maven-plugin`, bound to run before the resources are packaged:

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>web-bundler-manifest</id>
            <phase>process-classes</phase>
            <goals><goal>java</goal></goals>
            <configuration>
                <mainClass>dev.relism.flash.ext.webbundler.WebBundlerBuild</mainClass>
                <arguments>
                    <argument>${project.build.outputDirectory}/web/dist</argument>
                </arguments>
            </configuration>
        </execution>
    </executions>
</plugin>
```

This assumes the built frontend (`web/dist/`, or a static folder) is already copied under
`target/classes/web/dist` by that point — e.g. via `maven-resources-plugin`'s `copy-resources`
goal, or by running the frontend build with an output directory that points there directly. Once
the manifest is written alongside those files, they're just classpath resources: a plain `mvn
package` (or `maven-shade-plugin` for a fat jar) picks them up with no further configuration, and
the app can then be configured with `.assetsFromClasspath("web/dist")`.
