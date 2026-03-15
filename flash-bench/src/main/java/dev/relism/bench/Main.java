package dev.relism.bench;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equalsIgnoreCase("external")) {
            ExternalBenchmark.main(args);
            return;
        }

        System.out.println("Starting JMH Benchmarks... (Pass 'external' as arg to start standalone server)");
        Options opts = new OptionsBuilder()
                .include(FlashBenchmark.class.getSimpleName())
                .resultFormat(ResultFormatType.TEXT)
                .build();

        new Runner(opts).run();
    }
}
