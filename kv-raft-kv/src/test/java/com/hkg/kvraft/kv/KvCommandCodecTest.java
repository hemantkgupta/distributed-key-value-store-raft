package com.hkg.kvraft.kv;

import com.hkg.kvraft.common.Key;
import com.hkg.kvraft.common.Value;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KvCommandCodecTest {

    @Test
    void putRoundTrip() {
        PutCommand original = new PutCommand(
                new Key("hello".getBytes()),
                new Value("world".getBytes()),
                "req-1");
        byte[] encoded = KvCommandCodec.encode(original);
        KvCommand decoded = KvCommandCodec.decode(encoded);

        assertThat(decoded).isInstanceOf(PutCommand.class);
        PutCommand p = (PutCommand) decoded;
        assertThat(p.key()).isEqualTo(original.key());
        assertThat(p.value()).isEqualTo(original.value());
        assertThat(p.clientRequestId()).isEqualTo("req-1");
    }

    @Test
    void deleteRoundTrip() {
        DeleteCommand original = new DeleteCommand(new Key("k".getBytes()), "req-2");
        byte[] encoded = KvCommandCodec.encode(original);
        KvCommand decoded = KvCommandCodec.decode(encoded);

        assertThat(decoded).isInstanceOf(DeleteCommand.class);
        DeleteCommand d = (DeleteCommand) decoded;
        assertThat(d.key()).isEqualTo(original.key());
        assertThat(d.clientRequestId()).isEqualTo("req-2");
    }

    @Test
    void encodingIsDeterministic() {
        PutCommand command = new PutCommand(
                new Key("k".getBytes()), new Value("v".getBytes()), "req");
        byte[] a = KvCommandCodec.encode(command);
        byte[] b = KvCommandCodec.encode(command);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void putWithEmptyValueRoundTrips() {
        PutCommand original = new PutCommand(
                new Key("k".getBytes()), new Value(new byte[0]), "req");
        byte[] encoded = KvCommandCodec.encode(original);
        KvCommand decoded = KvCommandCodec.decode(encoded);
        assertThat(((PutCommand) decoded).value().bytes()).isEmpty();
    }

    @Test
    void unknownTagRaises() {
        byte[] bad = new byte[]{99, 0, 0, 0, 0};
        assertThatThrownBy(() -> KvCommandCodec.decode(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown command tag");
    }
}
