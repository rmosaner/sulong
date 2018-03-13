/*
 * Copyright (c) 2018, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm;

import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Path;

public class BenchmarkRunner {

    private static final Path SHOOTOUTS_SUITE_DIR = new File("/home/raphael/sulong-dev/sulong/cache/tests/benchmarksgame").toPath();
    private static final String benchmarkSuffix = "_clang_O1.bc";

    public static void main(String[] args) {
        if (args.length != 5)
            throw new IllegalArgumentException("BenchmarkRunner expects arguments: suiteDir benchDir #inProcessIterations processId outputFilePath");

        String suiteDir = args[0];
        String benchDir = args[1];
        int inProcessIterations = Integer.parseInt(args[2]);
        int procId = Integer.parseInt(args[3]);
        File output = new File(args[4]);

        try {
            run(suiteDir, benchDir, inProcessIterations, procId, output);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     *
     * Run a benchmark from a benchmark suite and log the in process iterations in a .csv file with
     * format: processID; benchmarkName; iter1; iter2; ...; iterInProcessIterations
     *
     * @param suiteDir - absolute path to the benchmark suite directory
     * @param benchDir - name of the benchmark to execute (e.g. "fasta")
     * @param inProcessIterations - number of iterations of the benchmark in one VM
     * @param procId - ID of the current process iteration
     * @param output - Output file
     */
    public static void run(String suiteDir, String benchDir, int inProcessIterations, int procId, File output) throws IOException {
        FileWriter writer = new FileWriter(output, true);

        File dir = new File(suiteDir + "/" + benchDir);
        String[] bms = dir.list(new FilenameFilter() {

            @Override
            public boolean accept(File file, String name) {
                return name.endsWith(benchmarkSuffix);
            }

        });

        File bm = new File(bms[0]); // assume only one benchmark file is present

        String[] args1 = {"10"}; // TODO handle parameterization of benchmarks

        writer.write("\n" + procId + "," + benchDir);

        for (int i = 0; i < inProcessIterations; i++) {
            long start = System.currentTimeMillis();
            try {
                Sulong.executeMain(bm, args1);
            } catch (Exception e) {
                e.printStackTrace();
            }
            long end = System.currentTimeMillis();
            double secs = (end - start) / 1000.0;
            writer.write("," + secs);
            writer.flush();
            System.out.println("-------------" + (System.currentTimeMillis() - start));
        }

        writer.close();
    }

}
