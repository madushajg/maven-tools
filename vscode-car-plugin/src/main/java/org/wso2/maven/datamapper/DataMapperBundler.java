/*
 * Copyright (c) 2024, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.maven.datamapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.wso2.maven.CARMojo;

public class DataMapperBundler {
    private final CARMojo mojoInstance;
    private final String resourcesDirectory;

    public DataMapperBundler(CARMojo mojoInstance, String resourcesDirectory) {
        this.mojoInstance = mojoInstance;
        this.resourcesDirectory = resourcesDirectory;
    }

    public void bundleDataMapper() {
        appendDataMapperLogs();
        String mavenHome = getMavenHome();

        if (mavenHome == null) {
            mojoInstance.logError("Could not determine Maven home.");
            return;
        }

        createDataMapperArtifacts();

        Invoker invoker = new DefaultInvoker();
        invoker.setMavenHome(new File(mavenHome));
        invoker.setOutputHandler(new InvocationOutputHandler() {
            @Override
            public void consumeLine(String line) {
                // Do nothing to suppress maven output
            }
        });

        InvocationRequest request = new DefaultInvocationRequest();
        Path baseDir = Paths.get(System.getProperty("user.dir"));
        Path pomPath = baseDir.resolve(Paths.get("." + File.separator + Constants.POM_FILE_NAME));
        request.setPomFile(pomPath.toFile());

        try {
            // Install Node and NPM
            mojoInstance.logInfo("Installing Node and NPM");
            request.setGoals(Collections.singletonList(Constants.INSTALL_NODE_AND_NPM_GOAL));
            Properties properties = new Properties();
            properties.setProperty("nodeVersion", Constants.NODE_VERSION);
            properties.setProperty("npmVersion", Constants.NPM_VERSION);
            request.setProperties(properties);
            InvocationResult result = invoker.execute(request);

            if (result.getExitCode() != 0) {
                mojoInstance.logError("Node and NPM installation failed.");
                mojoInstance.logError(result.getExecutionException().getMessage());
                return;
            }

            // Run npm install
            mojoInstance.logInfo("Running npm install");
            request = new DefaultInvocationRequest();
            request.setGoals(Collections.singletonList(Constants.NPM_GOAL));
            properties = new Properties();
            properties.setProperty("arguments", Constants.NPM_INSTALL);
            request.setProperties(properties);
            result = invoker.execute(request);

            if (result.getExitCode() != 0) {
                mojoInstance.logError("npm install failed.");
                mojoInstance.logError(result.getExecutionException().getMessage());
                return;
            }

            // Bundle data mappers
            mojoInstance.logInfo("Start bundling data mappers");
            String dataMapperDirectoryPath = resourcesDirectory + File.separator + Constants.DATA_MAPPER_DIR_PATH;
            List<Path> dataMappers = listSubDirectories(dataMapperDirectoryPath);

            for (Path dataMapper : dataMappers) {
                copyTsFiles(dataMapper);
                String dataMapperName = dataMapper.getFileName().toString();

                mojoInstance.logInfo("Bundling data mapper: " + dataMapperName);

                createWebpackConfig(dataMapperName);

                request = new DefaultInvocationRequest();
                request.setGoals(Collections.singletonList(Constants.NPM_RUN_BUILD_GOAL
                        + " -Dexec.executable=" + Constants.NPM_COMMAND
                        + " -Dexec.args=\"" + Constants.RUN_BUILD + "\""));
                result = invoker.execute(request);

                if (result.getExitCode() != 0) {
                    mojoInstance.logError("Failed to bundle data mapper: " + dataMapperName);
                    mojoInstance.logError(result.getExecutionException().getMessage());
                    return;
                }

                mojoInstance.logInfo("Bundle completed for data mapper: " + dataMapperName);
                Path bundledJsFilePath = Paths.get("." + File.separator
                        + Constants.DATA_MAPPER_ARTIFACTS_DIR_NAME + File.separator + Constants.BUNDLED_JS_FILE_NAME);
                copyBundledJsFile(bundledJsFilePath.toString(), dataMapper);

                removeTsFiles();
                removeWebpackConfig();
            }

            mojoInstance.logInfo("Data mapper bundling completed successfully.");
        } catch (MavenInvocationException e) {
            mojoInstance.logError("Failed to bundle data mapper.");
            mojoInstance.logError(e.getMessage());
        } finally {
            removeBundlingArtifacts();
        }
    }

    private void createDataMapperArtifacts() {
        mojoInstance.logInfo("Creating data mapper artifacts");
        ensureDataMapperDirectoryExists();
        createPackageJson();
        createConfigJson();
    }

    /**
     * Append log of data-mapper bundling process.
     */
    private void appendDataMapperLogs() {
        mojoInstance.getLog().info("------------------------------------------------------------------------");
        mojoInstance.getLog().info("Bundling Data Mapper");
        mojoInstance.getLog().info("------------------------------------------------------------------------");
    }

    private void createPackageJson() {
        String packageJsonContent = "{\n" +
                "    \"name\": \"data-mapper-bundler\",\n" +
                "    \"version\": \"1.0.0\",\n" +
                "    \"scripts\": {\n" +
                "        \"build\": \"tsc && webpack\"\n" +
                "    },\n" +
                "    \"devDependencies\": {\n" +
                "        \"typescript\": \"^4.4.2\",\n" +
                "        \"webpack\": \"^5.52.0\",\n" +
                "        \"webpack-cli\": \"^4.8.0\",\n" +
                "        \"ts-loader\": \"^9.2.3\"\n" +
                "    }\n" +
                "}";

        try (FileWriter fileWriter = new FileWriter(Constants.PACKAGE_JSON_FILE_NAME)) {
            fileWriter.write(packageJsonContent);
        } catch (IOException e) {
            mojoInstance.logError("Failed to create package.json file.");
            mojoInstance.logError(e.getMessage());
        }
    }
//
    private void createConfigJson() {
        String tsConfigContent = "{\n" +
                "    \"compilerOptions\": {\n" +
                "        \"outDir\": \"./target\",\n" +
                "        \"module\": \"commonjs\",\n" +
                "        \"target\": \"es5\",\n" +
                "        \"sourceMap\": true\n" +
                "    },\n" +
                "    \"include\": [\n" +
                "        \"./" + Constants.DATA_MAPPER_ARTIFACTS_DIR_NAME + "/**/*\"\n" +
                "    ]\n" +
                "}";

        try (FileWriter fileWriter = new FileWriter(Constants.TS_CONFIG_FILE_NAME)) {
            fileWriter.write(tsConfigContent);
        } catch (IOException e) {
            mojoInstance.logError("Failed to create tsconfig.json file.");
            mojoInstance.logError(e.getMessage());
        }
    }
//
    private void createWebpackConfig(String dataMapperName) {
        String webPackConfigContent = "const path = require(\"path\");\n" +
                "module.exports = {\n" +
                "    entry: \"." + File.separator + Constants.DATA_MAPPER_ARTIFACTS_DIR_NAME + File.separator + dataMapperName + ".ts\",\n" +
                "    module: {\n" +
                "        rules: [\n" +
                "            {\n" +
                "                test: /\\.tsx?$/,\n" +
                "                use: \"ts-loader\",\n" +
                "                exclude: /node_modules/,\n" +
                "            }\n" +
                "        ],\n" +
                "    },\n" +
                "    resolve: {\n" +
                "        extensions: [\".ts\", \".js\"],\n" +
                "    },\n" +
                "    output: {\n" +
                "        filename: \"" + Constants.BUNDLED_JS_FILE_NAME + "\",\n" +
                "        path: path.resolve(__dirname, \"" + Constants.DATA_MAPPER_ARTIFACTS_DIR_NAME + "\"),\n" +
                "    },\n" +
                "};";

        try (FileWriter fileWriter = new FileWriter(Constants.WEBPACK_CONFIG_FILE_NAME)) {
            fileWriter.write(webPackConfigContent);
        } catch (IOException e) {
            mojoInstance.logError("Failed to create webpack.config.js file.");
            mojoInstance.logError(e.getMessage());
        }
    }

    private List<Path> listSubDirectories(String directory) {
        Path dirPath = Paths.get(directory);
        List<Path> subDirectories = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)) {
            for (Path path : stream) {
                if (Files.isDirectory(path) && !path.equals(dirPath)) {
                    subDirectories.add(path);
                }
            }
        } catch (IOException e) {
            mojoInstance.logError("Failed to find data mapper directories.");
            mojoInstance.logError(e.getMessage());
        }
        return subDirectories;
    }

    private void copyTsFiles(final Path sourceDir) {
        final Path destDir = Paths.get("." + File.separator + Constants.DATA_MAPPER_ARTIFACTS_DIR_NAME);

        try {
            final List<Path> fileList = new ArrayList<>();
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".ts")) {
                        fileList.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            for (Path sourcePath : fileList) {
                Path destPath = destDir.resolve(sourceDir.relativize(sourcePath));
                try {
                    Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    mojoInstance.logError("Failed to copy data mapper file: " + sourcePath);
                    mojoInstance.logError(e.getMessage());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void copyBundledJsFile(String sourceFile, Path destinationDir) {
        mojoInstance.logInfo("Copying bundled js file: " + sourceFile);
        Path sourcePath = Paths.get(sourceFile);
        Path destPath = destinationDir.resolve(sourcePath.getFileName());

        try {
            Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            mojoInstance.logError("Failed to copy bundled js file: " + sourcePath);
            mojoInstance.logError(e.getMessage());
        }
    }

    private void ensureDataMapperDirectoryExists() {
        Path dataMapperPath = Paths.get("." + File.separator + Constants.DATA_MAPPER_ARTIFACTS_DIR_NAME);
        if (!Files.exists(dataMapperPath)) {
            try {
                Files.createDirectories(dataMapperPath);
            } catch (IOException e) {
                mojoInstance.logError("Failed to create data-mapper artifacts directory: " + dataMapperPath);
                mojoInstance.logError(e.getMessage());
            }
        }
    }

    private void removeTsFiles() {
        Path dataMapperPath = Paths.get("." + File.separator + Constants.DATA_MAPPER_ARTIFACTS_DIR_NAME);

        try {
            Files.walkFileTree(dataMapperPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".ts")) {
                        try {
                            Files.delete(file);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            mojoInstance.logError("Error while removing data-mapper source files.");
            mojoInstance.logError(e.getMessage());
        }
    }

    private void removeWebpackConfig() {
        Path filePath = Paths.get(Constants.WEBPACK_CONFIG_FILE_NAME);
        try {
            Files.delete(filePath);
        } catch (IOException e) {
            mojoInstance.logError("Error while removing webpack.config.js file.");
            mojoInstance.logError(e.getMessage());
        }
    }

    private String getMavenHome() {
        mojoInstance.logInfo("Finding maven home");

        ProcessBuilder processBuilder = new ProcessBuilder();
        if (System.getProperty("os.name").toLowerCase().contains(Constants.OS_WINDOWS)) {
            processBuilder.command("cmd.exe", "/c", "mvn -v");
        } else {
            processBuilder.command("sh", "-c", "mvn -v");
        }
        try {
            Process process = processBuilder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Maven home: ")) {
                    return line.split("Maven home: ")[1].trim();
                }
            }
        } catch (IOException e) {
            mojoInstance.logError("Failed to find maven home");
            mojoInstance.logError(e.getMessage());
        }
        return null;
    }

    private void removeBundlingArtifacts() {
        mojoInstance.logInfo("Cleaning up data mapper bundling artifacts");
        String[] pathsToDelete = {
            Constants.PACKAGE_JSON_FILE_NAME,
            Constants.TS_CONFIG_FILE_NAME,
            Constants.WEBPACK_CONFIG_FILE_NAME,
            "package-lock.json",
            "." + File.separator + Constants.DATA_MAPPER_ARTIFACTS_DIR_NAME,
            "." + File.separator + "node",
            "." + File.separator + "node_modules",
            "." + File.separator + "target"
        };

        for (String path : pathsToDelete) {
            File file = new File(path);
            deleteRecursively(file);
        }
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] entries = file.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    deleteRecursively(entry);
                }
            }
        }
        if (!file.delete()) {
            mojoInstance.logError("Failed to delete " + file.getPath());
        }
    }
}
