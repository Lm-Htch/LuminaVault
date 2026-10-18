package com.lumi.vault.business.websearch.client;

import com.lumi.vault.business.websearch.config.WebSearchProperties;
import com.lumi.vault.business.websearch.dto.WebSourceResult;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;

@Component
public class SauceNaoClient implements WebSourceLookupClient {

    private final WebSearchProperties properties;
    private final SauceNaoParser parser;
    private final RestClient restClient;

    public SauceNaoClient(WebSearchProperties properties, SauceNaoParser parser) {
        this.properties = properties;
        this.parser = parser;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        if (StringUtils.hasText(properties.proxy())) {
            URI proxyUri = URI.create(properties.proxy());
            int port = proxyUri.getPort() > 0 ? proxyUri.getPort() : 7890;
            requestFactory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyUri.getHost(), port)));
        }
        String baseUrl = properties.baseUrl();
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .build();
    }

    @Override
    public boolean available() {
        return properties.available();
    }

    @Override
    public WebSourceResult lookup(byte[] image, String contentType) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("api_key", properties.apiKey());
        form.add("output_type", "2");
        form.add("numres", "3");
        form.add("minsim", Integer.toString((int) Math.round(properties.minSimilarity() * 100)));
        form.add("file", new ByteArrayResource(image) {
            @Override
            public String getFilename() {
                return "image";
            }
        });
        String json;
        try {
            byte[] bytes = restClient.post()
                    .uri("/search.php")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(byte[].class);
            json = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            boolean quota = status == 429 || status == 403;
            throw new WebSourceException("SauceNAO 请求失败（HTTP " + status + "）", quota);
        } catch (Exception e) {
            throw new WebSourceException("SauceNAO 请求异常: " + e.getMessage(), false);
        }
        return parser.parse(json, properties.provider(), properties.minSimilarity());
    }
}
