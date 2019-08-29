/**
 * Copyright (c) 2013-2019 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.codec;

import java.io.IOException;

import org.jboss.marshalling.ByteInput;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.MarshallerFactory;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.SimpleClassResolver;
import org.jboss.marshalling.Unmarshaller;
import org.redisson.client.codec.BaseCodec;
import org.redisson.client.handler.State;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.concurrent.FastThreadLocal;

/**
 * JBoss Marshalling codec.
 * 
 * Uses River protocol by default.
 * 
 * https://github.com/jboss-remoting/jboss-marshalling
 * 
 * @author Nikita Koksharov
 *
 */
public class MarshallingCodec extends BaseCodec {

    private final FastThreadLocal<Unmarshaller> decoder = new FastThreadLocal<Unmarshaller>() {
        @Override
        protected Unmarshaller initialValue() throws IOException {
            return factory.createUnmarshaller(configuration);
        };
    };
    
    private final FastThreadLocal<Marshaller> encoder = new FastThreadLocal<Marshaller>() {
        @Override
        protected Marshaller initialValue() throws IOException {
            return factory.createMarshaller(configuration);
        };
    };
    
    public static class ByteInputWrapper implements ByteInput {

        private final ByteBuf byteBuf;
        
        public ByteInputWrapper(ByteBuf byteBuf) {
            super();
            this.byteBuf = byteBuf;
        }

        @Override
        public void close() throws IOException {
        }

        @Override
        public int read() throws IOException {
            return byteBuf.readByte() & 0xff;
        }

        @Override
        public int read(byte[] b) throws IOException {
            return read(b, 0, b.length);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int length = available();
            int readLength = Math.min(len, length);
            byteBuf.readBytes(b, off, readLength);
            return readLength;
        }

        @Override
        public int available() throws IOException {
            return byteBuf.readableBytes();
        }

        @Override
        public long skip(long n) throws IOException {
            int length = available();
            long skipLength = Math.min(length, n);
            byteBuf.readerIndex((int) (byteBuf.readerIndex() + skipLength));
            return skipLength;
        }
        
    }
    
    public static class ByteOutputWrapper implements ByteOutput {

        private final ByteBuf byteBuf;
        
        public ByteOutputWrapper(ByteBuf byteBuf) {
            this.byteBuf = byteBuf;
        }

        @Override
        public void close() throws IOException {
        }

        @Override
        public void flush() throws IOException {
        }

        @Override
        public void write(int b) throws IOException {
            byteBuf.writeByte(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            byteBuf.writeBytes(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            byteBuf.writeBytes(b, off, len);
        }
        
    }
    
    public enum Protocol {
        
        SERIAL,
        
        RIVER
        
    }
    
    private final MarshallerFactory factory;
    private final MarshallingConfiguration configuration;
    
    public MarshallingCodec() {
        this(Protocol.RIVER, new MarshallingConfiguration());
    }
    
    public MarshallingCodec(ClassLoader classLoader) {
        this(Protocol.RIVER, new MarshallingConfiguration());
        configuration.setClassResolver(new SimpleClassResolver(classLoader));
    }
    
    public MarshallingCodec(ClassLoader classLoader, MarshallingCodec codec) {
        this.factory = codec.factory;
        this.configuration = codec.configuration;
        this.configuration.setClassResolver(new SimpleClassResolver(classLoader));
    }
    
    public MarshallingCodec(Protocol protocol, MarshallingConfiguration configuration) {
        this.factory = Marshalling.getProvidedMarshallerFactory(protocol.toString().toLowerCase());
        this.configuration = configuration;
    }
    
    @Override
    public Decoder<Object> getValueDecoder() {
        return new Decoder<Object>() {
            
            @Override
            public Object decode(ByteBuf buf, State state) throws IOException {
                Unmarshaller unmarshaller = decoder.get();
                try {
                    unmarshaller.start(new ByteInputWrapper(buf));
                    return unmarshaller.readObject();
                } catch (ClassNotFoundException e) {
                    throw new IOException(e);
                } finally {
                    unmarshaller.finish();
                    unmarshaller.close();
                }
            }
        };
    }

    @Override
    public Encoder getValueEncoder() {
        return new Encoder() {
            
            @Override
            public ByteBuf encode(Object in) throws IOException {
                ByteBuf out = ByteBufAllocator.DEFAULT.buffer();

                Marshaller marshaller = encoder.get();
                marshaller.start(new ByteOutputWrapper(out));
                marshaller.writeObject(in);
                marshaller.finish();
                marshaller.close();
                return out;
            }
        };
    }

}