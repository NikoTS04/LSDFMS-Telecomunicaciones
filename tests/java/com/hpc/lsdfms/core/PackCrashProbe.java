package com.hpc.lsdfms.core;

import java.io.IOException;
import java.nio.file.Path;

/** Se detiene en un punto conocido para probar terminación real desde otro proceso. */
public final class PackCrashProbe {
    private PackCrashProbe() { }

    public static void main(String[] args) throws Exception {
        SequentialEngine.Operations operations = new SequentialEngine.Operations() {
            @Override
            public void replace(Path temporary, Path target) throws IOException {
                boolean data = target.getFileName().toString().endsWith(".dat");
                if (("index".equals(args[1]) && !data) || (!"index".equals(args[1]) && data)) {
                    if ("after".equals(args[1])) {
                        SequentialEngine.Operations.super.replace(temporary, target);
                    }
                    System.out.println("READY");
                    System.out.flush();
                    // Python mantiene stdin abierto hasta terminar abruptamente esta JVM.
                    System.in.read();
                    throw new IOException("Probe liberado sin terminación");
                }
                SequentialEngine.Operations.super.replace(temporary, target);
            }
        };
        if (args.length > 2 && "parallel".equals(args[2])) {
            new ParallelEngine(2, operations).pack(Path.of(args[0]));
        } else {
            new SequentialEngine(operations).pack(Path.of(args[0]));
        }
    }
}
