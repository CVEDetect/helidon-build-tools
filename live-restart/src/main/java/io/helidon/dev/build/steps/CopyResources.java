/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.dev.build.steps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;

import io.helidon.dev.build.BuildComponent;
import io.helidon.dev.build.BuildRoot;
import io.helidon.dev.build.BuildStep;
import io.helidon.dev.build.BuildType;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * A build step that copies resources.
 */
public class CopyResources implements BuildStep {

    @Override
    public BuildType inputType() {
        return BuildType.Resources;
    }

    @Override
    public BuildType outputType() {
        return BuildType.Resources;
    }

    @Override
    public void incrementalBuild(BuildRoot.Changes changes, Consumer<String> stdOut, Consumer<String> stdErr) throws Exception {
        if (!changes.isEmpty()) {
            final BuildRoot sources = changes.root();
            final BuildComponent component = sources.component();
            if (test(component)) {
                final Set<Path> changed = changes.addedOrModified();
                stdOut.accept("Copying " + changed.size() + " resource files");
                final Path srcDir = sources.path();
                final Path outDir = component.outputRoot().path();
                for (final Path srcFile : changed) {
                    copy(srcDir, outDir, srcFile, stdOut);
                }
            }
        }
    }

    private void copy(Path srcDir, Path outDir, Path srcPath, Consumer<String> stdOut) throws IOException {
        final Path relativePath = srcDir.relativize(srcPath);
        final Path outPath = outDir.resolve(relativePath);
        stdOut.accept("Copying resource " + srcPath);
        Files.copy(srcPath, outPath, REPLACE_EXISTING);
    }

    @Override
    public String toString() {
        return "CopyResources{}";
    }
}
