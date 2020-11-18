package org.jetlinks.core;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.Recycler;
import io.netty.util.ReferenceCountUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.bean.FastBeanCopier;
import org.jetlinks.core.codec.Decoder;
import org.jetlinks.core.codec.Encoder;
import org.jetlinks.core.metadata.Jsonable;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.function.Supplier;

@Getter
@Setter
@Slf4j
public class NativePayload<T> extends AbstractReferenceCounted implements Payload {

    private T nativeObject;

    private Encoder<T> encoder;

    private volatile Payload ref;

    private ByteBuf buf;

    private static Recycler<NativePayload> POOL = new Recycler<NativePayload>() {
        protected NativePayload newObject(io.netty.util.Recycler.Handle<NativePayload> handle) {
            return new NativePayload(handle);
        }
    };

    private final io.netty.util.Recycler.Handle<NativePayload> handle;

    private NativePayload(io.netty.util.Recycler.Handle<NativePayload> handle) {
        this.handle = handle;
    }

    @Override
    public Payload slice() {
        return ref != null ? ref.slice() : this;
    }

    public static <T> NativePayload<T> of(T nativeObject, Encoder<T> encoder) {
        NativePayload<T> payload = POOL.get();
        payload.setRefCnt(1);
        payload.nativeObject = nativeObject;
        payload.encoder = encoder;
        return payload;
    }

    public static <T> NativePayload<T> of(T nativeObject, Supplier<Payload> bodySupplier) {
        return of(nativeObject, v -> bodySupplier.get());
    }

    @Override
    @SuppressWarnings("all")
    @SneakyThrows
    public <T> T decode(Decoder<T> decoder, boolean release) {
        try {
            if (decoder.isDecodeFrom(nativeObject)) {
                return (T) nativeObject;
            }
        } finally {
            if (release) {
                release();
            }
        }
        Class<T> type = decoder.forType();
        if (type == JSONObject.class || type == Map.class) {
            return (T) bodyToJson(release);
        }
        if (Map.class.isAssignableFrom(decoder.forType())) {
            return bodyToJson(release).toJavaObject(decoder.forType());
        }
        return Payload.super.decode(decoder, release);
    }

    @Override
    public Object decode() {
        return decode(true);
    }

    @Override
    public Object decode(boolean release) {
        try {
            return nativeObject;
        } finally {
            if (release) {
                release();
            }
        }
    }

    @Nonnull
    @Override
    public ByteBuf getBody() {
        if (ref == null) {
            synchronized (this) {
                if (ref != null) {
                    return ref.getBody();
                }
                ByteBuf buf = Unpooled.unreleasableBuffer(this.buf = encoder.encode(nativeObject).getBody());
                ref = Payload.of(buf);
            }
        }
        return ref.getBody();
    }

    @Override
    public int refCnt() {
        return super.refCnt();
    }

    @Override
    protected void deallocate() {
        this.ref = null;
        this.nativeObject = null;
        this.encoder = null;
        if (this.buf != null) {
            ReferenceCountUtil.safeRelease(this.buf);
            this.buf = null;
        }
        handle.recycle(this);
    }

    @Override
    public NativePayload<T> touch(Object o) {
        return this;
    }

    @Override
    public NativePayload<T> touch() {
        super.touch();
        return this;
    }

    @Override
    public NativePayload<T> retain() {
        return retain(1);
    }

    @Override
    public NativePayload<T> retain(int inc) {
        super.retain(inc);
        return this;
    }

    @Override
    public boolean release() {
        return this.release(1);
    }

    @Override
    public boolean release(int decrement) {
        return super.release(decrement);
    }

    @Override
    public String bodyToString(boolean release) {
        try {
            return nativeObject.toString();
        } finally {
            if (release) {
                release();
            }
        }
    }

    @Override
    @SuppressWarnings("all")
    public JSONArray bodyToJsonArray(boolean release) {
        try {
            if (nativeObject == null) {
                return new JSONArray();
            }
            if (nativeObject instanceof JSONArray) {
                return ((JSONArray) nativeObject);
            }
            List<Object> collection;
            if (nativeObject instanceof List) {
                collection = ((List<Object>) nativeObject);
            } else if (nativeObject instanceof Collection) {
                collection = new ArrayList<>(((Collection<Object>) nativeObject));
            } else if (nativeObject instanceof Object[]) {
                collection = Arrays.asList(((Object[]) nativeObject));
            } else {
                throw new UnsupportedOperationException("body is not arry");
            }
            return new JSONArray(collection);
        } finally {
            if (release) {
                release();
            }
        }
    }

    @Override
    public JSONObject bodyToJson(boolean release) {
        try {
            if (nativeObject == null) {
                return new JSONObject();
            }
            if (nativeObject instanceof Jsonable) {
                return ((Jsonable) nativeObject).toJson();
            }
            return FastBeanCopier.copy(nativeObject, JSONObject::new);
        } finally {
            if (release) {
                release();
            }
        }
    }

    @Override
    public String toString() {
        return nativeObject == null ? "null" : nativeObject.toString();
    }

    @Override
    protected void finalize() throws Throwable {
        if (refCnt() != 0) {
            log.warn("payload was not release properly, release() was not called before it's garbage-collected. refCnt={}", refCnt());
        }
        super.finalize();
    }
}
