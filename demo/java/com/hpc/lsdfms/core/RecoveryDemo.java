package com.hpc.lsdfms.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Solo demostración: fallo real controlado después de reemplazar un archivo. */
public final class RecoveryDemo {
    private RecoveryDemo() { }

    public static void main(String[] args) throws Exception {
        AtomicBoolean injected = new AtomicBoolean();
        try {
            new ParallelEngine(2, new SequentialEngine.Operations() {
                @Override
                public void replace(Path temporary, Path target) throws IOException {
                    SequentialEngine.Operations.super.replace(temporary, target);
                    if (target.getFileName().toString().endsWith(".dat")
                            && injected.compareAndSet(false, true)) {
                        throw new IOException("Fallo de exposición tras un reemplazo");
                    }
                }
            }).pack(Path.of(args[0]));
            throw new AssertionError("No se produjo el fallo esperado");
        } catch (IOException expected) {
            if (!injected.get()) {
                throw expected;
            }
            System.out.println("FALLO_INYECTADO\t" + expected.getMessage());
        }
    }
}
