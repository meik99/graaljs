/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.test.external.testv8;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.graalvm.polyglot.Source;

import com.oracle.truffle.js.lang.JavaScriptLanguage;
import com.oracle.truffle.js.runtime.JSContextOptions;
import com.oracle.truffle.js.test.external.suite.SuiteConfig;
import com.oracle.truffle.js.test.external.suite.TestFile;
import com.oracle.truffle.js.test.external.suite.TestRunnable;
import com.oracle.truffle.js.test.external.suite.TestSuite;

public class TestV8 extends TestSuite {

    private static final String SUITE_NAME = "testv8";
    private static final String SUITE_DESCRIPTION = "Google V8 testsuite";
    private static final String DEFAULT_LOC = Paths.get("lib", "testv8", "testv8-20220810").toString();
    private static final String DEFAULT_CONFIG_LOC = "test";
    private static final String TESTS_REL_LOC = "test";
    private static final String HARNESS_REL_LOC = "";

    private static final String[] PREQUEL_FILES = new String[]{"/test/intl/assert.js", "/test/intl/utils.js", "/test/mjsunit/mjsunit.js"};
    private static final String[] TEST_DIRS = new String[]{"mjsunit", "intl"};
    private static final String TESTS_CONFIG_FILE = "testV8.json";
    private static final String FAILED_TESTS_FILE = "testv8.failed";

    /** An arbitrary limit high enough to pass mjsunit/regress/regress-crbug-160010.js. */
    private static final int STRING_LENGTH_LIMIT = (1 << 28) + 16;

    private final Source mockupSource;
    private final Map<String, String> commonOptions;
    private final List<String> commonOptionsExtLauncher;

    public TestV8(SuiteConfig config) {
        super(config);
        this.mockupSource = loadV8Mockup();
        Map<String, String> options = new HashMap<>();
        options.put(JSContextOptions.TESTV8_MODE_NAME, "true");
        options.put(JSContextOptions.VALIDATE_REGEXP_LITERALS_NAME, "false");
        options.put(JSContextOptions.V8_COMPATIBILITY_MODE_NAME, "true");
        options.put(JSContextOptions.V8_REALM_BUILTIN_NAME, "true");
        options.put(JSContextOptions.V8_LEGACY_CONST_NAME, "true");
        options.put(JSContextOptions.INTL_402_NAME, "true");
        options.put(JSContextOptions.SHELL_NAME, "true"); // readbuffer, quit
        // Reduce string length limit in order to avoid transient out of memory errors.
        options.put(JSContextOptions.STRING_LENGTH_LIMIT_NAME, String.valueOf(STRING_LENGTH_LIMIT));
        options.put(JSContextOptions.ESM_BARE_SPECIFIER_RELATIVE_LOOKUP_NAME, "true");
        config.addCommonOptions(options);
        commonOptions = Collections.unmodifiableMap(options);
        commonOptionsExtLauncher = optionsToExtLauncherOptions(options);
    }

    private Source loadV8Mockup() {
        InputStream resourceStream = TestV8.class.getResourceAsStream("/com/oracle/truffle/js/test/external/resources/v8mockup.js");
        try {
            if (getConfig().isExtLauncher()) {
                File tmpFile = File.createTempFile("v8mockup", ".js");
                tmpFile.deleteOnExit();
                Files.copy(resourceStream, tmpFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return Source.newBuilder(JavaScriptLanguage.ID, tmpFile).internal(true).build();
            } else {
                return Source.newBuilder(JavaScriptLanguage.ID, new InputStreamReader(resourceStream, StandardCharsets.UTF_8), "v8mockup.js").internal(true).build();
            }
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public String[] getPrequelFiles(int ecmaVersion) {
        return PREQUEL_FILES;
    }

    @Override
    public TestRunnable createTestRunnable(TestFile file) {
        return new TestV8Runnable(this, file);
    }

    @Override
    protected File getTestsConfigFile() {
        return Paths.get(getConfig().getSuiteConfigLoc(), TESTS_CONFIG_FILE).toFile();
    }

    @Override
    protected File getUnexpectedlyFailedTestsFile() {
        return new File(FAILED_TESTS_FILE);
    }

    @Override
    public String getHTMLFileName() {
        return "testv8.htm";
    }

    @Override
    public String getReportFileName() {
        return "testv8.txt";
    }

    protected Source getMockupSource() {
        return mockupSource;
    }

    @Override
    public Map<String, String> getCommonOptions() {
        return commonOptions;
    }

    @Override
    public List<String> getCommonExtLauncherOptions() {
        return commonOptionsExtLauncher;
    }

    @Override
    protected boolean isSkipped(TestFile testFile) {
        return testFile.getFilePath().contains("-skip-") || super.isSkipped(testFile);
    }

    public static void main(String[] args) throws Exception {
        SuiteConfig.Builder configBuilder = new SuiteConfig.Builder(SUITE_NAME, SUITE_DESCRIPTION, DEFAULT_LOC, DEFAULT_CONFIG_LOC, TESTS_REL_LOC, HARNESS_REL_LOC);

        // increase default timeouts
        configBuilder.setTimeoutTest(120);
        configBuilder.setTimeoutOverall(20 * 60);

        TimeZone pstZone = TimeZone.getTimeZone("PST"); // =Californian Time (PST)
        TimeZone.setDefault(pstZone);

        System.out.println("Checking your Javascript conformance. Using Google V8 testsuite.\n");

        TestSuite.parseDefaultArgs(args, configBuilder);
        TestV8 suite = new TestV8(configBuilder.build());
        System.exit(suite.runTestSuite(TEST_DIRS));
    }
}
