package dev.relism.flash.ext.protobuf;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import dev.relism.flash.Bound;
import dev.relism.flash.Services;
import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.ext.serde.Codec;
import dev.relism.flash.ext.serde.Decoder;
import dev.relism.flash.ext.serde.Encoder;
import dev.relism.flash.ext.validation.Validation;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Response;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;

/**
 * Binary Protocol Buffers through Google's generated {@link Message} classes. A body is read whole
 * and parsed from the array, which beats parsing a stream: that allocates a buffer per call. A
 * message is written straight into the protocol's buffer, its length known up front.
 */
public final class Protobuf implements Codec, Bound {

    private final ExtensionRegistryLite extensions;
    private ProtobufValidation validation;

    private final ClassValue<Parser<? extends Message>> parsers = new ClassValue<>() {
        @Override
        protected Parser<? extends Message> computeValue(Class<?> type) {
            if (!Message.class.isAssignableFrom(type))
                throw new IllegalArgumentException(type.getTypeName() + " is not a protobuf Message");
            return defaultInstance(type).getParserForType();
        }
    };

    /** The default instance a generated message class declares: its documented static {@code getDefaultInstance()}. */
    static Message defaultInstance(Class<?> type) {
        try {
            return (Message) type.getMethod("getDefaultInstance").invoke(null);
        } catch (ReflectiveOperationException | ClassCastException notGenerated) {
            throw new IllegalArgumentException(type.getTypeName() + " is not a generated protobuf message class", notGenerated);
        }
    }

    public Protobuf() {
        this(ExtensionRegistryLite.getEmptyRegistry());
    }

    /** Uses {@code extensions} for applications carrying protobuf extension fields. */
    public Protobuf(ExtensionRegistryLite extensions) {
        this.extensions = extensions;
    }

    @Override
    public Decoder decoder(Type type) {
        Parser<? extends Message> parser = parser(type);
        return (bytes, offset, length) -> {
            Message value;
            try {
                value = parser.parseFrom(bytes, offset, length, extensions);
            } catch (InvalidProtocolBufferException malformed) {
                throw HttpException.badRequest("Invalid protobuf message: " + malformed.getMessage());
            }
            if (validation != null) validation.verify(value);
            return value;
        };
    }

    @Override
    public Encoder encoder(Type type) {
        parser(type);
        return ENCODER;
    }

    /** Knows the length before it writes, so it writes straight into the buffer: no intermediate array. */
    private static final Encoder ENCODER = new Encoder() {
        @Override
        public void encode(Object value, ByteWriter out) throws IOException {
            Message message = (Message) value;
            int size = message.getSerializedSize();
            int at = out.reserve(size); // reserve may grow the buffer: read array() after it
            CodedOutputStream coded = CodedOutputStream.newInstance(out.array(), at, size);
            message.writeTo(coded);
            coded.checkNoSpaceLeft();
        }

        @Override
        public ContentType type() {
            return ContentType.PROTOBUF;
        }
    };

    @Override
    public byte[] write(Response response, Object value) {
        if (!(value instanceof Message message))
            throw new IllegalArgumentException(value.getClass().getTypeName() + " is not a protobuf Message");
        response.type(ContentType.PROTOBUF);
        return message.toByteArray();
    }

    @Override
    public ContentType contentType() {
        return ContentType.PROTOBUF;
    }

    @Override
    public void bind(Services services) {
        validation = Validation.family(services, ProtobufValidation.class).orElse(null);
        if (validation == null) LoggerFactory.getLogger(Protobuf.class)
                .info("No ProtobufValidation installed: {} bodies are parsed and not checked", ContentType.PROTOBUF);
    }

    private Parser<? extends Message> parser(Type type) {
        if (!(type instanceof Class<?> target))
            throw new IllegalArgumentException(type.getTypeName() + " is not a concrete protobuf Message class");
        return parsers.get(target);
    }
}
