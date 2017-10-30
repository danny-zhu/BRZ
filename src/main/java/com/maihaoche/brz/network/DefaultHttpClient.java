package com.maihaoche.brz.network;

import com.maihaoche.brz.cipher.CipherHelper;
import com.maihaoche.brz.cipher.DefaultCipherHelper;
import com.maihaoche.brz.coder.DefaultJsonHelper;
import com.maihaoche.brz.coder.JsonHelper;
import com.maihaoche.brz.result.DownloadFile;
import com.maihaoche.brz.utils.Config;
import com.maihaoche.brz.utils.NonceUtils;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collections;

/**
 * Created by alex on 2017/10/24.
 */
public class DefaultHttpClient implements HttpClient {

    private final static String SIGNATURE = "X-Signature";
    private final static String NONCE = "X-Nonce";

    private final static String ACCESS_TOKEN = "X-Access-Token";

    private final CipherHelper cipherHelper;
    private final JsonHelper jsonHelper;

    public DefaultHttpClient() {
        this.cipherHelper = new DefaultCipherHelper(Config.PLATFORM_PUBLIC_KEY, Config.CORP_PRIVATE_KEY);
        this.jsonHelper = new DefaultJsonHelper();
    }

    public DefaultHttpClient(CipherHelper cipherHelper, JsonHelper jsonHelper) {
        this.cipherHelper = cipherHelper;
        this.jsonHelper = jsonHelper;
    }

    public DownloadFile download(String url, Object command, String accessToken) throws IOException {
        HttpResponse response = get(url, command, accessToken);
        String fileName = URLDecoder.decode(response.getFirstHeader("Content-Disposition").getValue(), Config.ENCODING);
        InputStream content = response.getEntity().getContent();
        return new DownloadFile(fileName, content);
    }

    public <T> T get(String url, Class<T> returnType) throws IOException {
        HttpGet httpGet = new HttpGet(url);
        CloseableHttpClient client = HttpClients.createDefault();
        HttpResponse response = client.execute(httpGet);

        ResponseBody responseBody = analyzeBody(response);
        if (responseBody.fail()) {
            throw new RuntimeException(String.format("返回值错误,错误码:%s,原因:%s", responseBody.getCode(), responseBody.getMessage()));
        }

        byte[] ct = Base64.decodeBase64(responseBody.getCt());
        byte[] pt = cipherHelper.decrypt(ct);

        String json = new String(pt, Config.ENCODING);

        return jsonHelper.fromJson(json, returnType);
    }

    public <T> T get(String url, Object command, Class<T> returnType) throws IOException {
        return get(url, command, returnType, StringUtils.EMPTY);
    }

    public <T> T get(String url, Object command, Class<T> returnType, String accessToken) throws IOException {
        HttpResponse response = get(url, command, accessToken);
        ResponseBody responseBody = analyzeBody(response);
        if (responseBody.fail()) {
            throw new RuntimeException(String.format("返回值错误,错误码:%s,原因:%s", responseBody.getCode(), responseBody.getMessage()));
        }
        byte[] pt = cipherHelper.decrypt(responseBody.getCt().getBytes(Config.ENCODING));

        return jsonHelper.fromJson(new String(pt, Config.ENCODING), returnType);
    }

    private HttpResponse get(String url, Object command, String accessToken) throws IOException {
        String ciphertext = encrypt(command);

        String nonce = NonceUtils.nonce();
        String queryString = String.format("ct=%s", ciphertext);
        String signed = sign(queryString, nonce);

        HttpGet httpGet = new HttpGet(String.format("%s?%s", url, queryString));
        httpGet.addHeader(SIGNATURE, signed);
        if (StringUtils.isNotBlank(accessToken)) {
            httpGet.addHeader(ACCESS_TOKEN, accessToken);
        }

        CloseableHttpClient client = HttpClients.createDefault();
        return client.execute(httpGet);
    }

    public void post(String url, Object command, String accessToken) throws IOException {
        String ciphertext = encrypt(command);

        String nonce = NonceUtils.nonce();
        String requestBodyJSON = jsonHelper.toJson(Collections.singletonMap("ct", ciphertext));
        String signed = sign(requestBodyJSON, nonce);

        HttpPost httpPost = new HttpPost(url);
        httpPost.addHeader("Content-Type", "application/json");
        httpPost.addHeader(SIGNATURE, signed);
        httpPost.addHeader(NONCE, nonce);
        httpPost.addHeader(ACCESS_TOKEN, accessToken);

        httpPost.setEntity(new ByteArrayEntity(requestBodyJSON.getBytes(Config.ENCODING)));

        CloseableHttpClient client = HttpClients.createDefault();
        CloseableHttpResponse response = client.execute(httpPost);

        ResponseBody responseBody = analyzeBody(response);
        if (responseBody.fail()) {
            throw new RuntimeException(String.format("返回值错误,错误码:%s,原因:%s", responseBody.getCode(), responseBody.getMessage()));
        }
    }

    private Boolean isSuccess(HttpResponse response) {
        return response.getStatusLine().getStatusCode() == 200 || response.getStatusLine().getStatusCode() == 204;
    }

    private ResponseHeader analyzeHeader(HttpResponse response) throws IOException {
        Header headerSignature = response.getFirstHeader(SIGNATURE);
        if (headerSignature == null) {
            throw new RuntimeException("不存name为X-Signature的header");
        }
        String signature = headerSignature.getValue();
        if (StringUtils.isBlank(signature)) {
            throw new RuntimeException("X-Signature的值为空");
        }

        Header headerNonce = response.getFirstHeader(NONCE);
        if (headerNonce == null) {
            throw new RuntimeException("不存name为X-Nonce 的header");
        }
        String nonce = headerNonce.getValue();
        if (StringUtils.isBlank(nonce)) {
            throw new RuntimeException("不存name为X-Nonce 的header");
        }

        return new ResponseHeader(signature, nonce);
    }

    private ResponseBody analyzeBody(HttpResponse response) throws IOException {

        if (isSuccess(response)) {
            ResponseHeader header = analyzeHeader(response);
            String body = IOUtils.toString(response.getEntity().getContent(), Config.ENCODING);
            if (verifySignature(body, header.getNonce(), header.getSignature())) {
                ResponseBody responseBody = jsonHelper.fromJson(body, ResponseBody.class);
                return responseBody;
            } else {
                throw new RuntimeException("验签失败");
            }
        } else {
            throw new RuntimeException("请求错误,code:" + response.getStatusLine().getStatusCode());
        }
    }

    private Boolean verifySignature(String content, String nonce, String signature) throws UnsupportedEncodingException {
        byte[] si = Base64.decodeBase64(signature);
        return cipherHelper.verify(content.getBytes(Config.ENCODING), nonce.getBytes(Config.ENCODING), si);
    }

    private String encrypt(Object command) throws UnsupportedEncodingException {
        String plaintext = jsonHelper.toJson(command);
        byte[] result = cipherHelper.encrypt(plaintext.getBytes(Config.ENCODING));
        return Base64.encodeBase64URLSafeString(result);
    }

    private String sign(String content, String nonce) throws UnsupportedEncodingException {
        byte[] result = cipherHelper.sign(content.getBytes(Config.ENCODING), nonce.getBytes(Config.ENCODING));
        return Base64.encodeBase64URLSafeString(result);
    }


}
