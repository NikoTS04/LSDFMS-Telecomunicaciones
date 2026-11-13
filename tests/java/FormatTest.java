import com.hpc.lsdfms.model.CdrFormat;
import com.hpc.lsdfms.model.IndexEntry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Test ejecutable sin JUnit; falla explícitamente sin depender de -ea. */
public final class FormatTest {
    private FormatTest() { }

    public static void main(String[] args) {
        ByteBuffer buffer = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
        IndexEntry.write(buffer, 51912345678L, 8, 3000000000L);
        IndexEntry.write(buffer, 51999999999L, 1, 0);
        if (IndexEntry.BYTES != 20 || buffer.position() != 40
                || buffer.order() != ByteOrder.BIG_ENDIAN
                || buffer.getLong(IndexEntry.PHONE_OFFSET) != 51912345678L
                || buffer.getInt(IndexEntry.FILE_ID_OFFSET) != 8
                || buffer.getLong(IndexEntry.BYTE_OFFSET) != 3000000000L
                || buffer.getLong(20) != 51999999999L
                || CdrFormat.HEADER_BYTES != 21 || CdrFormat.PHONE_OFFSET != 5
                || CdrFormat.TIMESTAMP_OFFSET != 13) {
            throw new AssertionError("Contrato binario incompatible");
        }
    }
}
