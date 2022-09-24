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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

import com.oracle.truffle.js.lang.JavaScriptLanguage;
import com.oracle.truffle.js.runtime.JSConfig;
import com.oracle.truffle.js.runtime.JSContextOptions;
import com.oracle.truffle.js.test.external.suite.TestCallable;
import com.oracle.truffle.js.test.external.suite.TestExtProcessCallable;
import com.oracle.truffle.js.test.external.suite.TestFile;
import com.oracle.truffle.js.test.external.suite.TestRunnable;
import com.oracle.truffle.js.test.external.suite.TestSuite;

public class TestV8Runnable extends TestRunnable {

    private static final int LONG_RUNNING_TEST_SECONDS = 55;

    private static final String HARMONY_ERROR_CAUSE = "--harmony-error-cause";
    private static final String HARMONY_IMPORT_ASSERTIONS = "--harmony-import-assertions";
    private static final String HARMONY_SHAREDARRAYBUFFER = "--harmony-sharedarraybuffer";
    private static final String HARMONY_PUBLIC_FIELDS = "--harmony-public-fields";
    private static final String HARMONY_PRIVATE_FIELDS = "--harmony-private-fields";
    private static final String HARMONY_PRIVATE_METHODS = "--harmony-private-methods";
    private static final String HARMONY_TEMPORAL = "--harmony-temporal";
    private static final String NO_ASYNC_STACK_TRACES = "--noasync-stack-traces";
    private static final String NO_EXPOSE_WASM = "--noexpose-wasm";
    private static final String NO_HARMONY_REGEXP_MATCH_INDICES = "--no-harmony-regexp-match-indices";

    private static final Set<String> UNSUPPORTED_FLAGS = new HashSet<>(Arrays.asList(new String[]{
                    "--experimental-d8-web-snapshot-api",
                    "--experimental-web-snapshots",
                    "--experimental-wasm-bulk-memory",
                    "--experimental-wasm-compilation-hints",
                    "--experimental-wasm-eh",
                    "--experimental-wasm-gc",
                    "--experimental-wasm-memory64",
                    "--experimental-wasm-reftypes",
                    "--experimental-wasm-return-call",
                    "--experimental-wasm-simd",
                    "--experimental-wasm-threads",
                    "--experimental-wasm-typed-funcref",
                    "--experimental-wasm-type-reflection",
                    "--harmony-shadow-realm",
                    "--wasm-staging"
    }));
    private static final Set<String> ES2023_FLAGS = new HashSet<>(Arrays.asList(new String[]{
                    "--harmony-array-find-last",
                    "--harmony-array-grouping",
                    "--harmony-atomics-waitasync",
                    "--harmony_intl_enumeration",
                    "--harmony_intl_locale_info",
                    "--harmony_intl_more_timezone",
                    "--harmony-intl-number-format-v3",
    }));

    private static final String FLAGS_PREFIX = "// Flags: ";
    private static final String FILES_PREFIX = "// Files: ";
    private static final Pattern FLAGS_FIND_PATTERN = Pattern.compile("// Flags: (.*)");
    private static final Pattern FILES_FIND_PATTERN = Pattern.compile("// Files: (.*)");
    private static final Pattern SPLIT_PATTERN = Pattern.compile("\\s+");
    private static final String MODULE_FILE_EXT = ".mjs";

    private static final Source GC_NOOP_SOURCE = Source.newBuilder("js", "gc = function() { };", "").buildLiteral();

    public TestV8Runnable(TestSuite suite, TestFile testFile) {
        super(suite, testFile);
    }

    @Override
    public void run() {
        suite.printProgress(testFile);
        final File file = suite.resolveTestFilePath(testFile);
        List<String> code = TestSuite.readFileContentList(file);
        boolean negative = isNegativeTest(code);
        boolean shouldThrow = shouldThrow(code);
        boolean module = testFile.getFilePath().endsWith(MODULE_FILE_EXT);
        Set<String> flags = getFlags(code);
        List<String> setupFiles = getFiles(code, getConfig().getSuiteLoc());

        Map<String, String> extraOptions = new HashMap<>(2);
        if (flags.contains(HARMONY_SHAREDARRAYBUFFER)) {
            extraOptions.put(JSContextOptions.SHARED_ARRAY_BUFFER_NAME, "true");
        }
        if (suite.getConfig().isPolyglot()) {
            extraOptions.put(JSContextOptions.WEBASSEMBLY_NAME, Boolean.toString(!flags.contains(NO_EXPOSE_WASM)));
        }

        suite.logVerbose("Starting: " + getName());

        if (getConfig().isPrintScript()) {
            printScript(TestSuite.toPrintableCode(code));
        }

        boolean supported = true;
        int minESVersion = suite.getConfig().getMinESVersion();
        int flagVersion = minESVersion;
        for (String flag : flags) {
            if (ES2023_FLAGS.contains(flag)) {
                assert !UNSUPPORTED_FLAGS.contains(flag) : flag;
                flagVersion = JSConfig.ECMAScript2023;
            } else if (UNSUPPORTED_FLAGS.contains(flag)) {
                supported = false;
            }
        }

        TestFile.EcmaVersion ecmaVersion = testFile.getEcmaVersion();
        if (ecmaVersion == null) {
            ecmaVersion = TestFile.EcmaVersion.forVersions(flagVersion);
        } else {
            ecmaVersion = ecmaVersion.filterByMinVersion(minESVersion);
        }

        if (flags.contains(HARMONY_PUBLIC_FIELDS) || flags.contains(HARMONY_PRIVATE_FIELDS) || flags.contains(HARMONY_PRIVATE_METHODS)) {
            extraOptions.put(JSContextOptions.CLASS_FIELDS_NAME, "true");
        }
        if (flags.contains(NO_ASYNC_STACK_TRACES)) {
            extraOptions.put(JSContextOptions.ASYNC_STACK_TRACES_NAME, "false");
        }
        if (flags.contains(NO_HARMONY_REGEXP_MATCH_INDICES)) {
            extraOptions.put(JSContextOptions.REGEXP_MATCH_INDICES_NAME, "false");
        }
        if (flags.contains(HARMONY_ERROR_CAUSE)) {
            extraOptions.put(JSContextOptions.ERROR_CAUSE_NAME, "true");
        }
        if (flags.contains(HARMONY_IMPORT_ASSERTIONS)) {
            extraOptions.put(JSContextOptions.IMPORT_ASSERTIONS_NAME, "true");
            extraOptions.put(JSContextOptions.JSON_MODULES_NAME, "true");
        }
        if (flags.contains(HARMONY_TEMPORAL)) {
            extraOptions.put(JSContextOptions.TEMPORAL_NAME, "true");
        }

        if (supported) {
            testFile.setResult(runTest(ecmaVersion, version -> runInternal(version, file, negative, shouldThrow, module, extraOptions, setupFiles)));
        } else {
            testFile.setStatus(TestFile.Status.SKIP); // attn: does not force-skip statusOverrides
        }
    }

    private TestFile.Result runInternal(int ecmaVersion, File file, boolean negative, boolean shouldThrow, boolean module, Map<String, String> extraOptions, List<String> setupFiles) {
        suite.logVerbose(getName() + ecmaVersionToString(ecmaVersion));
        TestFile.Result testResult;

        long startDate = System.currentTimeMillis();
        reportStart();
        if (suite.getConfig().isExtLauncher()) {
            testResult = runExtLauncher(ecmaVersion, file, negative, shouldThrow, module, extraOptions, setupFiles);
        } else {
            testResult = runInJVM(ecmaVersion, file, negative, shouldThrow, module, extraOptions, setupFiles);
        }
        if (negative) {
            if (!testResult.isFailure()) {
                logFailure(ecmaVersion, "negative test, was expected to fail but didn't");
            } else {
                testResult = TestFile.Result.PASSED;
            }
        }
        // test with --throws must fail
        if (shouldThrow) {
            if (!testResult.isFailure()) {
                logFailure(ecmaVersion, "--throws test, was expected to fail but didn't");
            } else {
                testResult = TestFile.Result.PASSED;
            }
        }
        reportEnd(startDate);
        return testResult;
    }

    private TestFile.Result runInJVM(int ecmaVersion, File file, boolean negative, boolean shouldThrow, boolean module, Map<String, String> extraOptions, List<String> setupFiles) {
        boolean replaceGCBuiltin = shouldReplaceGCBuiltin(file);
        Source[] prequelSources = loadHarnessSources(ecmaVersion);
        Source[] sources = Arrays.copyOf(prequelSources, prequelSources.length + 2 + (replaceGCBuiltin ? 1 : 0) + setupFiles.size());
        int sourceIdx = prequelSources.length;
        try {
            for (String setupFile : setupFiles) {
                sources[sourceIdx++] = Source.newBuilder(JavaScriptLanguage.ID, new File(setupFile)).build();
            }
        } catch (IOException ioex) {
            return TestFile.Result.failed(ioex);
        }
        sources[sourceIdx++] = ((TestV8) suite).getMockupSource();
        if (replaceGCBuiltin) {
            sources[sourceIdx++] = GC_NOOP_SOURCE;
        }
        sources[sourceIdx++] = Source.newBuilder(JavaScriptLanguage.ID, createTestFileNamePrefix(file), "").buildLiteral();
        assert sourceIdx == sources.length;

        TestCallable tc = new TestCallable(suite, sources, toSource(file, module), file, ecmaVersion, extraOptions);
        if (!suite.getConfig().isPrintFullOutput()) {
            tc.setOutput(DUMMY_OUTPUT_STREAM);
        }
        try {
            tc.call();
            return TestFile.Result.PASSED;
        } catch (Throwable e) {
            if (e instanceof PolyglotException && ((PolyglotException) e).isExit() && ((PolyglotException) e).getExitStatus() == 0) {
                return TestFile.Result.PASSED;
            } else {
                if (!negative && !shouldThrow) {
                    logFailure(ecmaVersion, e);
                }
                return TestFile.Result.failed(e);
            }
        }
    }

    // delete the gc() builtin in cases where not strictly needed
    // this avoids excessive gc()ing in some tests
    private static boolean shouldReplaceGCBuiltin(File file) {
        String fp = file.getPath();
        return fp.endsWith("es6/typedarray-of.js") || fp.endsWith("regress/regress-crbug-854299.js");
    }

    private TestFile.Result runExtLauncher(int ecmaVersion, File file, boolean negative, boolean shouldThrow, boolean module, Map<String, String> extraOptions, List<String> setupFiles) {
        Source[] prequelSources = loadHarnessSources(ecmaVersion);
        List<String> args = new ArrayList<>(prequelSources.length + (module ? 5 : 4));
        for (Source prequelSrc : prequelSources) {
            args.add(prequelSrc.getPath());
        }
        for (String setupFile : setupFiles) {
            args.add(setupFile);
        }
        args.add("--eval");
        args.add(createTestFileNamePrefix(file));
        args.add(((TestV8) suite).getMockupSource().getPath());
        if (module) {
            args.add("--module");
        }
        args.add(file.getPath());
        TestExtProcessCallable tc = new TestExtProcessCallable(suite, ecmaVersion, args, extraOptions);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        tc.setOutput(byteArrayOutputStream);
        tc.setError(byteArrayOutputStream);
        try {
            switch (tc.call()) {
                case SUCCESS:
                    return TestFile.Result.PASSED;
                case FAILURE:
                    if (negative || shouldThrow) {
                        return TestFile.Result.failed("TestV8 expected failure");
                    } else {
                        String error = extLauncherFindError(byteArrayOutputStream.toString());
                        logFailure(ecmaVersion, error);
                        return TestFile.Result.failed(error);
                    }
                case TIMEOUT:
                    logTimeout(ecmaVersion);
                    return TestFile.Result.timeout("TIMEOUT");
                default:
                    throw new IllegalStateException("should not reach here");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String createTestFileNamePrefix(File file) {
        return "TEST_FILE_NAME = \"" + file.getPath().replaceAll("\\\\", "\\\\\\\\") + "\"";
    }

    private void reportStart() {
        synchronized (suite) {
            suite.getActiveTests().add(this);
        }
    }

    private void reportEnd(long startDate) {
        long executionTime = System.currentTimeMillis() - startDate;
        synchronized (suite) {
            if (executionTime > LONG_RUNNING_TEST_SECONDS * 1000) {
                System.out.println("Long running test finished: " + getTestFile().getFilePath() + " " + executionTime);
            }

            suite.getActiveTests().remove(this);
            StringJoiner activeTestNames = new StringJoiner(", ");
            if (suite.getConfig().isVerbose()) {
                for (TestRunnable test : suite.getActiveTests()) {
                    activeTestNames.add(test.getName());
                }
                suite.logVerbose("Finished test: " + getName() + " after " + executionTime + " ms, active: " + activeTestNames);
            }
        }
    }

    private void printScript(String scriptCode) {
        synchronized (suite) {
            System.out.println("================================================================");
            System.out.println("====== Testcase: " + getName());
            System.out.println("================================================================");
            System.out.println();
            System.out.println(scriptCode);
        }
    }

    private static boolean isNegativeTest(List<String> scriptCode) {
        for (String line : scriptCode) {
            if (line.contains("* @negative")) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldThrow(List<String> scriptCode) {
        for (String line : scriptCode) {
            if (line.contains("--throws")) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> getFlags(List<String> scriptCode) {
        return getStrings(scriptCode, FLAGS_PREFIX, FLAGS_FIND_PATTERN, SPLIT_PATTERN).collect(Collectors.toSet());
    }

    private static List<String> getFiles(List<String> scriptCode, String suiteLocation) {
        return getStrings(scriptCode, FILES_PREFIX, FILES_FIND_PATTERN, SPLIT_PATTERN).map(file -> Paths.get(suiteLocation, file).toString()).collect(Collectors.toList());
    }

    private static final OutputStream DUMMY_OUTPUT_STREAM = new OutputStream() {

        @Override
        public void write(int b) throws IOException {
        }
    };

}
