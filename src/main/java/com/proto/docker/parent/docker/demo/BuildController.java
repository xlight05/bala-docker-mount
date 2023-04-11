/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.proto.docker.parent.docker.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.proto.docker.parent.docker.demo.exceptions.BalaNotFoundException;
import com.proto.docker.parent.docker.demo.exceptions.BuildException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Controller for building bala files.
 */
@RestController
@RequestMapping("/project")
public class BuildController {

    private static final Logger LOG = LoggerFactory.getLogger(BuildController.class);

    @PostMapping("/build")
    public ResponseEntity<?> build(@RequestBody BalaFile balaFile) {
        Optional<Path> balaPath = Optional.empty();
        Optional<Path> docsPath = Optional.empty();
        ResponseEntity<?> response;
        try {
            long currentTime = System.nanoTime();
            Path tempPath = Files.createTempDirectory("bala-" + currentTime).toAbsolutePath();
            balaPath = Optional.of(tempPath.resolve(currentTime + ".bala"));
            docsPath = Optional.of(tempPath.resolve("target"));
            downloadBala(balaFile.getPath(), balaPath.get());
            buildBala(balaPath.get());

            ObjectMapper objectMapper = new ObjectMapper();
            ObjectNode docJsonArray = objectMapper.createObjectNode();
            ArrayNode arrayNode = objectMapper.createArrayNode();
            docJsonArray.set("apiDocJsons", arrayNode);

            // Set response
            response = ResponseEntity.ok(docJsonArray);
            LOG.info("generated docs for: " + balaFile.getPath());
        } catch (Throwable e) {
            String errorMsg = "error occurred generating docs: " + balaFile.getPath();
            LOG.error(errorMsg, e);
            Error error = new Error(errorMsg);
            response = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        } finally {
            try {
                if (balaPath.isPresent()) {
                    cleanUp(balaPath.get());
                }
                if (docsPath.isPresent()) {
                    cleanUp(docsPath.get());
                }
            } catch (IOException e) {
                //ignore
            }
        }
        return response;
    }

    /**
     * Download the bala file from URL.
     *
     * @param balaURL  URL for the bala file.
     * @param balaPath The output path of the bala file.
     * @throws BalaNotFoundException When bala URL is invalid or cannot be accessed.
     */
    private void downloadBala(String balaURL, Path balaPath) throws BuildException, BalaNotFoundException {
        try {
            URL balaURI = new URL(balaURL);
            LOG.debug("downloading bala: " + balaURI);
            ReadableByteChannel readableByteChannel = Channels.newChannel(balaURI.openStream());
            FileOutputStream fileOutputStream = new FileOutputStream(balaPath.toFile());
            fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
        } catch (FileNotFoundException e) {
            throw new BuildException("unable to write to temporary bala file: " + balaPath, e);
        } catch (MalformedURLException e) {
            throw new BalaNotFoundException("unable to locate bala file: " + balaURL, e);
        } catch (IOException e) {
            throw new BuildException("error reading from '" + balaURL + "' or writing to '" + balaPath + "'", e);
        }
    }

    /**
     * Building a bala file.
     *
     * @return The built project.
     * @throws BuildException When error occurred while building the bala.
     */
    public static void buildBala(Path downloadPath) throws BuildException {
        try {
            String balaName = downloadPath.getFileName().toString();
            //run the following command using process builder
            //docker run -v `pwd`:/home/ballerina ballerina/ballerina:2201.4.1 bal build
            ProcessBuilder processBuilder = new ProcessBuilder();
//            processBuilder.directory(downloadPath.getParent().toFile());
//            processBuilder.command("docker", "version");
            System.out.println(downloadPath.toAbsolutePath().toString());
            String compiledBalaVersion = getBallerinaVersion(downloadPath);
            System.out.println(compiledBalaVersion);
            Path ballerinaDistPath = getBallerinaDistPath(compiledBalaVersion);
            if (ballerinaDistPath == null) {
                throw new BuildException("unable to locate ballerina dist");
            }
            processBuilder.command(ballerinaDistPath.toAbsolutePath() + "/bin/bal", "doc", downloadPath.toAbsolutePath().toString());
//            processBuilder.command("bal", "doc", downloadPath.toAbsolutePath().toString());
            processBuilder.inheritIO();
            Process start = processBuilder.start();
            start.waitFor();
            start.exitValue();
        } catch (Exception e) {
            throw new BuildException("error occurred when building: " + e.getMessage(), e);
        }
    }

    public static String getBallerinaVersion(Path balaPath) throws IOException {
        ZipFile zipFile = new ZipFile(balaPath.toFile());
        // get the file package.json from the zipFile and read the content and store it in string
        ZipEntry zipEntry = zipFile.getEntry("package.json");
        String content = new String(zipFile.getInputStream(zipEntry).readAllBytes());
        // convert the string to json object using gson
        Gson gson = new Gson();
        JsonObject jsonObject = gson.fromJson(content, JsonObject.class);
        String ballerinaVersion = jsonObject.get("ballerina_version").getAsString();
        // Remove pre releases from the string
        return ballerinaVersion.split("-")[0];
    }

    public static Path getBallerinaDistPath(String requestedBallerinaVersion) {
        String distPath = "/dists/";
        Path distDir = Paths.get(distPath);
        File[] files = distDir.toFile().listFiles();
        if (files == null) {
            return null;
        }
        for (File file: files) {
            String name = file.getName();
            if (name.contains(requestedBallerinaVersion)) {
                return file.toPath();
            }
        }
        return null;
    }

    /**
     * Delete directory or file.
     *
     * @param path The path of directory or file.
     * @throws IOException When deleting.
     */
    private void cleanUp(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }
}
