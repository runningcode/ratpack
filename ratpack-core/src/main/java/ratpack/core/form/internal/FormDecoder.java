/*
 * Copyright 2013 the original author or authors.
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

package ratpack.core.form.internal;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import ratpack.core.form.Form;
import ratpack.core.form.UploadedFile;
import ratpack.core.handling.Context;
import ratpack.core.http.MediaType;
import ratpack.core.http.Request;
import ratpack.core.http.TypedData;
import ratpack.core.http.internal.ByteBufBackedTypedData;
import ratpack.core.http.internal.DefaultMediaType;
import ratpack.func.MultiValueMap;
import ratpack.func.internal.ImmutableDelegatingMultiValueMap;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ratpack.func.Exceptions.uncheck;

public abstract class FormDecoder {

  public static Form parseForm(Context context, TypedData body, MultiValueMap<String, String> base) throws RuntimeException {
    Request request = context.getRequest();
    HttpMethod method = io.netty.handler.codec.http.HttpMethod.valueOf(request.getMethod().getName());
    HttpRequest nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, request.getUri());
    nettyRequest.headers().add(HttpHeaderNames.CONTENT_TYPE, body.getContentType().toString());

    Map<String, List<String>> attributes = new LinkedHashMap<>(base.getAll());
    Map<String, List<UploadedFile>> files = new LinkedHashMap<>();

    HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(nettyRequest);
    try {
      HttpContent content = new DefaultHttpContent(body.getBuffer());
      decoder.offer(content);
      decoder.offer(LastHttpContent.EMPTY_LAST_CONTENT);
      InterfaceHttpData data = decoder.next();
      while (data != null) {
        if (data.getHttpDataType().equals(InterfaceHttpData.HttpDataType.Attribute)) {
          List<String> values = attributes.computeIfAbsent(data.getName(), k -> new ArrayList<>(1));
          try {
            values.add(((Attribute) data).getValue());
          } catch (IOException e) {
            throw uncheck(e);
          }
        } else if (data.getHttpDataType().equals(InterfaceHttpData.HttpDataType.FileUpload)) {
          List<UploadedFile> values = files.computeIfAbsent(data.getName(), k -> new ArrayList<>(1));
          try {
            FileUpload nettyFileUpload = (FileUpload) data;
            final ByteBuf byteBuf = nettyFileUpload.getByteBuf();
            byteBuf.retain();
            context.onClose(ro -> byteBuf.release());

            MediaType contentType;
            String rawContentType = nettyFileUpload.getContentType();
            if (rawContentType == null) {
              contentType = null;
            } else {
              Charset charset = nettyFileUpload.getCharset();
              if (charset == null) {
                contentType = DefaultMediaType.get(rawContentType);
              } else {
                contentType = DefaultMediaType.get(rawContentType + ";charset=" + charset);
              }
            }

            UploadedFile fileUpload = new DefaultUploadedFile(new ByteBufBackedTypedData(byteBuf, contentType), nettyFileUpload.getFilename());

            values.add(fileUpload);
          } catch (IOException e) {
            throw uncheck(e);
          }
        }
        data = decoder.next();
      }
    } catch (HttpPostRequestDecoder.EndOfDataDecoderException ignore) {
      // ignore
    } finally {
      decoder.destroy();
    }

    return new DefaultForm(new ImmutableDelegatingMultiValueMap<>(attributes), new ImmutableDelegatingMultiValueMap<>(files));
  }

}
