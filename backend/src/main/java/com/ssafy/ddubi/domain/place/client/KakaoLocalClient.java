package com.ssafy.ddubi.domain.place.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.ssafy.ddubi.domain.place.dto.response.PlaceSearchItem;
import com.ssafy.ddubi.global.common.PageResponse;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.util.SupportedServiceArea;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * PLACE-001 서비스 권역 장소 검색 — 카카오 로컬 키워드 검색 프록시.
 *
 * <p>수도권과 부산은 서로 떨어져 있으므로 두 권역의 {@code rect}를 독립 호출한다. 전국을 덮는 하나의 사각형을 쓰면 미지원 지역 결과가 섞이고 정확한 장소가
 * 밀려나기 때문이다. 각 권역 결과는 원래 순서를 보존한 채 번갈아 합치며 provider place ID로 중복 제거한다. 한 권역 장애를 성공처럼 숨기면 검색 결과가 조용히
 * 사라지므로 호출 하나라도 실패하면 기존 제공자 장애 응답을 반환한다.
 *
 * <p>카카오 제약이 우리 계약과 달라 맞춘 부분: - 카카오 page 는 1부터(1~45) → 우리 0-base 에서 +1 - 카카오 size 최대 15 → 요청 size 를
 * 15 로 상한. 명세서 기본 20 은 카카오가 지원하지 않는다
 */
@Component
public class KakaoLocalClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoLocalClient.class);

    private static final String KEYWORD_PATH = "/v2/local/search/keyword.json";

    /** 카카오 로컬 검색의 하드 리밋. */
    private static final int KAKAO_MAX_SIZE = 15;

    private static final int KAKAO_MAX_PAGE = 45;

    private final RestClient client;
    private final String restApiKey;

    public KakaoLocalClient(
            RestClient.Builder builder,
            @Value("${ddubi.external.kakao.base-url}") String baseUrl,
            @Value("${ddubi.external.kakao.rest-api-key}") String restApiKey) {
        this.client = builder.clone().baseUrl(baseUrl).build();
        this.restApiKey = restApiKey;
    }

    /**
     * @param page 0-base. @param size 15 초과분은 15 로 잘린다.
     */
    public PageResponse<PlaceSearchItem> search(String query, int page, int size) {
        if (!StringUtils.hasText(restApiKey)) {
            log.error("KAKAO_REST_API_KEY 미설정 — 장소 검색 불가");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        int kakaoSize = Math.min(Math.max(size, 1), KAKAO_MAX_SIZE);
        int kakaoPage = Math.min(page + 1, KAKAO_MAX_PAGE);

        List<SearchPage> pages = new ArrayList<>();
        try {
            for (SupportedServiceArea.Area area : SupportedServiceArea.areas()) {
                JsonNode body =
                        client.get()
                                .uri(
                                        uri ->
                                                uri.path(KEYWORD_PATH)
                                                        .queryParam("query", query)
                                                        .queryParam("page", kakaoPage)
                                                        .queryParam("size", kakaoSize)
                                                        .queryParam("rect", area.kakaoRect())
                                                        .build())
                                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey)
                                .retrieve()
                                .body(JsonNode.class);
                if (body == null) {
                    throw new BusinessException(
                            ErrorCode.ROUTE_PROVIDER_UNAVAILABLE,
                            "장소 검색 제공자 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.");
                }
                pages.add(parse(body));
            }
        } catch (RestClientException e) {
            log.warn("카카오 장소 검색 실패: {}", e.getClass().getSimpleName());
            throw new BusinessException(
                    ErrorCode.ROUTE_PROVIDER_UNAVAILABLE, "장소 검색 제공자 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        }
        // 권역 하나가 상위 결과를 전부 차지하지 않도록 같은 순위끼리 번갈아 합친다.
        // 프론트에는 기존 카카오 상한(15)을 유지하여 페이지 계약을 바꾸지 않는다.
        LinkedHashMap<String, PlaceSearchItem> merged = new LinkedHashMap<>();
        for (int rank = 0; rank < kakaoSize && merged.size() < kakaoSize; rank++) {
            for (SearchPage pageResult : pages) {
                if (rank >= pageResult.items().size()) continue;
                PlaceSearchItem item = pageResult.items().get(rank);
                String key =
                        StringUtils.hasText(item.providerPlaceId())
                                ? item.providerPlaceId()
                                : item.name() + ":" + item.lat() + ":" + item.lng();
                merged.putIfAbsent(key, item);
                if (merged.size() == kakaoSize) break;
            }
        }
        List<PlaceSearchItem> items = List.copyOf(merged.values());
        long total = pages.stream().mapToLong(SearchPage::total).sum();
        int totalPages = (int) Math.ceil((double) total / kakaoSize);
        boolean hasNext = pages.stream().anyMatch(SearchPage::hasNext);

        return new PageResponse<>(items, page, kakaoSize, total, Math.max(totalPages, 1), hasNext);
    }

    private static SearchPage parse(JsonNode body) {
        List<PlaceSearchItem> items =
                java.util.stream.StreamSupport.stream(body.path("documents").spliterator(), false)
                        .map(
                                doc ->
                                        new PlaceSearchItem(
                                                doc.path("id").asText(null),
                                                doc.path("place_name").asText(null),
                                                // 도로명 주소가 없는 장소(공원 등)는 지번 주소로 대신한다.
                                                StringUtils.hasText(
                                                                doc.path("road_address_name")
                                                                        .asText(""))
                                                        ? doc.path("road_address_name").asText()
                                                        : doc.path("address_name").asText(null),
                                                decimal(doc, "y"), // 카카오는 y=위도
                                                decimal(doc, "x"))) // x=경도
                        .toList();
        return new SearchPage(
                items,
                body.path("meta").path("pageable_count").asLong(items.size()),
                !body.path("meta").path("is_end").asBoolean(true));
    }

    private record SearchPage(List<PlaceSearchItem> items, long total, boolean hasNext) {}

    private static BigDecimal decimal(JsonNode doc, String field) {
        String value = doc.path(field).asText(null);
        return value == null ? null : new BigDecimal(value);
    }
}
