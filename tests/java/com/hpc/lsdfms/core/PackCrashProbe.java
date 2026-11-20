package com.hpc.lsdfms.core;

import java.io.IOException;
import java.nio.file.Path;

/** Se detiene en un punto conocido para probar terminación real desde otro proceso. */
public final class PackCrashProbe {
    private PackCrashProbe() { }

    public static void main(String[] args) throws Exception {
        new SequentialEngine(new SequentialEngine.Operations() {
            @Override
            public void replace(Path temporary, Path target) throws IOException {
                if (target.getFileName().toString().endsWith(".dat")) {
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
        }).pack(Path.of(args[0]));
    }
}
