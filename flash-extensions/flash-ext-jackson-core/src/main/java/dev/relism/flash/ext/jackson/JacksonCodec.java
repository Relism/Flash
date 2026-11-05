package dev.relism.flash.ext.jackson;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.ext.serde.Codec;
import dev.relism.flash.ext.serde.Decoder;
import dev.relism.flash.ext.serde.Encoder;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.Response;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One Jackson mapper, in the shape a handler needs it.
 *
 * <p>Every Jackson data format is the same databind model behind a different factory, so this is
 * the whole of what a format module has to say: which mapper, and which content type it writes.
 * What the annotations mean and how a type is described are the same for all of them.
 *
 * <p>A request body is parsed as it arrives, never held whole; a message is parsed where the
 * protocol holds it. The readers and writers are resolved once per type, when a handler asks at
 * boot, and the underlying {@link ObjectMapper} is thread-safe once configured.
 */
public abstract class JacksonCodec implements Codec {

    private final ObjectMapper mapper;
    private final ContentType contentType;
    /** Java member to wire name, per class, worked out once at boot. */
    private final Map<Class<?>, Map<String, String>> written = new ConcurrentHashMap<>();

    protected JacksonCodec(ObjectMapper mapper, ContentType contentType) {
        this.mapper = mapper;
        this.contentType = contentType;
    }

    @Override
    public Decoder decoder(Type type) {
        ObjectReader reader = mapper.readerFor(mapper.constructType(type));
        return new Decoder() {
            @Override
            public Object decode(byte[] bytes, int offset, int length) throws Exception {
                try {
                    return reader.readValue(bytes, offset, length);
                } catch (JsonProcessingException malformed) {
                    throw HttpException.badRequest("Invalid message: " + malformed.getOriginalMessage());
                }
            }

            @Override
            public Object decode(dev.relism.flash.http.Request request) throws Exception {
                try {
                    return reader.readValue(request.body().stream());
                } catch (JsonProcessingException malformed) {
                    throw HttpException.badRequest("Invalid request body: " + malformed.getOriginalMessage());
                }
            }
        };
    }

    @Override
    public Encoder encoder(Type type) {
        ObjectWriter writer = mapper.writerFor(mapper.constructType(type));
        return new Encoder() {
            @Override
            public void encode(Object value, ByteWriter out) throws Exception {
                writer.writeValue(out, value);
            }

            @Override
            public ContentType type() {
                return contentType;
            }
        };
    }

    @Override
    public byte[] write(Response response, Object value) throws Exception {
        response.type(contentType);
        return mapper.writeValueAsBytes(value);
    }

    @Override
    public ContentType contentType() {
        return contentType;
    }

    /**
     * The name the mapper reads or writes {@code field} under ({@code @JsonProperty}, then the naming
     * strategy), or null when it does neither: ignored, left out of {@code @JsonIncludeProperties}, or
     * not visible to the mapper. The name it writes wins over the one it reads.
     */
    @Override
    public String wireName(Field field) {
        return written.computeIfAbsent(field.getDeclaringClass(), this::wireNames).get(field.getName());
    }

    private Map<String, String> wireNames(Class<?> type) {
        JavaType javaType = mapper.constructType(type);
        Map<String, String> names = new HashMap<>();
        DeserializationConfig read = mapper.getDeserializationConfig();
        put(names, read, read.introspect(javaType), false);
        SerializationConfig write = mapper.getSerializationConfig();
        put(names, write, write.introspect(javaType), true);
        return names;
    }

    /**
     * Every property {@code bean} carries one way, without what the mapper ignores: a record keeps an
     * ignored component as a creator property, listed among the description's ignored names.
     */
    private static void put(Map<String, String> names, MapperConfig<?> config, BeanDescription bean, boolean writing) {
        JsonIgnoreProperties.Value ignorals = config.getDefaultPropertyIgnorals(bean.getBeanClass(), bean.getClassInfo());
        Set<String> ignored = ignorals == null ? Set.of()
                : writing ? ignorals.findIgnoredForSerialization() : ignorals.findIgnoredForDeserialization();
        JsonIncludeProperties.Value inclusions = config.getDefaultPropertyInclusions(bean.getBeanClass(), bean.getClassInfo());
        Set<String> included = inclusions == null ? null : inclusions.getIncluded();
        for (BeanPropertyDefinition property : bean.findProperties()) {
            String name = property.getName();
            if (!ignored.contains(name) && !bean.getIgnoredPropertyNames().contains(name)
                    && (included == null || included.contains(name))) names.put(property.getInternalName(), name);
        }
    }

    /** The mapper itself, for what a handler does with it beyond reading and writing a body. */
    public ObjectMapper mapper() {
        return mapper;
    }
}
