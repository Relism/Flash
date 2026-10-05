package dev.relism.flash.ext.protobuf;

import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import dev.relism.flash.Bound;
import dev.relism.flash.Services;
import dev.relism.flash.ext.serde.Codec;
import dev.relism.flash.ext.validation.Validation;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;

/** Binary Protocol Buffers through Google's generated {@link Message} classes. */
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
    public Codec.Reader reader(Type type) {
        Parser<? extends Message> parser = parser(type);
        ProtobufValidation checker = validation;
        return checker == null
                ? request -> parse(request, parser)
                : request -> checked(request, parser, checker);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T read(Request request, Type type) throws Exception {
        Message value = parse(request, parser(type));
        if (validation != null) validation.verify(value);
        return (T) value;
    }

    private Message parse(Request request, Parser<? extends Message> parser) throws IOException {
        Message value;
        try {
            value = parser.parseFrom(request.body().stream(), extensions);
        } catch (InvalidProtocolBufferException malformed) {
            if (malformed.getCause() instanceof IOException io) throw io;
            throw HttpException.badRequest("Invalid protobuf body: " + malformed.getMessage());
        }
        return value;
    }

    private Message checked(Request request, Parser<? extends Message> parser, ProtobufValidation checker) throws IOException {
        Message value = parse(request, parser);
        checker.verify(value);
        return value;
    }

    @Override
    public byte[] write(Response response, Object value) {
        if (!(value instanceof Message message))
            throw new IllegalArgumentException(value.getClass().getTypeName() + " is not a protobuf Message");
        response.type(ContentType.PROTOBUF);
        return message.toByteArray();
    }

    @Override
    public void prepare(Type type) {
        parser(type);
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
